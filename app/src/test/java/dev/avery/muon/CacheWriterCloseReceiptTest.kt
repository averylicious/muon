package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.junit.Assert.*
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

/** Actual cache writer/sink/native SQLite; only the read failure and commit refusal are injected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CacheWriterCloseReceiptTest {
    @get:Rule val folders = TemporaryFolder()

    @Test fun aReadFailureStillCommitsItsPartialBytesWhenSinkCloseSucceeds() = exercise(false)

    @Test fun writerCompletionCanHideAnUnsuccessfulSinkCloseBehindTheReadFailure() = exercise(true)

    private fun exercise(refuseCommit: Boolean) {
        val database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val cache = try { SimpleCache(folders.newFolder(), NoOpCacheEvictor(), database) }
            catch (error: Throwable) { database.close(); throw error }
        var source: CacheDataSource? = null
        try {
            cache.checkInitialization()
            val retainedKey = "retained-disposable-sentinel"
            val retained = byteArrayOf(7, 8, 9)
            val hole = cache.startReadWrite(retainedKey, 0, retained.size.toLong())
            try {
                val file = cache.startFile(retainedKey, 0, retained.size.toLong())
                file.writeBytes(retained)
                cache.commitFile(file, retained.size.toLong())
            } finally { cache.releaseHoleSpan(hole) }

            val readFailure = IOException("Injected read failure after the first byte")
            val commitFailure = Cache.CacheException("Injected commit refusal")
            var commitRefused = false
            val view = object : Cache by cache {
                override fun commitFile(file: File, length: Long) {
                    if (refuseCommit) {
                        commitRefused = true
                        throw commitFailure
                    }
                    cache.commitFile(file, length)
                }
            }
            val upstream = OneByteThenFailure(readFailure)
            var sinkCloseReturned = false
            var sinkCloseError: IOException? = null
            source = CacheDataSource.Factory().setCache(view)
                .setUpstreamDataSourceFactory { upstream }
                .setCacheWriteDataSinkFactory {
                    val actual = CacheDataSink.Factory().setCache(view).createDataSink()
                    object : DataSink by actual {
                        override fun close() {
                            try {
                                actual.close()
                                sinkCloseReturned = true
                            } catch (error: IOException) {
                                sinkCloseError = error
                                throw error
                            }
                        }
                    }
                }.createDataSource()
            val key = "partial-disposable-resource"
            val spec = DataSpec.Builder().setUri("memory:///partial").setKey(key).setLength(2).build()
            val writer = CacheWriter(source, spec, ByteArray(1), null)
            val reported = assertThrows(IOException::class.java) { writer.cache() }

            assertSame("The actual writer rethrows the original read error", readFailure, reported)
            assertTrue("The real tee source did close its upstream", upstream.closed)
            assertArrayEquals("Unrelated committed bytes were preserved", retained,
                requireNotNull(cache.getCachedSpans(retainedKey).single().file).readBytes())
            assertFalse("Neither error path completes the two-byte resource", cache.isCached(key, 0, 2))
            if (refuseCommit) {
                assertTrue("The actual sink reached the refusing commit operation", commitRefused)
                assertFalse("The actual sink close did not return successfully", sinkCloseReturned)
                val cleanupError = requireNotNull(sinkCloseError)
                assertTrue(generateSequence<Throwable>(cleanupError) { it.cause }.any { it === commitFailure })
                assertTrue("The writer did not attach the cleanup failure", reported.suppressed.isEmpty())
                assertEquals(0L, cache.getCachedBytes(key, 0, 2))
                // CacheDataSource cleared its current source even after failed close. A second outer
                // close returns, but cannot retroactively establish the failed sink's close receipt.
                source.close()
                assertFalse(sinkCloseReturned)
                assertSame(cleanupError, sinkCloseError)
            } else {
                assertTrue(sinkCloseReturned)
                assertNull(sinkCloseError)
                assertEquals(1L, cache.getCachedBytes(key, 0, 2))
                assertArrayEquals(byteArrayOf(42), requireNotNull(cache.getCachedSpans(key).single().file).readBytes())
            }
        } finally {
            // Single-thread fixture, no worker or native I/O remains admitted. This release is test
            // cleanup only; it is not a production recovery or durable catalog permission.
            try { source?.close() } finally { try { cache.release() } finally { database.close() } }
        }
    }

    private class OneByteThenFailure(private val failure: IOException) : DataSource {
        private var read = false
        var closed = false
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): Uri = Uri.parse("memory:///partial")
        override fun open(dataSpec: DataSpec): Long { read = false; closed = false; return 2 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (read) throw failure
            buffer[offset] = 42
            read = true
            return 1
        }
        override fun close() { closed = true }
    }
}
