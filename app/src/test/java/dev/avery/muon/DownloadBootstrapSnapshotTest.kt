@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadRequest
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DownloadBootstrapSnapshotTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var index: DefaultDownloadIndex
    @Before fun setup() {
        val root = folders.newFolder()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = root
        }
        database = StandaloneDatabaseProvider(context)
        index = DefaultDownloadIndex(database,"status_spool")
    }
    @After fun close() { database.close() }

    @Test fun completeRawRecordsBecomeAtMostSixteenCompactStatusesPerPublication() {
        val originals = (0..72).map { n ->
            val request = DownloadRequest.Builder("saved/$n",Uri.parse("https://fixture.invalid/$n"))
                .setData(ByteArray(48 * 1024) { n.toByte() }).build()
            Download(request,Download.STATE_COMPLETED,n.toLong(),n.toLong(),128,
                Download.STOP_REASON_NONE,Download.FAILURE_REASON_NONE).also(index::putDownload)
        }
        DownloadBootstrapSnapshot(context).use { spool ->
            spool.read(index)
            var offset = 0L
            val seen = ArrayList<DownloadStatus>()
            while (true) {
                val batch = spool.page(offset)
                assertTrue(batch.size <= 16)
                if (batch.isEmpty()) break
                seen += batch; offset += batch.size
            }
            assertEquals(originals.map(DownloadStatus::of),seen)
        }
        for (row in originals) assertEquals(row.request,index.getDownload(row.request.id)?.request)
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().none { it.name.startsWith("download-bootstrap-") })
    }

    @Test fun exactLiveChangesBeforeAndAfterCaptureExcludeOnlyThoseSnapshotIds() {
        val ids = listOf("a","a\u0000b","\uD800","\uD801","long/" + "x".repeat(2048))
        val originals = ids.mapIndexed { n,id ->
            Download(DownloadRequest.Builder(id,Uri.parse("https://fixture.invalid/$n")).build(),
                Download.STATE_COMPLETED,n.toLong(),n.toLong(),4,Download.STOP_REASON_NONE,Download.FAILURE_REASON_NONE)
                .also(index::putDownload)
        }
        DownloadBootstrapSnapshot(context).use { spool ->
            spool.changed(ids[1]); spool.read(index); spool.changed(ids[2])
            assertEquals(ids,spool.page(0).map { it.id })
            assertEquals(listOf(ids[0],ids[3],ids[4]),spool.page(0).filter { spool.unchanged(it.id) }.map { it.id })
        }
        for (row in originals) assertEquals(row.request,index.getDownload(row.request.id)?.request)
    }

    @Test fun aCursorFailureIncludingCloseNeverPublishesItsSuccessfulPrefix() {
        val request = DownloadRequest.Builder("saved/kept",Uri.parse("https://fixture.invalid/kept")).setData(byteArrayOf(7)).build()
        index.putDownload(Download(request,Download.STATE_COMPLETED,1,1,4,Download.STOP_REASON_NONE,Download.FAILURE_REASON_NONE))
        var closed = false
        val bad = object : WritableDownloadIndex by index {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                val actual = index.getDownloads(*states)
                return object : DownloadCursor by actual {
                    override fun close() { actual.close(); closed = true; throw IOException("Fixture close failure") }
                }
            }
        }
        DownloadBootstrapSnapshot(context).use { spool ->
            spool.read(bad)
            assertTrue(closed)
            assertThrows(IOException::class.java) { spool.page(0) }
            assertFalse(spool.unchanged(request.id))
        }
        assertEquals(request,index.getDownload(request.id)?.request)
    }
}
