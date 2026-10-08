package dev.avery.muon

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.Parcel
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.WritableDownloadIndex
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
    /** The phone's index as its manager and every census see it: [sourceIndex], failing only when asked. */
    private lateinit var phoneIndex: CensusFaults
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val moverField = OfflineStore::class.java.getDeclaredField("mover").apply { isAccessible = true }
    private val saverField = OfflineStore::class.java.getDeclaredField("saver").apply { isAccessible = true }
    private val ownershipField = OfflineStore::class.java.getDeclaredField("moveOwnership").apply { isAccessible = true }
    @Volatile private var onCardCheck: () -> Unit = {}
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
        phoneIndex = CensusFaults(sourceIndex)
        phone = shelf("phone", phoneIndex, MuonDownloadService::class.java)
        // Every card availability check also runs [onCardCheck], so a test can act at a counted point.
        card = shelf("card", targetIndex, MuonCardDownloadService::class.java) { onCardCheck(); true }
        val prefs = app.getSharedPreferences("move-fixture", Context.MODE_PRIVATE)
        val fixture = OfflineStore.Store(phone, DownloadArt(folders.newFolder("art")),
            PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {}, prefs, database, {}, {})
        fixture.card = card
        previousStore = storeField.get(null)
        storeField.set(null, fixture)
        // A move is admitted only with both managers initialized and idle (#230): wait for that here.
        awaitSettled(phone.manager)
        awaitSettled(card.manager)
        startedCommands() // Ignore unrelated setup work; no service is instantiated by this fixture.
    }

    /** Bounded: pumps the main looper until the manager has loaded its index and processed every command. */
    private fun awaitSettled(manager: androidx.media3.exoplayer.offline.DownloadManager) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (manager.isInitialized && manager.isIdle) return
            check(System.nanoTime() < deadline) { "The manager did not settle" }
            Thread.sleep(5) // Yields to Media3's real internal handler thread; not a timing assertion.
        }
    }

    @After fun tearDown() {
        try {
            awaitMover()
            awaitSaver()
            shadowOf(Looper.getMainLooper()).idle()
            OfflineStore.moveOutputs = MoveFileOutputs.Real
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

    /**
     * #230: the production move writes its destination through the strict sink, around the real file output
     * (counted here, not replaced): every file it opened was flushed, synced and closed before the Add.
     */
    @Test fun aHealthyMoveWritesItsFileThroughTheStrictSinkBeforeHandingOver() {
        var flushes = 0; var syncs = 0; var closes = 0; var opens = 0
        OfflineStore.moveOutputs = MoveFileOutputs { file ->
            val real = MoveFileOutputs.Real.open(file)
            opens++
            object : MoveFileOutput {
                override fun write(buffer: ByteArray, offset: Int, length: Int) = real.write(buffer, offset, length)
                override fun flush() { real.flush(); flushes++ }
                override fun sync() { real.sync(); syncs++ }
                override fun close() { real.close(); closes++ }
            }
        }
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, opens)
        assertEquals(listOf(1, 1, 1), listOf(flushes, syncs, closes))
        assertArrayEquals(bytes, targetBytes())
        assertEquals(request, addRequest(startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }))
    }

    /**
     * #230: a destination file whose sync or close fails is not committed, the copy is not handed over and
     * the source stays; a later move with a healthy output still completes. Only the output is injected:
     * the move, CacheDataSource, CacheWriter, caches and indexes are real.
     */
    @Test fun aDestinationFileThatIsNotWrittenOutIsNeitherCommittedNorHandedOver() {
        for (failing in listOf("sync", "close")) {
            OfflineStore.moveOutputs = MoveFileOutputs { file ->
                val real = MoveFileOutputs.Real.open(file)
                object : MoveFileOutput {
                    override fun write(buffer: ByteArray, offset: Int, length: Int) = real.write(buffer, offset, length)
                    override fun flush() = real.flush()
                    override fun sync() { if (failing == "sync") throw java.io.IOException("Injected sync failure"); real.sync() }
                    override fun close() { real.close(); if (failing == "close") throw java.io.IOException("Injected close failure") }
                }
            }
            if (failing == "sync") completeSource(bytes)
            OfflineStore.move(app, toCard = true)
            awaitMover()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(failing, startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
            assertNull(failing, targetIndex.getDownload(id))
            assertTrue("$failing: nothing was committed to the destination", card.cache.getCachedSpans(id).isEmpty())
            assertEquals("1 copy couldn't be moved. Its saved entry was kept.", ShadowToast.getTextOfLatestToast())
            assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
            assertNotNull(sourceIndex.getDownload(id))
        }
        OfflineStore.moveOutputs = MoveFileOutputs.Real
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertArrayEquals(bytes, targetBytes())
        assertEquals(request, addRequest(startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }))
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
        // The move holds its command exclusion until its main-thread hand-over runs (#230): the removal is
        // refused before any command is sent, and the song's hand-over is revoked (#234).
        assertEquals(OfflineStore.SavedRemoval.Busy, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Phone, id, id))))
        assertTrue(startedCommands().isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        // Do not blindly remove target spans: ownership-aware partial cleanup is still separate.
        assertArrayEquals(bytes, targetBytes())
        // Once the move has finished, the same removal is sent exactly as before.
        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Phone, id, id))))
        val removals = startedCommands()
        assertEquals(1, removals.size)
        assertTrue(removals.all { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD &&
            it.getStringExtra(DownloadService.KEY_CONTENT_ID) == id })
    }

    @Test fun removingAllBeforeQueuedAddRejectsTheLaterAdd() {
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        // Refused while the move holds its exclusion (#230), but its hand-overs are revoked at once (#234).
        OfflineStore.removeAll(app)
        awaitSaver()
        assertTrue(startedCommands().isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertEquals("A move is under way, so nothing was removed, and nothing more will be moved. " +
            "Remove all again when the move finishes.", ShadowToast.getTextOfLatestToast())
        // Once the move has finished, Remove all sends the same per-row removal as before.
        OfflineStore.removeAll(app)
        awaitSaver()
        val removals = startedCommands()
        assertEquals(1, removals.size)
        assertTrue(removals.all { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD &&
            it.getStringExtra(DownloadService.KEY_CONTENT_ID) == id })
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
        // A failed copy still releases the move's command exclusion (#230): the next removal is judged as
        // usual, not refused as busy.
        assertNotEquals(OfflineStore.SavedRemoval.Busy, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Phone, id, id))))
    }

    @Test fun aSecondMoveBeforeTheFirstReleasesIsRefusedAndAddsNothingMore() {
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        // Before the first move's release step runs, a second move is refused; nothing more is copied.
        awaitMover()
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Exactly the first move's one hand-over", 1,
            startedCommands().count { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertArrayEquals(bytes, targetBytes())
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
        admitCopiedAdd()
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
        admitCopiedAdd()
        assertTrue(OfflineStore.completeMovedCopyNow(app, OfflineStore.get(app), card, completed))
        val removal = startedCommands().single()
        assertEquals(DownloadService.ACTION_REMOVE_DOWNLOAD, removal.action)
        assertEquals(MuonDownloadService::class.java.name, removal.component?.className)
        assertEquals(id, removal.getStringExtra(DownloadService.KEY_CONTENT_ID))
        assertFalse("The queued removal cannot be sent twice", OfflineStore.completeMovedCopyNow(app,
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
        admitCopiedAdd()
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
        admitCopiedAdd()
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
        admitCopiedAdd()
        val changed = ByteArray(bytes.size) { 33 }
        seed(card.cache, 0, changed)
        setLength(card.cache, changed.size.toLong())
        val completed = Download(request, Download.STATE_COMPLETED, 0L, 0L, changed.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
        targetIndex.putDownload(completed)
        admitCopiedAdd()
        assertFalse(OfflineStore.completeMovedCopyNow(app, store, card, completed))
        assertTrue(startedCommands().isEmpty())
        assertArrayEquals(changed, targetBytes())
        assertTrue(store.moves.remember(phone, card, request))
        admitCopiedAdd()
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

    /**
     * #230: the song is removed while its copy is being written. The removal is made from the card's
     * availability check, counted: before the copy, move() and the batch loop each check once and the copy
     * once more, so the sixth check falls after the writer has cached at least one 128 KiB block of this
     * multi-block payload. Asserted from bytes, not from the count: some but not all were written.
     */
    @Test fun removalDuringTheCopyStopsWritingKeepsWrittenBytesAndARetryResumesThem() {
        val payload = ByteArray(8 * 128 * 1024 + 17) { (it * 7 % 251).toByte() }
        completeSource(payload)
        val ownership = ownershipField.get(null) as DownloadMoveOwnership
        var checks = 0
        onCardCheck = { if (++checks == 6) ownership.remove(listOf(id)) }
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        onCardCheck = {}
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertNull(targetIndex.getDownload(id))
        val written = targetBytes()
        assertTrue("Writing stopped before the end: ${written.size} of ${payload.size}", written.size < payload.size)
        assertTrue("Some bytes were written before the removal", written.isNotEmpty())
        // Kept exactly as written: a prefix of the source, neither removed, truncated nor relabelled.
        assertArrayEquals(payload.copyOf(written.size), written)
        assertArrayEquals(payload, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
        assertNotNull(sourceIndex.getDownload(id))

        // A later, separate move owns the song again: it checks the kept prefix against the source and
        // fills only the rest, then hands the exact copy over once.
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertArrayEquals(payload, targetBytes())
        assertEquals(request, addRequest(startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }))
        assertArrayEquals(payload, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
    }

    // ---- #253: the production censuses read names and states only; each acted-on record is read again ----

    private val large = ByteArray(512 * 1024) { (it % 253).toByte() }
    private val keptNotice = "1 copy was kept where it was: Muon can't tell their bytes belong to them alone."

    @Test fun anUndisplayableAliasWithALargeRecordStillPreventsRemovalRemoveAllAndMove() {
        completeSource(bytes)
        // An older row naming the song's key, with a large stored record, an ID too long for a saved handle
        // and a state no move takes: never listed, yet it still owns the bytes.
        val hidden = DownloadRequest.Builder("hidden/" + "X".repeat(1200), request.uri).setCustomCacheKey(id)
            .setData(large).build()
        sourceIndex.putDownload(Download(hidden, Download.STATE_FAILED, 1, 1, 0,
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_UNKNOWN))
        assertNull(SavedRef.download(SavedShelf.Phone, hidden.id, id))
        assertEquals(OfflineStore.SavedRemoval.NotOwned, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Phone, id, id))))
        assertEquals(0 to 2, OfflineStore.removeAllNow(app))
        assertTrue(startedCommands().isEmpty())
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().isEmpty())
        assertTrue(card.cache.keys.isEmpty())
        assertEquals(keptNotice, ShadowToast.getTextOfLatestToast())
        assertArrayEquals(large, sourceIndex.getDownload(hidden.id)?.request?.data)
        assertEquals(request, sourceIndex.getDownload(id)?.request)
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
    }

    @Test fun aSoleOwnedRowWithALargeRecordIsStillRemovedOneByOneAndByRemoveAll() {
        completeSource(bytes, largeRequest())
        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Phone, id, id))))
        assertEquals(1 to 0, OfflineStore.removeAllNow(app))
        val removals = startedCommands()
        assertEquals(2, removals.size)
        assertTrue(removals.all { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD &&
            it.getStringExtra(DownloadService.KEY_CONTENT_ID) == id &&
            it.component?.className == MuonDownloadService::class.java.name })
        // Captured, not delivered: the record is still the one stored.
        assertArrayEquals(large, sourceIndex.getDownload(id)?.request?.data)
    }

    @Test fun completionComparesTheExactTargetRequestReadAgainAndStillFinishesAnExactMove() {
        val moved = largeRequest()
        completeSource(bytes, moved)
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(moved, addRequest(startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }))
        assertArrayEquals(bytes, targetBytes())
        val store = OfflineStore.get(app)
        val completed = Download(moved, Download.STATE_COMPLETED, 0, 0, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
        // The Add was only captured: no target row, so nothing is compared and both copies stay.
        admitCopiedAdd()
        assertFalse(OfflineStore.completeMovedCopyNow(app, store, card, completed))
        // A target row under the same ID, key and address but another stored record is not the moved request.
        assertTrue(store.moves.remember(phone, card, moved))
        admitCopiedAdd()
        targetIndex.putDownload(Download(request, Download.STATE_COMPLETED, 0, 0, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        assertFalse(OfflineStore.completeMovedCopyNow(app, store, card, completed))
        assertTrue(startedCommands().isEmpty())
        // The exact request completes and asks once for the original's removal.
        assertTrue(store.moves.remember(phone, card, moved))
        admitCopiedAdd()
        targetIndex.putDownload(completed)
        assertTrue(OfflineStore.completeMovedCopyNow(app, store, card, completed))
        val removal = startedCommands().single()
        assertEquals(DownloadService.ACTION_REMOVE_DOWNLOAD, removal.action)
        assertEquals(MuonDownloadService::class.java.name, removal.component?.className)
        assertEquals(id, removal.getStringExtra(DownloadService.KEY_CONTENT_ID))
        assertArrayEquals(large, sourceIndex.getDownload(id)?.request?.data)
        assertArrayEquals(large, targetIndex.getDownload(id)?.request?.data)
    }

    @Test fun aMoveIsRefusedWhenTheTargetRowIsNotTheExactRequest() {
        completeSource(bytes)
        // Same ID, key and address on the card, another stored record: read again, it is not this request.
        val other = DownloadRequest.Builder(id, request.uri).setCustomCacheKey(id).setData(large).build()
        targetIndex.putDownload(Download(other, Download.STATE_COMPLETED, 0, 0, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().isEmpty())
        assertTrue(card.cache.getCachedSpans(id).isEmpty())
        assertEquals(keptNotice, ShadowToast.getTextOfLatestToast())
        assertArrayEquals(large, targetIndex.getDownload(id)?.request?.data)
        assertEquals(request, sourceIndex.getDownload(id)?.request)
    }

    @Test fun aCensusThatFailsPartWayClosesItsCursorAndNothingIsRemovedClaimedOrMoved() {
        completeSource(bytes)
        val other = DownloadRequest.Builder("saved/other", request.uri).setCustomCacheKey("saved/other").build()
        sourceIndex.putDownload(Download(other, Download.STATE_COMPLETED, 1, 1, 0,
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        phoneIndex.failAtRow = 2 // Every whole read of the phone's index now fails at its second row.
        assertEquals(OfflineStore.SavedRemoval.NotOwned, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Phone, id, id))))
        assertEquals("The card's census still runs; the phone's sends nothing", 0 to 0, OfflineStore.removeAllNow(app))
        assertThrows(IllegalStateException::class.java) { OfflineStore.takenNames(OfflineStore.get(app)) }
        val claims = PlayedClaims().apply { read(phoneIndex) }
        assertFalse("A partial read leaves played copies unremovable", claims.known)
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().isEmpty())
        assertTrue(card.cache.keys.isEmpty())

        // A real copy and receipt, then a census that fails at completion: both copies stay.
        phoneIndex.failAtRow = 0
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(request, addRequest(startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }))
        admitCopiedAdd()
        val completed = Download(request, Download.STATE_COMPLETED, 0, 0, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
        targetIndex.putDownload(completed)
        phoneIndex.failAtRow = 2
        assertFalse(OfflineStore.completeMovedCopyNow(app, OfflineStore.get(app), card, completed))
        assertTrue(startedCommands().isEmpty())
        assertArrayEquals(bytes, targetBytes())
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
        assertEquals(request, sourceIndex.getDownload(id)?.request)
        assertEquals(request, targetIndex.getDownload(id)?.request)
        // Removal, Remove all, names, claims, move and completion: each failed read closed its cursor.
        assertEquals(6, phoneIndex.failing.size)
        assertTrue(phoneIndex.failing.all { it.isClosed })
        phoneIndex.failAtRow = 0
        claims.read(phoneIndex)
        assertTrue("Read whole, the claims are known", claims.known)
    }

    // ---- #253: a batch copies no more than its free move receipts can hand over ----

    private val deferredNotice = Regex("(1 more copy stays where it is|[0-9]+ more copies stay where they are)\\. " +
        "Move again once this move finishes\\.")

    @Test fun aBatchCopiesOnlyAsManyAsItsReceiptsCanHandOverAndTheRestMoveLater() {
        val store = withReceiptCapacity(2)
        val sources = listOf("saved/a", "saved/b", "saved/c").associateWith { completeKeyed(it) }
        val beforeToasts = ShadowToast.shownToastCount()
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        val added = startedCommands().filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }.map(::addRequest)
        assertEquals(2, added.size)
        assertTrue("Each Add is a source's exact request", added.all { it == sources[it.id]?.first })
        val left = (sources.keys - added.map { it.id }.toSet()).single()
        assertEquals("1 more copy stays where it is. Move again once this move finishes.", ShadowToast.getTextOfLatestToast())
        assertEquals("One aggregate deferral, no per-copy pending warnings", beforeToasts + 1, ShadowToast.shownToastCount())
        for ((key, source) in sources) {
            assertEquals("$key's source record is kept", source.first, sourceIndex.getDownload(key)?.request)
            assertArrayEquals(source.second, keyedBytes(phone.cache, key))
            assertNull(targetIndex.getDownload(key))
        }
        for (request in added) assertArrayEquals(sources.getValue(request.id).second, keyedBytes(card.cache, request.id))
        assertTrue("The deferred copy wrote nothing on the target", card.cache.getCachedSpans(left).isEmpty())
        assertEquals(0, store.moves.available())
        assertNull(store.moves.find(card, left))
        assertTrue(added.all { store.moves.find(card, it.id)?.request == it })

        // Those two finish (their target rows recorded, originals gone); their receipts end. A new move
        // then takes the one left behind, with nothing deferred.
        for (request in added) {
            targetIndex.putDownload(Download(request, Download.STATE_COMPLETED, 0, 0, bytes.size.toLong(),
                Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
            sourceIndex.removeDownload(request.id)
            store.moves.finish(requireNotNull(store.moves.find(card, request.id)))
        }
        assertEquals(2, store.moves.available())
        val toasts = ShadowToast.shownToastCount()
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(sources.getValue(left).first,
            addRequest(startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }))
        assertArrayEquals(sources.getValue(left).second, keyedBytes(card.cache, left))
        assertEquals("Nothing deferred, kept or failed this time", toasts, ShadowToast.shownToastCount())
    }

    @Test fun keptAndFailedRowsTakeNoPlaceAndNothingPastTheBudgetIsCopied() {
        withReceiptCapacity(2)
        val valid = listOf("saved/v1", "saved/v2", "saved/v3").onEach { completeKeyed(it, start = 10) }
        // Earlier start times force kept/failed rows before valid ones in the actual index's order.
        // Shares its key with another row: kept, never copied.
        val shared = completeKeyed("saved/shared").second
        val alias = DownloadRequest.Builder("unknown-alias", Uri.parse("http://192.168.1.20:7814/api1/fileopus/9"))
            .setCustomCacheKey("saved/shared").build()
        sourceIndex.putDownload(Download(alias, Download.STATE_COMPLETED, 1, 1, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        // A finished row with no bytes: its copy fails before writing.
        val empty = DownloadRequest.Builder("saved/empty", Uri.parse("http://192.168.1.20:7814/api1/fileopus/8"))
            .setCustomCacheKey("saved/empty").build()
        sourceIndex.putDownload(Download(empty, Download.STATE_COMPLETED, 1, 1, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        val added = startedCommands().filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }.map { addRequest(it).id }
        // Whatever the scan order, only successful copies count: two of the three valid ones, never more.
        assertEquals(2, added.size)
        assertTrue(valid.containsAll(added))
        val written = (valid + listOf("saved/shared", "saved/empty")).filter { card.cache.getCachedSpans(it).isNotEmpty() }
        assertEquals(added.toSet(), written.toSet())
        assertTrue(deferredNotice.matches(ShadowToast.getTextOfLatestToast().orEmpty()))
        for (key in valid + listOf("saved/shared", "unknown-alias", "saved/empty")) assertNotNull(key, sourceIndex.getDownload(key))
        assertArrayEquals(shared, keyedBytes(phone.cache, "saved/shared"))
    }

    @Test fun aCopyRevokedBeforeHandOverFreesNoExtraCopyAndIsNotAdded() {
        withReceiptCapacity(2)
        listOf("saved/a", "saved/b", "saved/c").forEach { completeKeyed(it) }
        OfflineStore.move(app, toCard = true)
        awaitMover()
        val copied = listOf("saved/a", "saved/b", "saved/c").filter { card.cache.getCachedSpans(it).isNotEmpty() }
        assertEquals(2, copied.size)
        // Removed while the move still holds its exclusion: refused, and its hand-over revoked (#234).
        assertEquals(OfflineStore.SavedRemoval.Busy, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Phone, copied[0], copied[0]))))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(copied[1]), startedCommands().filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
            .map { addRequest(it).id })
        assertEquals(1, OfflineStore.get(app).moves.available())
        listOf("saved/a", "saved/b", "saved/c").forEach { assertNotNull(sourceIndex.getDownload(it)) }
    }

    @Test fun noFreeReceiptMeansNoCopyAndNoCommand() {
        val store = withReceiptCapacity(0)
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().isEmpty())
        assertTrue(card.cache.keys.isEmpty())
        assertEquals(request, sourceIndex.getDownload(id)?.request)
        assertEquals("1 more copy stays where it is. Move again once this move finishes.", ShadowToast.getTextOfLatestToast())
        assertFalse(store.moves.hasPending)
    }

    @Test fun anOversizedSingleMoveCommandIsKeptWithoutTargetOutputAndOtherCopiesStillMove() {
        val (small, payload) = completeKeyed("saved/oversized")
        val oversized = DownloadRequest.Builder(small.id, small.uri).setCustomCacheKey(small.id)
            .setData(ByteArray(MOVE_COMMAND_BYTES.toInt()) { 9 }).build()
        sourceIndex.putDownload(Download(oversized, Download.STATE_COMPLETED, 0, 0, payload.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        val valid = completeKeyed("saved/valid")
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(valid.first), startedCommands().filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
            .map(::addRequest))
        assertEquals(oversized, sourceIndex.getDownload(oversized.id)?.request)
        assertArrayEquals(payload, keyedBytes(phone.cache, oversized.id))
        assertTrue(card.cache.getCachedSpans(oversized.id).isEmpty())
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("too much stored metadata"))
        assertNotNull(sourceIndex.getDownload(valid.first.id))
        assertArrayEquals(valid.second, keyedBytes(card.cache, valid.first.id))
    }

    @Test fun largeExactRequestsStopBeforeTheBatchMetadataLimitWithoutWritingTheRemainder() {
        val sources = (0 until 9).associate { n ->
            val key = "saved/large-$n"
            val (small, payload) = completeKeyed(key)
            val original = DownloadRequest.Builder(key, small.uri).setCustomCacheKey(key).setData(large).build()
            sourceIndex.putDownload(Download(original, Download.STATE_COMPLETED, n.toLong(), n.toLong(),
                payload.size.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
            key to (original to payload)
        }
        OfflineStore.move(app, toCard = true)
        awaitMover(); shadowOf(Looper.getMainLooper()).idle()
        val commands = startedCommands().filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
        val added = commands.map(::addRequest)
        // Measure the actual captured, receipt-tagged service Intent, not just its request. This is
        // serialization evidence on API34, not successful Binder/service delivery on a phone.
        for (command in commands) {
            val parcel = Parcel.obtain()
            val commandBytes = try { command.writeToParcel(parcel, 0); parcel.dataSize().toLong() }
                finally { parcel.recycle() }
            val raw = Parcel.obtain()
            val requestBytes = try { addRequest(command).writeToParcel(raw, 0); raw.dataSize().toLong() }
                finally { raw.recycle() }
            assertTrue("The fixed envelope fits its allowance", commandBytes - requestBytes <= 4096L)
            assertTrue("Whole tagged command within admission cap", commandBytes <= MOVE_COMMAND_BYTES)
        }
        assertTrue("The byte limit, not the 128-receipt limit, stops this batch", added.size in 1..8)
        assertTrue(added.sumOf(::moveRequestBytes) <= MOVE_METADATA_BYTES)
        val left = sources.keys - added.map { it.id }.toSet()
        assertTrue(left.isNotEmpty())
        assertTrue(deferredNotice.matches(ShadowToast.getTextOfLatestToast().toString()))
        for ((key, source) in sources) {
            assertEquals("Original exact request retained", source.first, sourceIndex.getDownload(key)?.request)
            assertArrayEquals(source.second, keyedBytes(phone.cache, key))
            if (key in left) assertTrue("No deferred target output", card.cache.getCachedSpans(key).isEmpty())
            else {
                assertEquals(source.first, added.single { it.id == key })
                assertArrayEquals(source.second, keyedBytes(card.cache, key))
            }
        }
    }

    /** Replaces the fixture's store with one whose move receipts hold at most [capacity]. */
    private fun withReceiptCapacity(capacity: Int): OfflineStore.Store {
        val fixture = OfflineStore.Store(phone, DownloadArt(folders.newFolder("art-$capacity")),
            PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {}, app.getSharedPreferences("move-fixture", Context.MODE_PRIVATE),
            database, {}, {}, moves = DownloadMoveReceipts(capacity))
        fixture.card = card
        storeField.set(null, fixture)
        return fixture
    }

    /** A finished, sole-owned source copy under its own key, with its own bytes: its request and bytes. */
    private fun completeKeyed(key: String, start: Long = 1): Pair<DownloadRequest, ByteArray> {
        val payload = ByteArray(bytes.size) { (it + key.hashCode()).toByte() }
        val made = DownloadRequest.Builder(key, Uri.parse("http://192.168.1.20:7814/api1/fileopus/${key.length}"))
            .setCustomCacheKey(key).setData(key.toByteArray()).build()
        val hole = requireNotNull(phone.cache.startReadWrite(key, 0, payload.size.toLong()))
        try {
            val file = phone.cache.startFile(key, 0, payload.size.toLong())
            file.writeBytes(payload)
            phone.cache.commitFile(file, payload.size.toLong())
        } finally { phone.cache.releaseHoleSpan(hole) }
        phone.cache.applyContentMetadataMutations(key,
            ContentMetadataMutations.setContentLength(ContentMetadataMutations(), payload.size.toLong()))
        sourceIndex.putDownload(Download(made, Download.STATE_COMPLETED, start, start, payload.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        return made to payload
    }

    private fun keyedBytes(cache: SimpleCache, key: String): ByteArray = cache.getCachedSpans(key)
        .sortedBy { it.position }.fold(byteArrayOf()) { acc, span -> acc + requireNotNull(span.file).readBytes() }

    private fun largeRequest() = DownloadRequest.Builder(id, request.uri).setCustomCacheKey(id).setData(large).build()

    /** Delegates to [actual]; while [failAtRow] is set, a whole-index read fails at that row. */
    private class CensusFaults(private val actual: DefaultDownloadIndex) : WritableDownloadIndex by actual {
        @Volatile var failAtRow = 0
        /** The real cursors of the reads made to fail. */
        val failing = java.util.concurrent.CopyOnWriteArrayList<DownloadCursor>()
        override fun getDownloads(vararg states: Int): DownloadCursor {
            val cursor = actual.getDownloads(*states)
            val row = failAtRow
            if (states.isNotEmpty() || row == 0) return cursor // The manager's own reads name states.
            failing += cursor
            var reads = 0
            return object : DownloadCursor by cursor {
                override fun getDownload(): Download {
                    if (++reads == row) throw IllegalStateException("Injected index read failure")
                    return cursor.download
                }
            }
        }
    }

    private fun shelf(name: String, index: WritableDownloadIndex,
        service: Class<out DownloadService>, present: () -> Boolean = { true }): Shelf {
        val cache = SimpleCache(folders.newFolder(name), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        val neverDownload = DownloaderFactory { error("Fixture must not start a downloader/network") }
        return Shelf(cache, DownloadManager(app, index, neverDownload), service, present)
    }

    private fun completeSource(payload: ByteArray, recorded: DownloadRequest = request) {
        seed(phone.cache, 0, payload)
        setLength(phone.cache, payload.size.toLong())
        putCompleted(payload.size.toLong(), recorded)
    }

    private fun putCompleted(length: Long, recorded: DownloadRequest = request) {
        sourceIndex.putDownload(Download(recorded, Download.STATE_COMPLETED, 1, 1, length,
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

    /** Captured-command fixture only: marks its Add admitted; the delivery suite runs real services. */
    private fun admitCopiedAdd() {
        val moves = OfflineStore.get(app).moves
        val receipt = moves.find(card, id) ?: return
        if (receipt.addQueued()) assertTrue(moves.admitAdd(receipt))
    }

    private fun startedCommands(): List<Intent> = buildList {
        val shadow = shadowOf(app)
        while (true) add(shadow.nextStartedService ?: break)
    }

    @Test fun oversizedSaveCountIsRefusedBeforeCensusAndKeepsTheOriginal() {
        val (old, payload) = completeKeyed("saved/original")
        phoneIndex.failAtRow = 1
        val track = TauonTrack(42, "Title", "Artist", "Album", 1000, true, false)
        OfflineStore.add(app, ServerEndpoint.parse("192.168.1.20"), List(DOWNLOAD_COMMAND_COUNT + 1) { track })
        awaitSaver(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().isEmpty())
        assertTrue("No census or encoding preparation for a refused selection", phoneIndex.failing.isEmpty())
        assertFalse(OfflineStore.get(app).savePreparation.get())
        assertEquals(old, sourceIndex.getDownload(old.id)?.request)
        assertArrayEquals(payload, keyedBytes(phone.cache, old.customCacheKey!!))
        assertTrue(ShadowToast.getTextOfLatestToast().orEmpty().contains("Nothing was queued"))
    }

    @Test fun oversizedSingleTagsAndAggregatePreparationAreRefusedWithoutSavingAPrefix() {
        val (old, payload) = completeKeyed("saved/original")
        phoneIndex.failAtRow = 1
        val endpoint = ServerEndpoint.parse("192.168.1.20")
        val huge = TauonTrack(42, "x".repeat(MOVE_COMMAND_BYTES.toInt()), "", "", 1000, true, false)
        OfflineStore.add(app, endpoint, listOf(huge))
        val aggregate = huge.copy(title = "x".repeat(32 * 1024))
        OfflineStore.add(app, endpoint, List(32) { aggregate })
        awaitSaver(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().isEmpty())
        assertTrue(phoneIndex.failing.isEmpty())
        assertEquals(old, sourceIndex.getDownload(old.id)?.request)
        assertArrayEquals(payload, keyedBytes(phone.cache, old.customCacheKey!!))
    }

    @Test fun aCountBoundarySavePreservesEveryRecordAndUsesFreshIndependentNames() {
        val (old, payload) = completeKeyed("saved/original")
        val track = TauonTrack(42, "A\u0000B😀", "Artist", "Album", 1000, true, false)
        OfflineStore.add(app, ServerEndpoint.parse("192.168.1.20"), List(DOWNLOAD_COMMAND_COUNT) { track })
        awaitSaver(); shadowOf(Looper.getMainLooper()).idle()
        val requests = startedCommands().filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }.map(::addRequest)
        assertEquals(DOWNLOAD_COMMAND_COUNT, requests.size)
        assertEquals(requests.size, requests.map { it.id }.toSet().size)
        assertTrue(requests.all { it.id.startsWith(NEW_SAVE_PREFIX) && it.id == it.customCacheKey })
        requests.forEach { assertArrayEquals(encodeSong(track), it.data) }
        assertFalse(OfflineStore.get(app).savePreparation.get())
        assertEquals(old, sourceIndex.getDownload(old.id)?.request)
        assertArrayEquals(payload, keyedBytes(phone.cache, old.customCacheKey!!))
    }

    @Test fun repeatedTapsCannotQueueAnotherPreparationAndRetryWorksAfterTheFirstFinishes() {
        val saver = saverField.get(null) as ExecutorService
        val running = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        saver.execute { running.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        val track = TauonTrack(42, "Title", "Artist", "Album", 1000, true, false)
        val endpoint = ServerEndpoint.parse("192.168.1.20")
        try {
            OfflineStore.add(app, endpoint, listOf(track))
            assertTrue(OfflineStore.get(app).savePreparation.get())
            OfflineStore.add(app, endpoint, listOf(track.copy(id = 43)))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(startedCommands().isEmpty())
            assertTrue(ShadowToast.getTextOfLatestToast().orEmpty().contains("wasn't queued"))
        } finally { release.countDown() }
        awaitSaver(); shadowOf(Looper.getMainLooper()).idle()
        val first = startedCommands().filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }.map(::addRequest)
        assertEquals(1, first.size)
        assertArrayEquals(encodeSong(track), first.single().data)
        assertFalse(OfflineStore.get(app).savePreparation.get())
        OfflineStore.add(app, endpoint, listOf(track.copy(id = 43)))
        awaitSaver(); shadowOf(Looper.getMainLooper()).idle()
        val retried = startedCommands().filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }.map(::addRequest)
        assertEquals(1, retried.size)
        assertArrayEquals(encodeSong(track.copy(id = 43)), retried.single().data)
    }

    @Test fun failedPreparationReleasesTheSlotAndLeavesOriginalsForAValidRetry() {
        val (old, payload) = completeKeyed("saved/original")
        phoneIndex.failAtRow = 1
        val track = TauonTrack(42, "Title", "Artist", "Album", 1000, true, false)
        val endpoint = ServerEndpoint.parse("192.168.1.20")
        OfflineStore.add(app, endpoint, listOf(track))
        awaitSaver(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().isEmpty())
        assertFalse(OfflineStore.get(app).savePreparation.get())
        assertEquals(old, sourceIndex.getDownload(old.id)?.request)
        assertArrayEquals(payload, keyedBytes(phone.cache, old.customCacheKey!!))
        phoneIndex.failAtRow = 0
        OfflineStore.add(app, endpoint, listOf(track))
        awaitSaver(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, startedCommands().count { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertFalse(OfflineStore.get(app).savePreparation.get())
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
