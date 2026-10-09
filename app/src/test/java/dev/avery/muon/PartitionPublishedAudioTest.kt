@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloaderFactory
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Actual public native cache/sidecar/journal, pool and production readers. All bytes are disposable.
 * Publication uses the existing copy+comparison helper, not fabricated Ready rows. No production
 * opt-in/migration owner, live server, phone, measured heap or physical-card behavior is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionPublishedAudioTest {
    @get:Rule val folders = TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val natives = mutableListOf<SimpleCache>()
    private val fixtures = mutableListOf<Fixture>()
    private val bytes = byteArrayOf(2, 4, 6)
    private inner class Fixture(capacity: Int) {
        val root = folders.newFolder()
        val catalog = CachePartitionCatalog(root)
        val journal = CacheMigrationJournal(root)
        val saves = PartitionSaveJournal(root, create = true)
        val budget = PartitionNativeBudget(capacity)
        var volume: String? = "card-one"
        val owner = PartitionNativeOwner(catalog, journal, "card-one", { volume }, budget = budget, saves = saves)
        val audio = PartitionPublishedAudio(owner, capacity)
        val legacy = SimpleCache(folders.newFolder(), NoOpCacheEvictor(), database).also {
            natives += it; it.checkInitialization()
        }
    }
    private fun fixture(capacity: Int = 2) = Fixture(capacity).also(fixtures::add)
    @After fun close() {
        try { fixtures.asReversed().forEach { it.audio.close(); it.saves.close(); it.journal.close(); it.catalog.close() } }
        finally { try { natives.asReversed().forEach { it.release() } } finally { database.close() } }
    }
    private fun publish(f: Fixture, key: String, payload: ByteArray = bytes): MigrationRecord {
        seed(f.legacy, key, payload)
        return CacheMigrationPublication(f.catalog, f.journal, f.owner.migrationTarget(key)).migrate(f.legacy, key, {})
    }
    private fun seed(cache: Cache, key: String, payload: ByteArray) {
        val hole = requireNotNull(cache.startReadWrite(key, 0, payload.size.toLong()))
        try {
            val file = cache.startFile(key, 0, payload.size.toLong())
            file.writeBytes(payload); cache.commitFile(file, payload.size.toLong())
        } finally { cache.releaseHoleSpan(hole) }
        cache.applyContentMetadataMutations(key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), payload.size.toLong()))
    }
    private fun spec(key: String) = DataSpec.Builder().setUri("muon-saved:fixture").setKey(key).build()
    private fun original(f: Fixture, key: String) = requireNotNull(f.legacy.getCachedSpans(key).single().file)

    @Test fun pagedPublishedEnumerationOpensNoNativeCacheAndInspectionUsesBoundedResidency() {
        val f = fixture()
        val names = (0 until 19).map { "saved/%02d".format(it) }
        names.forEach { publish(f, it) }
        f.catalog.reserve("saved/reserved")
        f.journal.begin(f.catalog.reserve("saved/copying"), f.legacy.uid, null)
        val listed = mutableListOf<String>()
        f.audio.forEachKey { listed += it; true }
        assertEquals(names, listed)
        assertEquals(0, f.budget.resident)
        assertEquals(0, f.audio.resident)
        for (key in names) {
            assertTrue(f.audio.contains(key))
            assertEquals(SavedCoverage.Full, f.audio.inspect(key).coverage)
            assertTrue(f.audio.resident <= 2); assertTrue(f.budget.resident <= 2)
            assertEquals(0, f.audio.active)
            assertArrayEquals(bytes, original(f, key).readBytes())
        }
        val one = mutableListOf<String>()
        f.audio.forEachKey { one += it; false }
        assertEquals(listOf(names.first()), one)
        f.audio.close(); assertEquals(0, f.budget.resident)
        assertThrows(IOException::class.java) { f.audio.contains(names.first()) }
        assertThrows(IOException::class.java) { f.audio.forEachKey { true } }
    }

    @Test fun unreadyMigrationAndCleanlyClosedNewSaveDoNotGainPublishedReadAuthority() {
        val f = fixture()
        val copying = f.journal.begin(f.catalog.reserve("saved/copying"), f.legacy.uid, null)
        val verified = f.journal.begin(f.catalog.reserve("saved/verified"), f.legacy.uid, null)
        f.journal.verified(verified, 123, MigrationCopyEvidence(3, 1))
        val uncertain = f.journal.begin(f.catalog.reserve("saved/uncertain"), f.legacy.uid, null)
        f.journal.uncertain(uncertain)
        val ticket = f.saves.begin(f.catalog.reserve("saved/new"))
        f.owner.openNewSave(ticket).let { cache -> seed(cache, ticket.allocation.key, bytes); cache.release() }
        assertEquals(PartitionSavePhase.Closed, f.saves.find("saved/new")?.phase)
        for (key in listOf(copying.allocation.key, verified.allocation.key, uncertain.allocation.key, "saved/new", "absent")) {
            assertFalse(f.audio.contains(key))
            assertThrows(IOException::class.java) { f.audio.inspect(key) }
            val reader = f.audio.source.createDataSource()
            assertThrows(IOException::class.java) { reader.open(spec(key)) }; reader.close()
        }
        f.audio.forEachKey { error("No Ready route exists") }
        assertEquals(0, f.audio.resident); assertEquals(0, f.budget.resident)
        assertEquals(PartitionSavePhase.Closed, f.saves.find("saved/new")?.phase)
    }

    @Test fun laterReservationOrNewSaveClaimCannotInvalidateOrReplaceTheVerifiedPublishedCopy() {
        val f = fixture(1); val key = "saved/collision"; val ready = publish(f, key)
        val replacement = f.catalog.reserveFresh(key)
        val ticket = f.saves.begin(replacement)
        assertTrue(f.audio.contains(key))
        assertEquals(SavedCoverage.Full, f.audio.inspect(key).coverage)
        assertThrows(IOException::class.java) { f.owner.openNewSave(ticket) }
        assertFalse(f.catalog.directory(replacement).exists())
        assertEquals(ready, f.journal.ready(key))
        assertArrayEquals(bytes, original(f, key).readBytes())
    }

    @Test fun actualOfflineReaderAndLibraryUsePublishedBytesWithoutChangingLegacyOrRows() {
        val f = fixture(1); val key = "saved/one"; publish(f, key)
        val index = DefaultDownloadIndex(database, "published_boundary")
        val request = DownloadRequest.Builder(key, Uri.parse("http://127.0.0.1:7814/api1/file/1")).setCustomCacheKey(key).build()
        index.putDownload(Download(request, Download.STATE_COMPLETED, 1, 1, 3, 0, Download.FAILURE_REASON_NONE))
        val manager = DownloadManager(RuntimeEnvironment.getApplication(), index, DownloaderFactory { error("No downloads") })
        val shelf = Shelf(f.legacy, manager, MuonDownloadService::class.java, audio = f.audio)
        shelf.stream = DataSource.Factory { error("Saved bytes must never stream") }
        val reader = OfflineDataSource { routeOfflineRequest(it, shelf, null) }
        try {
            val entries = savedInventory(SavedShelf.Phone, index, shelf.audio, null) { false }
            assertEquals(1, entries.size); assertTrue(entries.single().complete)
            assertEquals(1L, countCompleteSavedCopies(SavedShelf.Phone, index, shelf.audio, false))
            val ref = requireNotNull(SavedRef.download(SavedShelf.Phone, key, key))
            assertEquals(3L, reader.open(DataSpec.Builder().setUri(ref.handle).build()))
            val got = ByteArray(3); assertEquals(3, reader.read(got, 0, 3)); assertArrayEquals(bytes, got)
            reader.close(); assertEquals(0, f.audio.active)
            assertEquals(request, index.getDownload(key)?.request)
            assertArrayEquals(bytes, original(f, key).readBytes())
        } finally { reader.close(); manager.release() }
    }

    @Test fun routeChangedInsideFileReadRefusesReturnedBytesAndKeepsItsPinUntilClose() {
        val f = fixture(1); val key = "saved/one"; val ready = publish(f, key)
        val nativeBytes = File(f.catalog.directory(ready.ticket.allocation), "bytes").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".exo") }.associateWith { it.readBytes() }
        val source = f.audio.source.createDataSource()
        source.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(s: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onTransferStart(s: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onTransferEnd(s: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onBytesTransferred(s: DataSource, spec: DataSpec, network: Boolean, count: Int) {
                // Controlled publication loss, not an ordinary new reservation. Ready is immutable
                // through the public journal API; inject corruption using disposable native SQLite.
                SQLiteDatabase.openDatabase(File(f.root, "migration-journal-v1.db").path, null,
                    SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.execSQL("UPDATE migrations SET phase=?", arrayOf(MigrationPhase.Uncertain.ordinal))
                }
            }
        })
        try {
            source.open(spec(key))
            assertThrows(IOException::class.java) { source.read(ByteArray(3), 0, 3) }
            assertEquals(1, f.audio.active)
            assertNull(source.uri)
            assertThrows(IOException::class.java) { source.read(ByteArray(3), 0, 3) }
            assertThrows(IOException::class.java) { f.audio.inspect(key) }
            assertThrows(IOException::class.java) { f.owner.openReady(key) }
        } finally { source.close() }
        assertEquals(0, f.audio.active)
        f.audio.close(); assertEquals(0, f.budget.resident)
        nativeBytes.forEach { (file, payload) -> assertArrayEquals(payload, file.readBytes()) }
        assertArrayEquals(bytes, original(f, key).readBytes())
        assertNull(f.journal.ready(key))
        assertEquals(MigrationPhase.Uncertain, f.journal.find(key)?.phase)
    }

    @Test fun changedReadyUidCannotReuseAnOldIdleNativeInstanceForReading() {
        val f = fixture(1); val key = "saved/identity"; val ready = publish(f, key)
        val before = original(f, key).readBytes()
        val reader = f.audio.source.createDataSource()
        reader.open(spec(key)); reader.close()
        assertEquals(1, f.audio.resident); assertEquals(0, f.audio.active)
        // Public publication makes Ready immutable. Inject a mismatched scalar in disposable SQL
        // to verify that a cached pool hit cannot bypass the owner's native-identity admission.
        SQLiteDatabase.openDatabase(File(f.root, "migration-journal-v1.db").path, null,
            SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE migrations SET target_uid=?", arrayOf(requireNotNull(ready.targetUid) xor 1L))
        }
        assertTrue(f.audio.contains(key)) // Existence is deliberately not native identity verification.
        assertThrows(IOException::class.java) { reader.open(spec(key)) }
        reader.close(); assertEquals(0, f.audio.active)
        assertThrows(IOException::class.java) { f.audio.inspect(key) }
        assertArrayEquals(before, original(f, key).readBytes())
        SQLiteDatabase.openDatabase(File(f.root, "migration-journal-v1.db").path, null,
            SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE migrations SET target_uid=?", arrayOf(requireNotNull(ready.targetUid)))
        }
        assertEquals(3L, reader.open(spec(key)))
        val got=ByteArray(3); assertEquals(3,reader.read(got,0,3)); assertArrayEquals(bytes,got)
        reader.close(); assertEquals(0,f.audio.active)
    }

    @Test fun lostVolumeIsStickyForTheOpenReaderAndReturnedVolumeNeedsFreshOpen() {
        val f = fixture(1); val key = "saved/one"; publish(f, key)
        val reader = f.audio.source.createDataSource()
        reader.open(spec(key)); f.volume = null
        assertThrows(IOException::class.java) { reader.read(ByteArray(1), 0, 1) }
        assertThrows(IOException::class.java) { f.audio.contains(key) }
        f.volume = "card-one"
        assertThrows(IOException::class.java) { reader.read(ByteArray(1), 0, 1) }
        assertEquals(1, f.audio.active)
        reader.close(); reader.open(spec(key))
        val got = ByteArray(3); assertEquals(3, reader.read(got, 0, 3)); assertArrayEquals(bytes, got)
        reader.close(); assertEquals(0, f.audio.active)
        assertArrayEquals(bytes, original(f, key).readBytes())
    }

    @Test fun activePublishedReaderBlocksResidencyReplacementAndStopDoesNotForceCloseIt() {
        val f = fixture(1); publish(f, "saved/one"); publish(f, "saved/two")
        val reader = f.audio.source.createDataSource()
        reader.open(spec("saved/one"))
        assertThrows(PartitionCacheBusy::class.java) { f.audio.inspect("saved/two") }
        assertEquals(1, f.budget.resident); assertEquals(1, f.audio.active)
        f.audio.close()
        assertEquals(3, reader.read(ByteArray(3), 0, 3))
        assertEquals(-1, reader.read(ByteArray(1), 0, 1))
        assertEquals(1, f.budget.resident)
        reader.close(); assertEquals(0, f.audio.resident); assertEquals(0, f.budget.resident)
        assertThrows(IOException::class.java) { f.audio.inspect("saved/two") }
        assertArrayEquals(bytes, original(f, "saved/one").readBytes())
    }
}
