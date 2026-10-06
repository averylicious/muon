package dev.avery.muon

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloaderFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * #230: actual OfflineStore.move/remove calls, real cache copy/indexes, captured service intents.
 * Removal cases reject stale publication (#234); missing-span cases still characterize an unfixed risk;
 * the target-prefix cases assert the copy's byte check (#281). Not Android service delivery.
 * Reflection only installs disposable shelves and waits for the existing mover's FIFO boundary;
 * it does not replace the worker, copy implementation, command sender or main-thread handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadMoveCharacterizationTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var phone: Shelf
    private lateinit var card: Shelf
    private lateinit var sourceIndex: DefaultDownloadIndex
    private lateinit var targetIndex: DefaultDownloadIndex
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val moverField = OfflineStore::class.java.getDeclaredField("mover").apply { isAccessible = true }
    private val saverField = OfflineStore::class.java.getDeclaredField("saver").apply { isAccessible = true }
    private var previousStore: Any? = null
    private val id = "http://192.168.1.20:7814/7"
    private val request get() = DownloadRequest.Builder(id, Uri.parse("http://192.168.1.20:7814/api1/fileopus/7"))
        .setCustomCacheKey(id).setData(byteArrayOf(1, 2, 3)).build()
    private val bytes = ByteArray(512) { (it % 251).toByte() }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        database = StandaloneDatabaseProvider(app)
        sourceIndex = DefaultDownloadIndex(database, "move_source")
        targetIndex = DefaultDownloadIndex(database, "move_target")
        phone = shelf("phone", sourceIndex, MuonDownloadService::class.java)
        card = shelf("card", targetIndex, MuonCardDownloadService::class.java)
        val prefs = app.getSharedPreferences("move-fixture", Context.MODE_PRIVATE)
        val fixture = OfflineStore.Store(phone, DownloadArt(folders.newFolder("art")),
            PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {}, prefs, database, {}, {})
        fixture.card = card
        previousStore = storeField.get(null)
        storeField.set(null, fixture)
        startedCommands() // Ignore unrelated setup work; no service is instantiated by this fixture.
    }

    @After fun tearDown() {
        try {
            awaitMover()
            awaitSaver()
            shadowOf(Looper.getMainLooper()).idle()
            storeField.set(null, previousStore)
            DownloadMarks.moving = null
            phone.manager.release()
            card.manager.release()
            card.cache.release()
            phone.cache.release()
        } finally {
            database.close()
        }
    }

    @Test fun normalMoveCopiesExactBytesThenPostsOneAddWhileSourceRemains() {
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        assertTrue(card.cache.isCached(id, 0, bytes.size.toLong()))
        assertArrayEquals(bytes, targetBytes())
        assertTrue(phone.cache.isCached(id, 0, bytes.size.toLong()))
        assertTrue("Worker posts the add; it has not started a service yet", startedCommands().isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        val add = startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
        assertEquals(MuonCardDownloadService::class.java.name, add.component?.className)
        assertEquals(request, addRequest(add))
        assertNull("Captured intents are not end-to-end service delivery", targetIndex.getDownload(id))
    }

    @Test fun removingTheSongBeforeQueuedAddRejectsTheLaterAdd() {
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Phone, id, id))))
        val removals = startedCommands()
        assertEquals(1, removals.size)
        assertTrue(removals.all { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD &&
            it.getStringExtra(DownloadService.KEY_CONTENT_ID) == id })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        // Do not blindly remove target spans: ownership-aware partial cleanup is still separate.
        assertArrayEquals(bytes, targetBytes())
    }

    @Test fun removingAllBeforeQueuedAddRejectsTheLaterAdd() {
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        OfflineStore.removeAll(app)
        awaitSaver()
        val removals = startedCommands()
        assertEquals(1, removals.size)
        assertTrue(removals.all { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD &&
            it.getStringExtra(DownloadService.KEY_CONTENT_ID) == id })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
    }

    @Test fun missingLaterSpanLeavesPartialDestinationBytesWithoutAnAddOrDownloadRecord() {
        val chunk = ByteArray(128 * 1024) { (it % 251).toByte() }
        seed(phone.cache, 0, chunk)
        val later = seed(phone.cache, chunk.size.toLong(), chunk)
        setLength(phone.cache, 2L * chunk.size)
        putCompleted(2L * chunk.size)
        assertTrue(phone.cache.isCached(id, 0, 2L * chunk.size))
        assertTrue(later.delete()) // Only a disposable fixture file, before the actual worker reads it.
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertNull(targetIndex.getDownload(id))
        assertEquals("1 copy couldn't be moved. Its saved entry was kept.", ShadowToast.getTextOfLatestToast())
        assertTrue("Failed copy retains unindexed target spans", card.cache.getCachedSpans(id).isNotEmpty())
        assertFalse(card.cache.isCached(id, 0, 2L * chunk.size))
        assertArrayEquals(chunk, requireNotNull(phone.cache.getCachedSpans(id).first().file).readBytes())
    }

    @Test fun unrelatedCrossShelfCompletionNeverDeletesTheOlderCopy() {
        completeSource(bytes)
        val otherBytes = ByteArray(bytes.size) { 99 }
        seed(card.cache, 0, otherBytes)
        setLength(card.cache, otherBytes.size.toLong())
        val otherRequest = request.copyWithId(id) // Same old ID/key, different audio: not a move receipt.
        val completed = Download(otherRequest, Download.STATE_COMPLETED, 0L, 0L, otherBytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
        targetIndex.putDownload(completed)
        assertFalse(OfflineStore.completeMovedCopyNow(app, OfflineStore.get(app), card, completed))
        assertTrue(startedCommands().isEmpty())
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).first().file).readBytes())
        assertArrayEquals(otherBytes, targetBytes())
        assertNotNull(sourceIndex.getDownload(id))
    }

    @Test fun onlyTrackedByteIdenticalCompletionRequestsSourceRemoval() {
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, startedCommands().count { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        val completed = Download(request, Download.STATE_COMPLETED, 0L, 0L, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
        targetIndex.putDownload(completed)
        assertTrue(OfflineStore.completeMovedCopyNow(app, OfflineStore.get(app), card, completed))
        val removal = startedCommands().single()
        assertEquals(DownloadService.ACTION_REMOVE_DOWNLOAD, removal.action)
        assertEquals(MuonDownloadService::class.java.name, removal.component?.className)
        assertEquals(id, removal.getStringExtra(DownloadService.KEY_CONTENT_ID))
        assertFalse("The receipt is consumed, not replayable", OfflineStore.completeMovedCopyNow(app,
            OfflineStore.get(app), card, completed))
    }

    @Test fun sourceWithStaleShortLengthIsKeptWithoutPublishingAPrefixAsTheWholeCopy() {
        val original = bytes + byteArrayOf(77)
        val file = seed(phone.cache, 0, original)
        setLength(phone.cache, bytes.size.toLong())
        putCompleted(bytes.size.toLong())
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertTrue(card.cache.getCachedSpans(id).isEmpty())
        assertArrayEquals(original, file.readBytes())
        assertNotNull(sourceIndex.getDownload(id))
    }

    @Test fun extraSourceBytesAfterPublicationCannotBeDeletedByAReceiptForOnlyItsPrefix() {
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, startedCommands().count { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        setLength(phone.cache, androidx.media3.common.C.LENGTH_UNSET.toLong())
        val extra = seed(phone.cache, bytes.size.toLong() + 10, byteArrayOf(77))
        setLength(phone.cache, bytes.size.toLong())
        val completed = Download(request, Download.STATE_COMPLETED, 0, 0, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
        targetIndex.putDownload(completed)
        assertFalse(OfflineStore.completeMovedCopyNow(app, OfflineStore.get(app), card, completed))
        assertTrue(startedCommands().isEmpty())
        assertArrayEquals(byteArrayOf(77), extra.readBytes())
        assertNotNull(sourceIndex.getDownload(id))
        assertNotNull(targetIndex.getDownload(id))
    }

    @Test fun recordedCompleteTargetWithExtraBytesIsNotAdoptedOrTruncated() {
        completeSource(bytes)
        val retained = seed(card.cache, 0, bytes + byteArrayOf(99))
        setLength(card.cache, bytes.size.toLong())
        targetIndex.putDownload(Download(request, Download.STATE_COMPLETED, 0, 0, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertArrayEquals(bytes + byteArrayOf(99), retained.readBytes())
        assertEquals(bytes.size.toLong(), ContentMetadata.getContentLength(card.cache.getContentMetadata(id)))
        assertNotNull(sourceIndex.getDownload(id))
        assertEquals("1 copy couldn't be moved. Its saved entry was kept.", ShadowToast.getTextOfLatestToast())
    }

    @Test fun trailingFragmentAddedAfterMovePublicationPreventsSourceRemoval() {
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, startedCommands().count { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        // A real writer must extend/unset the known length before SimpleCache accepts another span.
        // A later metadata update can restore the old length without deleting that retained span.
        setLength(card.cache, androidx.media3.common.C.LENGTH_UNSET.toLong())
        val fragment = seed(card.cache, bytes.size.toLong() + 10, byteArrayOf(88, 99))
        setLength(card.cache, bytes.size.toLong())
        val completed = Download(request, Download.STATE_COMPLETED, 0, 0, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
        targetIndex.putDownload(completed)
        assertFalse(OfflineStore.completeMovedCopyNow(app, OfflineStore.get(app), card, completed))
        assertTrue(startedCommands().isEmpty())
        assertArrayEquals(byteArrayOf(88, 99), fragment.readBytes())
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
        assertNotNull(sourceIndex.getDownload(id))
        assertNotNull(targetIndex.getDownload(id))
    }

    @Test fun changedTargetBytesOrRemovalInvalidationKeepBothCopies() {
        completeSource(bytes)
        val store = OfflineStore.get(app)
        assertTrue(store.moves.remember(phone, card, request))
        val changed = ByteArray(bytes.size) { 33 }
        seed(card.cache, 0, changed)
        setLength(card.cache, changed.size.toLong())
        val completed = Download(request, Download.STATE_COMPLETED, 0L, 0L, changed.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
        targetIndex.putDownload(completed)
        assertFalse(OfflineStore.completeMovedCopyNow(app, store, card, completed))
        assertTrue(startedCommands().isEmpty())
        assertArrayEquals(changed, targetBytes())
        assertTrue(store.moves.remember(phone, card, request))
        store.moves.invalidate(id)
        assertFalse(OfflineStore.completeMovedCopyNow(app, store, card, completed))
        assertTrue(startedCommands().isEmpty())
        assertNotNull(sourceIndex.getDownload(id))
    }

    @Test fun anAliasedSourceCannotBeRemovedThroughTheSavedEntryAction() {
        completeSource(bytes)
        val alias = DownloadRequest.Builder("unknown-alias", request.uri).setCustomCacheKey(id).build()
        sourceIndex.putDownload(Download(alias, Download.STATE_COMPLETED, 0L, 0L, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        assertEquals(OfflineStore.SavedRemoval.NotOwned, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Phone, id, id))))
        assertTrue(startedCommands().isEmpty())
        assertNotNull(sourceIndex.getDownload(id))
        assertNotNull(sourceIndex.getDownload(alias.id))
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).first().file).readBytes())
    }

    // Refuse known conflicting spans before any destination write. The original prefix is kept;
    // no source suffix is appended to a different copy and no add is sent.
    @Test fun anAlreadyCompleteIdenticalRecordedTargetNeedsNoCacheOrRecordRewrite() {
        completeSource(bytes)
        seed(card.cache, 0, bytes)
        setLength(card.cache, bytes.size.toLong())
        card.cache.applyContentMetadataMutations(id, ContentMetadataMutations().set("old-note", "kept"))
        val old = Download(request, Download.STATE_COMPLETED, 3, 4, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
        targetIndex.putDownload(old)
        val files = card.cache.getCachedSpans(id).map { requireNotNull(it.file).name }
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        val add = startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
        assertEquals(request, addRequest(add))
        assertArrayEquals(bytes, targetBytes())
        assertEquals(files, card.cache.getCachedSpans(id).map { requireNotNull(it.file).name })
        assertEquals("kept", card.cache.getContentMetadata(id).get("old-note", ""))
        assertEquals(old.request, targetIndex.getDownload(id)?.request)
        assertEquals(old.startTimeMs, targetIndex.getDownload(id)?.startTimeMs)
        assertTrue(phone.cache.isCached(id, 0, bytes.size.toLong()))
    }

    @Test fun anOlderRecordedPartialCopyIsNeverExtendedDespiteEqualRequestTags() {
        completeSource(bytes)
        val prefix = bytes.copyOf(128)
        seed(card.cache, 0, prefix)
        setLength(card.cache, bytes.size.toLong())
        val held = Download(request, Download.STATE_STOPPED, 3, 4, bytes.size.toLong(),
            RETAINED_STOP_REASON, Download.FAILURE_REASON_NONE)
        targetIndex.putDownload(held)
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().isEmpty())
        assertArrayEquals(prefix, targetBytes())
        assertFalse(card.cache.isCached(id, 128, 1))
        assertEquals(held.request, targetIndex.getDownload(id)?.request)
        assertEquals(Download.STATE_STOPPED, targetIndex.getDownload(id)?.state)
        assertEquals(RETAINED_STOP_REASON, targetIndex.getDownload(id)?.stopReason)
        assertTrue(phone.cache.isCached(id, 0, bytes.size.toLong()))
    }

    @Test fun conflictingKnownDestinationLengthIsKeptBeforeAnyMetadataOrSpanWrite() {
        completeSource(bytes)
        val prefix = bytes.copyOf(128)
        seed(card.cache, 0, prefix)
        val oldLength = bytes.size.toLong() + 100
        setLength(card.cache, oldLength)
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().isEmpty())
        assertArrayEquals(prefix, targetBytes())
        assertFalse(card.cache.isCached(id, 128, 1))
        assertEquals(oldLength, ContentMetadata.getContentLength(card.cache.getContentMetadata(id)))
        assertTrue(phone.cache.isCached(id, 0, bytes.size.toLong()))
    }

    @Test fun differentUnindexedTargetPrefixIsRejectedWithoutAnAddAndKept() {
        val old = ByteArray(200) { (255 - it % 251).toByte() } // Differs from [bytes] at every index.
        seed(card.cache, 0, old)
        assertNull(targetIndex.getDownload(id))
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertNull(targetIndex.getDownload(id))
        val earlier = card.cache.getCachedSpans(id).single { it.position == 0L }
        assertEquals(old.size.toLong(), earlier.length)
        assertArrayEquals(old, requireNotNull(earlier.file).readBytes())
        assertArrayEquals(old, targetBytes())
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
        assertNotNull(sourceIndex.getDownload(id))
    }

    // A legitimate resume: a target prefix that matches the source is kept and the move is published.
    @Test fun identicalUnindexedTargetPrefixIsReusedAndAdded() {
        seed(card.cache, 0, bytes.copyOfRange(0, 200))
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        assertArrayEquals(bytes, targetBytes())
        assertEquals(bytes.size.toLong(), ContentMetadata.getContentLength(card.cache.getContentMetadata(id)))
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
        shadowOf(Looper.getMainLooper()).idle()
        val add = startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
        assertEquals(MuonCardDownloadService::class.java.name, add.component?.className)
        assertEquals(request, addRequest(add))
    }

    @Test fun identicalPrefixAcrossSeveralComparisonBlocksIsAdded() {
        val payload = ByteArray(2 * 64 * 1024 + 17) { (it % 251).toByte() }
        seed(card.cache, 0, payload.copyOfRange(0, 90 * 1024))
        completeSource(payload)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertArrayEquals(payload, targetBytes())
        assertArrayEquals(payload, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
        assertEquals(request, addRequest(startedCommands().single {
            it.action == DownloadService.ACTION_ADD_DOWNLOAD }))
        assertNull(targetIndex.getDownload(id))
    }

    @Test fun mismatchAfterTheFirstComparisonBlockIsRejectedWithoutDeletingBytes() {
        val payload = ByteArray(2 * 64 * 1024 + 17) { (it % 251).toByte() }
        val old = payload.copyOfRange(0, 90 * 1024)
        old[80 * 1024] = (old[80 * 1024].toInt() xor 1).toByte()
        seed(card.cache, 0, old)
        completeSource(payload)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertNull(targetIndex.getDownload(id))
        assertArrayEquals(old, targetBytes())
        assertArrayEquals(payload, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
        assertNotNull(sourceIndex.getDownload(id))
    }

    @Test fun failedMoveOverAnUnindexedTargetPrefixKeepsItAndTheSourceWithoutAnAdd() {
        val old = ByteArray(1000) { (255 - it % 251).toByte() }
        seed(card.cache, 0, old)
        val chunk = ByteArray(128 * 1024) { (it % 251).toByte() }
        seed(phone.cache, 0, chunk)
        val later = seed(phone.cache, chunk.size.toLong(), chunk)
        setLength(phone.cache, 2L * chunk.size)
        putCompleted(2L * chunk.size)
        assertTrue(later.delete()) // Only a disposable fixture file, before the actual worker reads it.
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertNull(targetIndex.getDownload(id))
        val earlier = card.cache.getCachedSpans(id).single { it.position == 0L }
        assertEquals(old.size.toLong(), earlier.length)
        assertArrayEquals(old, requireNotNull(earlier.file).readBytes())
        assertFalse(card.cache.isCached(id, 0, 2L * chunk.size))
        assertArrayEquals(chunk, requireNotNull(phone.cache.getCachedSpans(id).first().file).readBytes())
        assertNotNull(sourceIndex.getDownload(id))
    }

    @Test fun aConflictingTargetIslandDoesNotFillTheHolesAroundIt() {
        val old = ByteArray(128) { (255 - it % 251).toByte() }
        val island = seed(card.cache, 200, old)
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertNull(targetIndex.getDownload(id))
        val spans = card.cache.getCachedSpans(id)
        assertEquals(1, spans.size)
        assertEquals(200L, spans.single().position)
        assertEquals(128L, card.cache.cacheSpace)
        assertArrayEquals(old, island.readBytes())
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
        assertNotNull(sourceIndex.getDownload(id))
    }

    @Test fun aMatchingTargetIslandCanStillBeCompletedAndAdded() {
        seed(card.cache, 200, bytes.copyOfRange(200, 328))
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertArrayEquals(bytes, targetBytes())
        assertEquals(request, addRequest(startedCommands().single {
            it.action == DownloadService.ACTION_ADD_DOWNLOAD }))
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
    }

    @Test fun destinationBytesBeyondTheSourceAreKeptWithoutTruncationOrAnAdd() {
        val old = bytes + byteArrayOf(1)
        val retained = seed(card.cache, 0, old)
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertNull(targetIndex.getDownload(id))
        assertArrayEquals(old, retained.readBytes())
        assertEquals(old.size.toLong(), card.cache.cacheSpace)
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
        assertNotNull(sourceIndex.getDownload(id))
    }

    @Test fun unknownSourceLengthRejectsCopyWithoutRemovingSourceOrAddingDestination() {
        seed(phone.cache, 0, bytes)
        putCompleted(bytes.size.toLong())
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertTrue(card.cache.getCachedSpans(id).isEmpty())
        assertTrue(phone.cache.isCached(id, 0, bytes.size.toLong()))
        assertNotNull(sourceIndex.getDownload(id))
    }

    @Test fun failureAfterAMatchingPrefixPreservesThatPrefixAndReportsTheKeptOriginal() {
        val chunk = ByteArray(128 * 1024) { (it % 251).toByte() }
        val prefix = chunk.copyOf(1000)
        val priorFile = seed(card.cache, 0, prefix)
        seed(phone.cache, 0, chunk)
        val later = seed(phone.cache, chunk.size.toLong(), chunk)
        setLength(phone.cache, 2L * chunk.size)
        putCompleted(2L * chunk.size)
        assertTrue(later.delete()) // Disposable source fixture only, not a device eject.
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().isEmpty())
        assertNull(targetIndex.getDownload(id))
        assertArrayEquals(prefix, priorFile.readBytes())
        assertFalse(card.cache.isCached(id, 0, 2L * chunk.size))
        assertNotNull(sourceIndex.getDownload(id))
        assertArrayEquals(chunk, requireNotNull(phone.cache.getCachedSpans(id).first().file).readBytes())
        assertEquals("1 copy couldn't be moved. Its saved entry was kept.", ShadowToast.getTextOfLatestToast())
    }

    @Test fun aBatchOfUnreadableCompletedSourcesReportsEveryAttemptWithoutPublishing() {
        putCompleted(bytes.size.toLong()) // Completed index rows, deliberately no source spans/length.
        val second = DownloadRequest.Builder("another-saved-copy", request.uri)
            .setCustomCacheKey("another-saved-copy").setData(request.data).build()
        sourceIndex.putDownload(Download(second, Download.STATE_COMPLETED, 1, 1, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().isEmpty())
        assertTrue(card.cache.keys.isEmpty())
        assertNotNull(sourceIndex.getDownload(id))
        assertNotNull(sourceIndex.getDownload(second.id))
        assertEquals("2 copies couldn't be moved. Their saved entries were kept.", ShadowToast.getTextOfLatestToast())
    }

    private fun shelf(name: String, index: DefaultDownloadIndex,
        service: Class<out DownloadService>): Shelf {
        val cache = SimpleCache(folders.newFolder(name), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        val neverDownload = DownloaderFactory { error("Fixture must not start a downloader/network") }
        return Shelf(cache, DownloadManager(app, index, neverDownload), service)
    }

    private fun completeSource(payload: ByteArray) {
        seed(phone.cache, 0, payload)
        setLength(phone.cache, payload.size.toLong())
        putCompleted(payload.size.toLong())
    }

    private fun putCompleted(length: Long) {
        sourceIndex.putDownload(Download(request, Download.STATE_COMPLETED, 1, 1, length,
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }

    private fun seed(cache: SimpleCache, position: Long, payload: ByteArray): File {
        val hole = requireNotNull(cache.startReadWrite(id, position, payload.size.toLong()))
        try {
            val file = cache.startFile(id, position, payload.size.toLong())
            file.writeBytes(payload)
            cache.commitFile(file, payload.size.toLong())
            return file
        } finally { cache.releaseHoleSpan(hole) }
    }

    private fun setLength(cache: SimpleCache, length: Long) {
        cache.applyContentMetadataMutations(id, ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, length)
        })
    }

    private fun targetBytes(): ByteArray = card.cache.getCachedSpans(id)
        .sortedBy { it.position }.fold(byteArrayOf()) { acc, span -> acc + requireNotNull(span.file).readBytes() }

    @Suppress("DEPRECATION")
    private fun addRequest(intent: Intent): DownloadRequest =
        requireNotNull(intent.getParcelableExtra(DownloadService.KEY_DOWNLOAD_REQUEST))

    private fun startedCommands(): List<Intent> = buildList {
        val shadow = shadowOf(app)
        while (true) add(shadow.nextStartedService ?: break)
    }

    private fun awaitSaver() {
        val saver = saverField.get(null) as ExecutorService
        saver.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun awaitMover() {
        val mover = moverField.get(null) as ExecutorService
        mover.submit {}.get(10, TimeUnit.SECONDS)
    }
}
