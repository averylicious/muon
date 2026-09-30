package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Actual pinned Media3 bitmap loader with deterministic encoded sources; no phone notification tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NotificationArtworkTest {
    private val uri = Uri.parse("http://192.168.1.10:7814/api1/pic/medium/42")

    @Test fun normalUriArtDecodesAndClosesSource() {
        val bytes = png(64)
        val source = Source(bytes, bytes.size.toLong())
        val bitmap = loader(source).loadBitmap(uri).get(10, TimeUnit.SECONDS)
        assertEquals(64, bitmap.width)
        assertEquals(32, bitmap.height)
        assertTrue(source.closed)
        bitmap.recycle()
    }

    @Test fun declaredOversizedImageIsRefusedBeforeReadingAndSourceCloses() {
        val source = Source(byteArrayOf(), NOTIFICATION_ART_BYTES + 1L)
        val failure = assertThrows(ExecutionException::class.java) {
            loader(source).loadBitmap(uri).get(10, TimeUnit.SECONDS)
        }
        assertTrue(failure.cause is IOException)
        assertEquals(0, source.position)
        assertTrue(source.closed)
    }

    @Test fun unknownLengthStopsAtCapPlusDetectionByteAndCloses() {
        val source = Source(ByteArray(NOTIFICATION_ART_BYTES + 4096), C.LENGTH_UNSET.toLong())
        val failure = assertThrows(ExecutionException::class.java) {
            loader(source).loadBitmap(uri).get(10, TimeUnit.SECONDS)
        }
        assertTrue(failure.cause is IOException)
        assertEquals(NOTIFICATION_ART_BYTES + 1, source.position)
        assertTrue(source.closed)
    }

    @Test fun embeddedOversizedArtIsRefusedWithoutOpeningNetwork() {
        val source = Source(byteArrayOf(), 0L)
        val failure = assertThrows(ExecutionException::class.java) {
            loader(source).decodeBitmap(ByteArray(NOTIFICATION_ART_BYTES + 1)).get(10, TimeUnit.SECONDS)
        }
        assertTrue(failure.cause is IOException)
        assertFalse(source.opened)
    }

    @Test fun highlyCompressedLargeImageIsSampledToNotificationDimensions() {
        val encoded = png(2048)
        assertTrue("Fixture must exercise compressed dimensions, not byte-limit rejection", encoded.size < NOTIFICATION_ART_BYTES)
        val bitmap = loader(Source(byteArrayOf(), 0L)).decodeBitmap(encoded).get(10, TimeUnit.SECONDS)
        assertTrue(bitmap.width in 1..NOTIFICATION_ART_SIDE)
        assertTrue(bitmap.height in 1..NOTIFICATION_ART_SIDE)
        bitmap.recycle()
    }

    private fun loader(source: Source) = notificationBitmapLoader(RuntimeEnvironment.getApplication(), DataSource.Factory { source })
    private fun png(width: Int): ByteArray = requireNotNull(javaClass.getResourceAsStream(
        "/fixtures/notification-art-$width.png")).use { it.readBytes() }
    private class Source(val bytes: ByteArray, val declared: Long) : DataSource {
        var position = 0
        var opened = false
        var closed = false
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long { opened = true; return declared }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count); position += count
            return count
        }
        override fun getUri(): Uri? = null
        override fun close() { closed = true }
    }
}
