package dev.avery.muon

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.session.CacheBitmapLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Characterizes the pinned framework cache, not notification rendering or a proposed app fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NotificationArtworkIdentityControlTest {
    private val firstUri = Uri.parse("http://127.0.0.1:7814/api1/pic/medium/1")
    private val otherUri = Uri.parse("http://127.0.0.1:7814/api1/pic/medium/2")

    @Test fun changingTitleArtistAlbumAtSameUriDoesNotConsultDelegateAgain() {
        val old = bitmap(Color.RED)
        val replacement = bitmap(Color.BLUE)
        val delegate = RecordingLoader(old, replacement)
        val cache = CacheBitmapLoader(delegate)
        try {
            val first = requireNotNull(cache.loadBitmapFromMetadata(metadata(firstUri, "Old")))
            assertSame(old, first.get(3, TimeUnit.SECONDS))
            val changed = requireNotNull(cache.loadBitmapFromMetadata(metadata(firstUri, "Replacement")))
            assertSame(first, changed)
            assertSame(old, changed.get(3, TimeUnit.SECONDS))
            assertEquals(listOf(firstUri), delegate.requests)
            val differentUri = requireNotNull(cache.loadBitmapFromMetadata(metadata(otherUri, "Replacement")))
            assertNotSame(first, differentUri)
            assertSame(replacement, differentUri.get(3, TimeUnit.SECONDS))
            assertEquals(listOf(firstUri, otherUri), delegate.requests)
        } finally { old.recycle(); replacement.recycle() }
    }

    @Test fun aFailedFutureIsAlsoRetainedForSameUriUntilAnotherRequestReplacesIt() {
        val replacement = bitmap(Color.BLUE)
        val delegate = RecordingLoader(null, replacement)
        val cache = CacheBitmapLoader(delegate)
        try {
            val failed = requireNotNull(cache.loadBitmapFromMetadata(metadata(firstUri, "Old")))
            assertThrows(ExecutionException::class.java) { failed.get(3, TimeUnit.SECONDS) }
            val retry = requireNotNull(cache.loadBitmapFromMetadata(metadata(firstUri, "New")))
            assertSame(failed, retry)
            assertEquals(listOf(firstUri), delegate.requests)
            assertSame(replacement, requireNotNull(cache.loadBitmapFromMetadata(metadata(otherUri, "Other")))
                .get(3, TimeUnit.SECONDS))
            assertEquals(listOf(firstUri, otherUri), delegate.requests)
        } finally { replacement.recycle() }
    }

    private fun metadata(uri: Uri, tag: String) = MediaMetadata.Builder().setArtworkUri(uri)
        .setTitle(tag).setArtist("$tag artist").setAlbumTitle("$tag album").build()
    private fun bitmap(color: Int) = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private class RecordingLoader(private val first: Bitmap?, private val second: Bitmap) : BitmapLoader {
        val requests = mutableListOf<Uri>()
        override fun supportsMimeType(mimeType: String) = true
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            throw AssertionError("URI metadata should not invoke embedded decoding")
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            requests += uri
            return if (requests.size == 1) first?.let { Futures.immediateFuture(it) }
                ?: Futures.immediateFailedFuture(IOException("synthetic first load failure"))
            else Futures.immediateFuture(second)
        }
    }
}
