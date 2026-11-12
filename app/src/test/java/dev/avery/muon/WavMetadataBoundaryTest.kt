@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.wav.WavExtractor
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Raw pinned-reader behavior for #723, not a production guard, decode, OOM or device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WavMetadataBoundaryTest {
    @Test fun validPcmUsesTheDefaultReaderAndFmtExtensionAllocatesBeforeItsMissingBytes() {
        val pcm = format(type = 1, rate = 48_000, block = 2, bits = 16)
        val bytes = wave(atom("fmt ", pcm), atom("data", ByteArray(8)))
        val out = Capture()
        val selected = DefaultExtractorsFactory().createExtractors().single { it is WavExtractor }
        val input = DefaultExtractorInput(reader(bytes), 0, bytes.size.toLong())
        try {
            assertTrue(selected.sniff(input)); input.resetPeekPosition()
            selected.init(out)
            finish(selected, input)
            assertEquals(MimeTypes.AUDIO_RAW, out.format?.sampleMimeType)
            assertEquals(48_000, out.format?.sampleRate)
            assertTrue(requireNotNull(out.map).isSeekable)
        } finally { selected.release() }

        // The actual file has only the16 fixed fields, while fmt declares4KiB. Observe the real
        // small allocation at its body peek, never substitute a huge positive declaration.
        val missing = wave(ascii("fmt ") + le32(4096) + pcm)
        var allocated = 0
        val observed = object : ForwardingExtractorInput(DefaultExtractorInput(reader(missing), 0, missing.size.toLong())) {
            override fun peekFully(target: ByteArray, offset: Int, length: Int) {
                if (length == 4096 - 16) allocated = target.size
                super.peekFully(target, offset, length)
            }
        }
        val raw = WavExtractor()
        try {
            raw.init(Capture())
            assertThrows(EOFException::class.java) { finish(raw, observed) }
            assertEquals(4080, allocated)
            assertTrue(allocated > missing.size)
        } finally { raw.release() }
    }

    @Test fun imaWriterBuildsDerivedBuffersBeforeReadingAnySampleData() {
        val rate = 160_000
        val block = 256
        val frames = 505 // ((256 - 4 * one channel) * 8) / (4 bits * one channel) + 1.
        val fmt = format(type = 17, rate = rate, block = block, bits = 4, average = rate * block / frames) +
            le16(2) + le16(frames)
        val bytes = wave(atom("fmt ", fmt), atom("data", ByteArray(0)))
        assertTrue(bytes.size < 100)
        val raw = DefaultExtractorsFactory().createExtractors().single { it is WavExtractor }
        val out = Capture()
        try {
            val input = DefaultExtractorInput(reader(bytes), 0, bytes.size.toLong())
            assertTrue(raw.sniff(input)); input.resetPeekPosition()
            raw.init(out)
            finish(raw, input)
            assertEquals(MimeTypes.AUDIO_RAW, out.format?.sampleMimeType)
            assertEquals(rate, out.format?.sampleRate)
            val writer = WavExtractor::class.java.getDeclaredField("outputWriter").also { it.isAccessible = true }.get(raw)
            val inputBytes = writer.javaClass.getDeclaredField("inputData").also { it.isAccessible = true }.get(writer) as ByteArray
            val decoded = writer.javaClass.getDeclaredField("decodedData").also { it.isAccessible = true }.get(writer) as ParsableByteArray
            //32 blocks produce moderate8KiB+32KiB arrays from a tiny format-only file.
            assertEquals(32 * block, inputBytes.size)
            assertEquals(32 * frames * 2, decoded.data.size)
            assertTrue(decoded.data.size > bytes.size * 100)
            assertEquals(32_000, out.format?.maxInputSize)
        } finally { raw.release() }
    }

    private fun finish(extractor: Extractor, input: androidx.media3.extractor.ExtractorInput) {
        val holder = PositionHolder()
        repeat(100) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_END_OF_INPUT -> return
                else -> error("Unexpected seek for a tiny WAV fixture")
            }
        }
        error("WAV fixture did not finish")
    }
    private class Capture : ExtractorOutput {
        var format: Format? = null
        var map: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(value: Format) { format = value }
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }
    private fun reader(bytes: ByteArray): DataReader {
        var at = 0
        return DataReader { target, offset, length ->
            if (length == 0) 0 else if (at == bytes.size) C.RESULT_END_OF_INPUT
            else minOf(length, bytes.size - at).also { count -> bytes.copyInto(target, offset, at, at + count); at += count }
        }
    }
    private fun format(type: Int, rate: Int, block: Int, bits: Int, average: Int = rate * block) =
        le16(type) + le16(1) + le32(rate) + le32(average) + le16(block) + le16(bits)
    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
    private fun le16(value: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    private fun le32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun atom(name: String, bytes: ByteArray) = ascii(name) + le32(bytes.size) + bytes
    private fun wave(vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ascii("WAVE")) { result, part -> result + part }
        return ascii("RIFF") + le32(body.size) + body
    }
}
