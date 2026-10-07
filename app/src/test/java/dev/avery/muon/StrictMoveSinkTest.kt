package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
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

/**
 * #230: the move's strict cache sink over a real SimpleCache and native SQLite. Failures are injected only
 * in the file output (around the real FileOutputStream) or, once, in the cache's commit; every other step is
 * real. One case runs the actual CacheDataSource + CacheWriter, where Media3 closes quietly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StrictMoveSinkTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var folder: File
    private val key = "http://192.168.1.20:7814/7"
    private val payload = ByteArray(200_000) { (it % 251).toByte() }
    private val opened = ArrayList<Faulty>()

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        folder = folders.newFolder("cache")
        cache = SimpleCache(folder, NoOpCacheEvictor(), database)
        cache.checkInitialization()
    }

    @After fun tearDown() { try { cache.release() } finally { database.close() } }

    @Test fun aHealthyFileIsFlushedSyncedClosedAndCommittedExactly() {
        val sink = StrictMoveSink(cache, outputs(null))
        writeThrough(sink)
        assertTrue(sink.clean)
        assertEquals(1, sink.committed)
        with(opened.single()) { assertTrue(flushed && synced && closed) }
        assertArrayEquals(payload, requireNotNull(cache.getCachedSpans(key).single().file).readBytes())
    }

    @Test fun aFailedFlushSyncOrCloseCommitsNothingAndLeavesNoReservedFile() {
        for (fault in listOf(Fault.Flush, Fault.Sync, Fault.Close)) {
            val before = spanFiles()
            val sink = StrictMoveSink(cache, outputs(fault))
            val thrown = assertThrows(IOException::class.java) { writeThrough(sink) }
            assertEquals(fault.name, sink.failure, thrown)
            assertFalse(fault.name, sink.clean)
            assertEquals(0, sink.committed)
            assertTrue("$fault: the file is closed even after a failure", opened.last().closed)
            assertTrue("$fault: nothing entered the cache", cache.getCachedSpans(key).isEmpty())
            assertEquals("$fault: its never-committed file is gone, nothing else", before, spanFiles())
        }
    }

    @Test fun aFailedCommitIsKeptAsEvidenceOfFailureAndItsFileIsNotDeleted() {
        val refusing = object : Cache by cache {
            override fun commitFile(file: File, length: Long) = throw Cache.CacheException("Injected commit refusal")
        }
        val sink = StrictMoveSink(refusing, outputs(null))
        assertThrows(Cache.CacheException::class.java) { writeThrough(sink) }
        assertTrue(sink.failure is Cache.CacheException)
        assertFalse(sink.clean)
        // Left where it is: a cache whose commit failed may already partly know the file.
        assertTrue(spanFiles().isNotEmpty())
    }

    @Test fun aFailedWriteIsNeverCommittedEvenWhenTheCallerClosesQuietly() {
        val sink = StrictMoveSink(cache, outputs(Fault.Write))
        val hole = requireNotNull(cache.startReadWrite(key, 0, payload.size.toLong()))
        try {
            sink.open(DataSpec.Builder().setUri(Uri.EMPTY).setKey(key).setLength(payload.size.toLong()).build())
            assertThrows(IOException::class.java) { sink.write(payload, 0, payload.size) }
            runCatching { sink.close() } // As Media3 does after a failure.
        } finally { cache.releaseHoleSpan(hole) }
        assertNotNull(sink.failure)
        assertTrue(opened.single().closed)
        assertTrue(cache.getCachedSpans(key).isEmpty())
    }

    @Test fun aFailedOpenLeavesNothingOpenOrReserved() {
        val sink = StrictMoveSink(cache) { throw IOException("Injected open failure") }
        val hole = requireNotNull(cache.startReadWrite(key, 0, payload.size.toLong()))
        try {
            assertThrows(IOException::class.java) {
                sink.open(DataSpec.Builder().setUri(Uri.EMPTY).setKey(key).setLength(payload.size.toLong()).build())
            }
            sink.close() // Nothing open: nothing to do.
        } finally { cache.releaseHoleSpan(hole) }
        assertNotNull(sink.failure)
        assertFalse(sink.clean)
        assertTrue(spanFiles().isEmpty())
    }

    @Test fun throughMedia3sQuietCloseTheSinkStillKeepsTheHiddenCloseFailure() {
        // The actual CacheDataSource + CacheWriter: the upstream fails after some bytes, so CacheWriter closes
        // quietly and rethrows the read failure. The output's close failure is hidden from that caller, but
        // not from the sink, which commits nothing.
        val sink = StrictMoveSink(cache, outputs(Fault.Close))
        val source = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory { FailsAfter(payload, 64 * 1024) }
            .setCacheWriteDataSinkFactory { sink }
            .createDataSourceForDownloading()
        val readFailure = assertThrows(IOException::class.java) {
            CacheWriter(source, DataSpec.Builder().setUri(Uri.parse("fixture://audio")).setKey(key).build(), null, null).cache()
        }
        assertEquals("Injected read failure", readFailure.message)
        assertEquals("Injected close failure", sink.failure?.message)
        assertFalse(sink.clean)
        assertTrue(opened.single().closed)
        assertTrue("No partial file was committed", cache.getCachedSpans(key).isEmpty())
    }

    // ---- fixture ----

    private enum class Fault { Write, Flush, Sync, Close }

    /** Wraps the real file output; a step fails only where the test asks. */
    private class Faulty(private val real: MoveFileOutput, private val fault: Fault?) : MoveFileOutput {
        var flushed = false; var synced = false; var closed = false
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (fault == Fault.Write) throw IOException("Injected write failure")
            real.write(buffer, offset, length)
        }
        override fun flush() { if (fault == Fault.Flush) throw IOException("Injected flush failure"); real.flush(); flushed = true }
        override fun sync() { if (fault == Fault.Sync) throw IOException("Injected sync failure"); real.sync(); synced = true }
        override fun close() {
            real.close()
            closed = true // The real descriptor is released before the injected failure.
            if (fault == Fault.Close) throw IOException("Injected close failure")
        }
    }

    private fun outputs(fault: Fault?) = MoveFileOutputs { file -> Faulty(MoveFileOutputs.Real.open(file), fault).also(opened::add) }

    /** Open, write and close one file, holding the cache's hole lock as CacheDataSource does. */
    private fun writeThrough(sink: StrictMoveSink) {
        val hole = requireNotNull(cache.startReadWrite(key, 0, payload.size.toLong()))
        try {
            sink.open(DataSpec.Builder().setUri(Uri.EMPTY).setKey(key).setLength(payload.size.toLong()).build())
            sink.write(payload, 0, payload.size)
            sink.close()
        } finally { cache.releaseHoleSpan(hole) }
    }

    /** Every span file on disk, whatever the index says. */
    private fun spanFiles(): Set<String> =
        folder.walk().filter { it.isFile && it.name.endsWith(".exo") }.map { it.name }.toSet()

    /** An upstream that serves [limit] bytes of [bytes], then fails. */
    private class FailsAfter(private val bytes: ByteArray, private val limit: Int) : DataSource {
        private var position = 0
        private var uri: Uri? = null
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long { uri = dataSpec.uri; position = dataSpec.position.toInt(); return bytes.size.toLong() - position }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= limit) throw IOException("Injected read failure")
            val count = minOf(length, limit - position)
            System.arraycopy(bytes, position, buffer, offset, count)
            position += count
            return count
        }
        override fun getUri(): Uri? = uri
        override fun close() { uri = null }
    }
}
