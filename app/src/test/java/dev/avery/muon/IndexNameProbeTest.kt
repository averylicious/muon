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
import java.io.IOException

/** Full native index scans and production ownership equivalence, never a displayed-page census. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class IndexNameProbeTest {
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var index: DefaultDownloadIndex
    @Before fun setup() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        index = DefaultDownloadIndex(database, "single_name_probe")
    }
    @After fun close() { database.close() }

    @Test fun allStatesAndHiddenLargeAliasesMatchCompleteCensus() {
        val rows = listOf(row("saved/a"), row("hidden/" + "x".repeat(1500), "saved/a", Download.STATE_FAILED),
            row("saved/b"), row("removing", "saved/b", Download.STATE_REMOVING),
            row("saved/c"), row("stopped", "saved/c", Download.STATE_STOPPED),
            row("saved/sole"), row("other", "different-key"), row(PLAYED_PREFIX + "legacy"),
            row("http://127.0.0.1:7814/api1/file/9", null))
        rows.forEach(index::putDownload)
        val census = IndexCensus.of(rows)
        for (name in rows.flatMap { listOf(it.request.id, keyOf(it)) } + "missing") {
            val probe = IndexNameProbe.read(index, name)
            assertEquals(name, census.soleOwner(name), probe.soleOwner)
            val expected = census.row(name)
            assertEquals(expected?.id, probe.row?.id)
            assertEquals(expected?.key, probe.row?.key)
            assertEquals(expected?.state, probe.row?.state)
        }
        rows.forEach { assertEquals(it.request, index.getDownload(it.request.id)?.request) }
    }

    @Test fun unrelatedRowsAreAllVisitedButOnlyTheCandidateSurvives() {
        val kept = row("saved/kept")
        index.putDownload(kept)
        repeat(513) { index.putDownload(row("unrelated/$it", data = ByteArray(2048) { 7 })) }
        val cursor = index.getDownloads()
        var reads = 0
        val observed = object : DownloadIndex {
            override fun getDownload(id: String): Download? = error("No extra raw row lookup")
            override fun getDownloads(vararg states: Int): DownloadCursor {
                assertTrue("Every state contributes", states.isEmpty())
                return object : DownloadCursor by cursor {
                    override fun getDownload(): Download { reads++; return cursor.download }
                }
            }
        }
        val probe = IndexNameProbe.read(observed, kept.request.id)
        assertTrue(probe.soleOwner)
        assertEquals(514, reads)
        assertTrue(cursor.isClosed)
        assertEquals(kept.request.id, probe.row?.id)
        // The result holds only one compact row and primitive counters, no raw Download/List/Map fields.
        assertTrue(IndexNameProbe::class.java.declaredFields.filterNot {
            java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.all { it.type == IndexRow::class.java || it.type == Int::class.javaPrimitiveType })
        assertEquals(kept.request, index.getDownload(kept.request.id)?.request)
    }

    @Test fun duplicateIdsAndUtf16NamesRemainExact() {
        val a = row("saved/\uD800"); val b = row("saved/\uD801")
        val repeated = row("saved/repeated")
        val rows = listOf(a, b, repeated, repeated, row("saved/nul\u0000end"), row("saved/nul"))
        for (name in rows.map { it.request.id }) {
            val probe = IndexNameProbe.readFrom(name) { emit -> rows.forEach { emit(IndexRow.of(it)) } }
            assertEquals(name, IndexCensus.of(rows).soleOwner(name), probe.soleOwner)
        }
        assertNull(IndexNameProbe.readFrom(repeated.request.id) { emit ->
            rows.forEach { emit(IndexRow.of(it)) }
        }.row)
    }

    @Test fun aLaterReadFailureCannotPublishPartialOwnershipAndClosesNativeCursor() {
        val kept = row("saved/kept")
        index.putDownload(kept); index.putDownload(row("alias", kept.request.id)); index.putDownload(row("last"))
        val cursor = index.getDownloads()
        var reads = 0
        val faulty = object : DownloadIndex {
            override fun getDownload(id: String): Download? = index.getDownload(id)
            override fun getDownloads(vararg states: Int): DownloadCursor = object : DownloadCursor by cursor {
                override fun getDownload(): Download {
                    if (++reads == 3) throw IOException("Later row unreadable")
                    return cursor.download
                }
            }
        }
        assertThrows(IOException::class.java) { IndexNameProbe.read(faulty, kept.request.id) }
        assertEquals(3, reads)
        assertTrue(cursor.isClosed)
        assertEquals(kept.request, index.getDownload(kept.request.id)?.request)
        assertFalse(IndexNameProbe.read(index, kept.request.id).soleOwner)
    }

    @Test fun closeFailureAlsoRefusesTheProbe() {
        val kept = row("saved/kept"); index.putDownload(kept)
        val cursor = index.getDownloads()
        val faulty = object : DownloadIndex {
            override fun getDownload(id: String): Download? = index.getDownload(id)
            override fun getDownloads(vararg states: Int): DownloadCursor = object : DownloadCursor by cursor {
                override fun close() { cursor.close(); throw IOException("Close failed") }
            }
        }
        assertThrows(IOException::class.java) { IndexNameProbe.read(faulty, kept.request.id) }
        assertTrue(cursor.isClosed)
        assertEquals(kept.request, index.getDownload(kept.request.id)?.request)
    }

    private fun row(id: String, key: String? = id, state: Int = Download.STATE_COMPLETED,
        data: ByteArray = byteArrayOf(1, 0, 2)): Download {
        val request = DownloadRequest.Builder(id, Uri.parse("http://127.0.0.1:7814/api1/file/9"))
            .setCustomCacheKey(key).setData(data).build()
        return Download(request, state, 10, 20, 4,
            if (state == Download.STATE_STOPPED) RETAINED_STOP_REASON else Download.STOP_REASON_NONE,
            if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE)
    }
}
