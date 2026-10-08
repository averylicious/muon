package dev.avery.muon

import android.net.Uri
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.offline.DownloadRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadMoveBudgetTest {
    private fun request(data: Int = 3): DownloadRequest = DownloadRequest.Builder("saved/id", Uri.parse("http://localhost/7"))
        .setCustomCacheKey("saved/id").setData(ByteArray(data) { 9 }).build()

    @Test fun exactBoundaryIncludesTextRawTagsDrmKeysAndStreamKeysWithoutClipping() {
        val original = DownloadRequest.Builder("saved/id", Uri.parse("http://localhost/7"))
            .setMimeType("video/mp4").setCustomCacheKey("saved/id").setData(byteArrayOf(9, 8, 7))
            .setKeySetId(byteArrayOf(1, 2)).setStreamKeys(listOf(StreamKey(0, 1, 2))).build()
        val cost = 3L + 2L + 2L * ("saved/id".length * 2 + "http://localhost/7".length + "video/mp4".length) + 64L + 256L
        assertEquals(cost, moveRequestBytes(original))
        val budget = DownloadMoveBudget(cost)
        assertTrue(budget.fits(original)); budget.commit(original)
        assertFalse(budget.fits(original))
        assertFalse(DownloadMoveBudget(cost - 1).fits(original))
        assertArrayEquals(byteArrayOf(9, 8, 7), original.data)
        assertArrayEquals(byteArrayOf(1, 2), original.keySetId)
        assertEquals(listOf(StreamKey(0, 1, 2)), original.streamKeys)
    }

    @Test fun failedOrSkippedCopyDoesNotConsumeRoomButSuccessfulCopyDoes() {
        val original = request()
        val budget = DownloadMoveBudget(moveRequestBytes(original))
        repeat(5) { assertTrue(budget.fits(original)) } // Queries are not reservations.
        budget.commit(original)
        assertFalse(budget.fits(original))
        assertTrue("Fits alone distinguishes retryable remainder", budget.fitsAlone(original))
        assertThrows(IllegalStateException::class.java) { budget.commit(original) }
    }

    @Test fun oversizedRequestIsRefusedWithoutMutatingItsDataOrBudget() {
        val huge = request(1024)
        val small = request()
        val budget = DownloadMoveBudget(moveRequestBytes(small))
        assertFalse(budget.fitsAlone(huge)); assertFalse(budget.fits(huge))
        assertTrue(budget.fits(small))
        assertEquals(1024, huge.data.size)
        assertTrue(huge.data.all { it == 9.toByte() })
    }
}
