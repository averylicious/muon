package dev.avery.muon

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal const val NOTIFICATION_ART_BYTES = 4 * 1024 * 1024
internal const val NOTIFICATION_ART_SIDE = 512

/** One physical worker and the newest waiting request, shared across service/loader instances.
 * A cancelled active Future may still be unwinding I/O; the worker starts nothing else until it
 * returns. Displaced pending futures finish as cancelled instead of retaining an endless backlog.
 * Caller-held futures/bitmaps and the Media3 metadata/cache wrappers are outside this queue bound.
 */
internal class NotificationArtworkExecutor : ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
    ArrayBlockingQueue<Runnable>(1), { work -> Thread(work, "muon-notification-art") }) {
    private val submissions = Any()
    override fun execute(command: Runnable) {
        var displaced: Future<*>? = null
        try {
            synchronized(submissions) {
                try { super.execute(command) }
                catch (full: RejectedExecutionException) {
                    if (isShutdown) throw full
                    // All submissions use this lock; the worker can only remove a queued task.
                    displaced = queue.poll() as? Future<*>
                    super.execute(command)
                }
            }
        } finally {
            // Future listeners can submit again. Publish the replacement and leave the lock first.
            displaced?.cancel(false)
        }
    }
}

private val notificationArtworkExecutor: ListeningExecutorService by lazy {
    MoreExecutors.listeningDecorator(NotificationArtworkExecutor())
}

/** The service has a separate loader from Compose: apply limits to URI and embedded artwork here. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun notificationBitmapLoader(context: Context, upstream: DataSource.Factory,
    executor: ListeningExecutorService = notificationArtworkExecutor,
    savedArt: (String) -> ByteArray? = { OfflineStore.current()?.art?.forEntry(it) }): BitmapLoader {
    val delegate = DataSourceBitmapLoader.Builder(context)
        .setExecutorService(executor)
        .setDataSourceFactory { LimitedArtworkSource(SavedArtworkSource(upstream.createDataSource(), savedArt)) }
        .setMaximumOutputDimension(NOTIFICATION_ART_SIDE).build()
    return object : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = try { delegate.loadBitmap(uri) }
            catch (closed: RejectedExecutionException) { Futures.immediateFailedFuture(closed) }
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            if (data.size > NOTIFICATION_ART_BYTES)
                Futures.immediateFailedFuture(IOException("Notification artwork exceeds 4 MiB"))
            else try { delegate.decodeBitmap(data) }
                catch (closed: RejectedExecutionException) { Futures.immediateFailedFuture(closed) }
    }
}

/** Allows at most the encoded cap plus one detection byte, including responses of unknown length. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LimitedArtworkSource(private val upstream: DataSource) : DataSource {
    private var read = 0
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun open(dataSpec: DataSpec): Long {
        read = 0
        val length = upstream.open(dataSpec)
        if (length > NOTIFICATION_ART_BYTES) throw IOException("Notification artwork exceeds 4 MiB")
        return length
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remaining = NOTIFICATION_ART_BYTES - read
        val count = upstream.read(buffer, offset, minOf(length, remaining + 1))
        if (count == C.RESULT_END_OF_INPUT) return count
        if (count > remaining) throw IOException("Notification artwork exceeds 4 MiB")
        read += count
        return count
    }
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()
}

/** Entry-owned artwork is resolved locally; malformed/missing local handles never fall back to HTTP. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SavedArtworkSource(private val upstream: DataSource,
    private val saved: (String) -> ByteArray?) : DataSource {
    private var active: DataSource? = null
    private val listeners = ArrayList<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme.equals(SAVED_ART_SCHEME, ignoreCase = true)) {
            val id = savedArtRequest(dataSpec.uri.toString()) ?: throw IOException("Invalid saved artwork handle")
            val bytes = saved(id) ?: throw IOException("Saved artwork isn't available")
            if (bytes.size > NOTIFICATION_ART_BYTES) throw IOException("Saved artwork exceeds 4 MiB")
            ByteArrayDataSource(bytes)
        } else upstream
        active = source
        listeners.forEach(source::addTransferListener)
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "Read before open" }.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() { try { active?.close() } finally { active = null } }
}
