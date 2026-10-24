package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ogg.OggExtractor
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

        // Exercise the actual Ogg -> OpusReader -> VorbisUtil route too. Only91 bytes;
        // checksums are placeholders because this pinned extractor does not validate Ogg CRC.
        val opusHead = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1).put(1)
            .putShort(0).putInt(48_000).putShort(0).put(0).array()
        val tags = "OpusTags".toByteArray(Charsets.US_ASCII) + malformed.data
        val file = page(opusHead, 2, 0) + page(tags, 0, 1)
        assertEquals(91, file.size)
        var cursor = 0
        val reader = DataReader { target, offset, length ->
            if (length == 0) 0 else if (cursor == file.size) C.RESULT_END_OF_INPUT else {
                val count = minOf(length, file.size - cursor)
                file.copyInto(target, offset, cursor, cursor + count)
                cursor += count
                count
            }
        }
        val input = DefaultExtractorInput(reader, 0, file.size.toLong())
        var tracks = 0
        val extractor = OggExtractor()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                tracks++
                assertEquals(C.TRACK_TYPE_AUDIO, type)
                return DiscardingTrackOutput()
            }
            override fun endTracks() {}
            override fun seekMap(map: SeekMap) {}
        })
        try {
            assertTrue(extractor.sniff(input))
            input.resetPeekPosition()
            assertThrows(NegativeArraySizeException::class.java) {
                extractor.read(input, PositionHolder())
            }
            assertEquals(1, tracks)
            assertEquals(file.size.toLong(), input.position)
        } finally { extractor.release() }
    }

    private fun page(payload: ByteArray, type: Int, sequence: Int): ByteArray {
        require(payload.size < 255)
        return ByteBuffer.allocate(28 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".toByteArray(Charsets.US_ASCII)).put(0).put(type.toByte())
            .putLong(0).putInt(1).putInt(sequence).putInt(0).put(1)
            .put(payload.size.toByte()).put(payload).array()
    }
}
