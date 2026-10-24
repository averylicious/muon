package dev.avery.muon

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.VorbisUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Raw pinned-library characterization, not a production guard or a phone/OOM test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VorbisMetadataBoundaryTest {
    @Test fun unsignedCountReachesArrayConstructionBeforePayloadValidation() {
        val valid = ParsableByteArray(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(2).putInt(0).putInt(0).array())
        val parsed = VorbisUtil.readVorbisCommentHeader(valid, false, false)
        assertEquals("", parsed.vendor)
        assertArrayEquals(arrayOf("", ""), parsed.comments)
        assertEquals(16, valid.position)

        // Eight bytes only: vendor length0, unsigned count2^31. Its negative Int cast cannot
        // allocate an array, so this demonstrates the unchecked boundary without heap pressure.
        val malformed = ParsableByteArray(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(Int.MIN_VALUE).array())
        assertThrows(NegativeArraySizeException::class.java) {
            VorbisUtil.readVorbisCommentHeader(malformed, false, false)
        }
        assertEquals(8, malformed.position)
        assertEquals(0, malformed.bytesLeft())
    }
}
