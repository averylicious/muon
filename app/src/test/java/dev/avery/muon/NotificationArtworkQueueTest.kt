package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual pinned Media3 loader and source closure; queue policy, not notification/device timing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NotificationArtworkQueueTest {
    private class Fixture : AutoCloseable {
        val worker = NotificationArtworkExecutor()
        val executor = MoreExecutors.listeningDecorator(worker)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val trace = ConcurrentLinkedQueue<String>()
        val bytes = requireNotNull(NotificationArtworkQueueTest::class.java.getResourceAsStream(
            "/fixtures/notification-art-64.png")).use { it.readBytes() }
        val factory = DataSource.Factory { object : DataSource {
            var openedUri: Uri? = null
            var position = 0
            override fun addTransferListener(listener: TransferListener) = Unit
            override fun open(spec: DataSpec): Long {
                openedUri = spec.uri; trace += "open:" + openedUri!!.lastPathSegment
                return bytes.size.toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position == 0 && openedUri!!.lastPathSegment == "first") {
                    held.countDown()
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw IOException("Fixture body not released")
                    } catch (_: InterruptedException) { throw InterruptedIOException("Fixture body interrupted") }
                }
                if (position == bytes.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + count); position += count
                return count
            }
            override fun getUri(): Uri? = openedUri
            override fun close() { trace += "close:" + openedUri?.lastPathSegment }
        } }
        // Both delegates share one budget, like multiple service/cache wrapper instances.
        val firstLoader = notificationBitmapLoader(RuntimeEnvironment.getApplication(), factory, executor)
        val nextLoader = notificationBitmapLoader(RuntimeEnvironment.getApplication(), factory, executor)
        fun uri(name: String) = Uri.parse("http://127.0.0.1/cover/$name")
        fun start() = firstLoader.loadBitmap(uri("first")).also {
            assertTrue("Actual encoded read must be held", held.await(10, TimeUnit.SECONDS))
        }
        override fun close() {
            release.countDown()
            executor.shutdownNow().forEach { (it as? java.util.concurrent.Future<*>)?.cancel(false) }
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun rapidUriAndEmbeddedRequestsKeepOnlyActiveAndNewestWaitingAcrossLoaders() {
        Fixture().use { f ->
            val active = f.start()
            val obsolete = ArrayList<ListenableFuture<android.graphics.Bitmap>>()
            repeat(32) { i ->
                obsolete += if (i % 2 == 0) f.nextLoader.loadBitmap(f.uri("old$i"))
                    else f.nextLoader.decodeBitmap(f.bytes.copyOf())
                assertTrue(f.worker.queue.size <= 1)
            }
            val latest = f.nextLoader.loadBitmap(f.uri("latest"))
            assertTrue(obsolete.all { it.isCancelled })
            assertFalse(active.isCancelled)
            assertEquals(listOf("open:first"), f.trace.toList())
            f.release.countDown()
            active.get(10, TimeUnit.SECONDS).recycle()
            latest.get(10, TimeUnit.SECONDS).recycle()
            assertEquals(listOf("open:first", "close:first", "open:latest", "close:latest"), f.trace.toList())
        }
    }

    @Test fun activeCancellationFinishesItsActualSourceBeforeNewestWorkStarts() {
        Fixture().use { f ->
            val active = f.start()
            val latest = f.nextLoader.loadBitmap(f.uri("latest"))
            assertTrue(active.cancel(true))
            latest.get(10, TimeUnit.SECONDS).recycle()
            assertEquals(listOf("open:first", "close:first", "open:latest", "close:latest"), f.trace.toList())
            assertTrue(active.isCancelled)
        }
    }

    @Test fun displacedFutureListenerMayReenterWithoutOrphaningTheNextFuture() {
        Fixture().use { f ->
            val active = f.start()
            val old = f.nextLoader.loadBitmap(f.uri("old"))
            val reentered = AtomicReference<ListenableFuture<android.graphics.Bitmap>>()
            old.addListener({ reentered.set(f.nextLoader.loadBitmap(f.uri("reentered"))) }, MoreExecutors.directExecutor())
            val displacedAgain = f.nextLoader.loadBitmap(f.uri("intermediate"))
            assertTrue(old.isCancelled)
            assertTrue(displacedAgain.isCancelled)
            val latest = requireNotNull(reentered.get())
            f.release.countDown()
            active.get(10, TimeUnit.SECONDS).recycle()
            latest.get(10, TimeUnit.SECONDS).recycle()
            assertEquals(listOf("open:first", "close:first", "open:reentered", "close:reentered"), f.trace.toList())
        }
    }

    @Test fun stoppedExecutorReturnsFailedFuturesWithoutOpeningArtwork() {
        Fixture().use { f ->
            f.executor.shutdown()
            for (future in listOf(f.nextLoader.loadBitmap(f.uri("closed")), f.nextLoader.decodeBitmap(f.bytes))) {
                val failure = assertThrows(ExecutionException::class.java) { future.get(10, TimeUnit.SECONDS) }
                assertTrue(failure.cause is RejectedExecutionException)
            }
            assertTrue(f.trace.isEmpty())
        }
    }
}
