package dev.avery.muon

import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloaderFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedStartupTimingTest {
    @get:Rule val folders = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    @Test fun disabledBuildNeverUsesClockSinkOrRetainsAnOperation() {
        val timing = SavedStartupTiming(false, { error("Disabled clock") }, { error("Disabled sink") })
        repeat(100) { assertNull(timing.begin(SavedStartupTiming.Phase.PREPARATION)); timing.end(null) }
        val flags = app.applicationInfo.flags
        try {
            app.applicationInfo.flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            assertSame(SavedStartupTiming.DISABLED, SavedStartupTiming.forContext(app))
            app.applicationInfo.flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
            assertTrue(SavedStartupTiming.forContext(app).enabled)
        } finally { app.applicationInfo.flags = flags }
    }
    @Test fun oneConstantEventPerOperationAndSinkFailureCannotChangePlayback() {
        var now = 10L; val events = ArrayList<SavedStartupTiming.Event>()
        val timing = SavedStartupTiming(true, { now }, events::add)
        val token = timing.begin(SavedStartupTiming.Phase.ADMISSION)
        now = 31; timing.end(token, SavedStartupTiming.Outcome.REFUSED)
        timing.end(token); assertEquals(1, events.size)
        assertEquals(SavedStartupTiming.Event(SavedStartupTiming.Phase.ADMISSION,
            SavedStartupTiming.Outcome.REFUSED, 21, 31), events.single())
        val throwing = SavedStartupTiming(true, { 0 }) { error("Sink failed") }
        throwing.end(throwing.begin(SavedStartupTiming.Phase.READY))
        assertTrue(SavedStartupTiming.Event::class.java.declaredFields.none { it.type == String::class.java })
    }
    @Test fun realSavedCacheOpenAndFirstReadHaveBoundedTimingsWithoutChangingBytes() {
        val db = StandaloneDatabaseProvider(app)
        val cache = SimpleCache(folders.newFolder(), NoOpCacheEvictor(), db)
        cache.checkInitialization()
        val index = DefaultDownloadIndex(db, "timing_fixture")
        val key = "saved/private-song"; val bytes = byteArrayOf(1, 2, 3, 4)
        val hole = requireNotNull(cache.startReadWrite(key, 0, bytes.size.toLong()))
        try {
            val file = cache.startFile(key, 0, bytes.size.toLong()); file.writeBytes(bytes); cache.commitFile(file, bytes.size.toLong())
            cache.applyContentMetadataMutations(key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), bytes.size.toLong()))
        } finally { cache.releaseHoleSpan(hole) }
        val request = DownloadRequest.Builder(key, Uri.parse("http://private-host/api1/file/7")).setCustomCacheKey(key).build()
        index.putDownload(Download(request, Download.STATE_COMPLETED, 1, 2, bytes.size.toLong(), 0, 0))
        val manager = DownloadManager(app, index, DownloaderFactory { error("No downloading") })
        var available = true
        val shelf = Shelf(cache, manager, MuonDownloadService::class.java) { available }
        val events = ArrayList<SavedStartupTiming.Event>(); var now = 0L
        val timing = SavedStartupTiming(true, { ++now }, events::add)
        val ref = requireNotNull(SavedRef.download(SavedShelf.Phone, key, key))
        val source = OfflineDataSource(timing) { routeOfflineRequest(it, shelf, null) }
        try {
            assertEquals(4L, source.open(DataSpec.Builder().setUri(ref.handle).build()))
            assertEquals(0, source.read(ByteArray(1), 0, 0)); assertEquals(2, events.size)
            val buffer = ByteArray(4); assertEquals(2, source.read(buffer, 0, 2))
            assertEquals(2, source.read(buffer, 2, 2)); assertArrayEquals(bytes, buffer)
            assertEquals(listOf(SavedStartupTiming.Phase.ROUTE_PHONE, SavedStartupTiming.Phase.OPEN_PHONE,
                SavedStartupTiming.Phase.FIRST_READ_PHONE), events.map { it.phase })
            assertTrue(events.all { it.outcome == SavedStartupTiming.Outcome.OK && it.milliseconds >= 0 })
            source.close(); events.clear()
            source.open(DataSpec.Builder().setUri(ref.handle).build()); available = false
            assertThrows(java.io.IOException::class.java) { source.read(ByteArray(1), 0, 1) }
            assertEquals(SavedStartupTiming.Outcome.FAILED, events.last().outcome)
            assertThrows(java.io.IOException::class.java) { source.read(ByteArray(1), 0, 1) }
            assertEquals(3, events.size) // Sticky loss and repeated buffers add no history/log flood.
            assertNotNull(index.getDownload(key)); assertTrue(cache.isCached(key, 0, bytes.size.toLong()))
        } finally { source.close(); manager.release(); cache.release(); db.close() }
    }
}
