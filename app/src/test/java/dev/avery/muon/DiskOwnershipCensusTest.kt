package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DiskOwnershipCensusTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var db: StandaloneDatabaseProvider
    private lateinit var index: DefaultDownloadIndex
    @Before fun setup() { db = StandaloneDatabaseProvider(app); index = DefaultDownloadIndex(db, "disk_census") }
    @After fun cleanup() { db.close() }
    private fun put(id: String, key: String?, state: Int = Download.STATE_COMPLETED) {
        val request = DownloadRequest.Builder(id, Uri.parse("http://h/api1/file/1"))
            .setCustomCacheKey(key).setData(byteArrayOf(1, 2, 3)).build()
        index.putDownload(Download(request, state, 1, 2, 3, 0, 0))
    }
    private fun rows() = ArrayList<Download>().also { all -> index.getDownloads().use { while(it.moveToNext()) all += it.download } }
    @Test fun matchesCompleteMemoryRulesForHiddenAliasesEveryStateAndExactNames() {
        put("saved/a", "saved/a"); put("hidden", "saved/a", Download.STATE_STOPPED)
        put("saved/b", "saved/b"); put("played:kept", "played:kept")
        put("odd", "another", Download.STATE_FAILED); put("keyless", null)
        put("saved:é😀\u0000", "saved:é😀\u0000")
        val before = rows(); val memory = IndexCensus.of(before)
        DiskOwnershipCensus.read(app, index).use { census ->
            for (id in before.map { it.request.id } + listOf("missing", "another", "http://h/api1/file/1")) {
                assertEquals(id, memory.soleOwner(id), census.soleOwner(id))
                assertEquals(id, memory.names(id), census.names(id))
                assertEquals(memory.onlyNaming(id)?.id, census.onlyNaming(id)?.id)
            }
            val observed = ArrayList<String>()
            census.forEachRow { observed += it.id; true }
            assertEquals(before.map { it.request.id }, observed)
            assertEquals(before.count { it.state == Download.STATE_COMPLETED }, census.countCompleted())
        }
        assertEquals(before.map { it.request }, rows().map { it.request })
    }
    @Test fun earlyIterationStopClosesTheCursorAndNoFullCompletedIdsAreNeeded() {
        repeat(64) { put("saved/$it", "saved/$it") }
        DiskOwnershipCensus.read(app, index).use { census ->
            var visited = 0
            census.forEachRow(completedOnly = true) { visited++; visited < 4 }
            assertEquals(4, visited); assertTrue(census.soleOwner("saved/63")); assertEquals(64, census.countCompleted())
        }
        assertEquals(64, rows().size)
    }
    @Test fun midReadAndCloseFailureNeverPublishesPartialAuthorityAndRemovesScratchOnly() {
        put("saved/a", "saved/a"); put("hidden", "saved/a")
        val before = rows().map { it.request }
        for (atClose in listOf(false, true)) {
            val failing = object : DownloadIndex by index {
                override fun getDownloads(vararg states: Int): DownloadCursor {
                    val cursor = index.getDownloads(*states); var visited = 0
                    return object : DownloadCursor by cursor {
                        override fun moveToNext(): Boolean {
                            if (!atClose && visited++ == 1) throw java.io.IOException("scan failure")
                            return cursor.moveToNext()
                        }
                        override fun close() { cursor.close(); if (atClose) throw java.io.IOException("close failure") }
                    }
                }
            }
            assertThrows(java.io.IOException::class.java) { DiskOwnershipCensus.read(app, failing) }
        }
        assertEquals(before, rows().map { it.request })
        assertTrue(java.io.File(app.noBackupFilesDir, "ownership-scratch").listFiles().orEmpty().isEmpty())
    }
    @Test fun targetEqualityAndSourceHiddenOwnershipStillGateMoves() {
        val target = DefaultDownloadIndex(db, "target_disk")
        put("saved/a", "saved/a"); val original = requireNotNull(index.getDownload("saved/a"))
        target.putDownload(original)
        DiskOwnershipCensus.read(app, index).use { source ->
            DiskOwnershipCensus.read(app, target).use { destination ->
                assertTrue(movable(original, source, destination, target::getDownload))
                val altered = original.request.copyWithId("saved/a").let {
                    DownloadRequest.Builder(it.id, it.uri).setCustomCacheKey(it.customCacheKey).setData(byteArrayOf(9)).build()
                }
                target.putDownload(Download(altered, original.state, 1, 2, 3, 0, 0))
                assertFalse(movable(original, source, destination, target::getDownload))
            }
        }
        put("hidden", "saved/a", Download.STATE_FAILED)
        DiskOwnershipCensus.read(app, index).use { source ->
            DiskOwnershipCensus.read(app, target).use { destination ->
                assertFalse(movable(original, source, destination, target::getDownload))
            }
        }
    }
}
