@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.wav.WavExtractor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual pinned WAV reader, small inline RIFF controls; no audio-device or heap-pressure test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WavMetadataAdmissionTest {
    @Test fun ordinaryFloatExtensibleRf64AndImaControlsKeepFormatAndSeeking() {
        val fmt = format()
        val guid = byteArrayOf(0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71)
        val extensible = format(type = 65534) + le16(22) + le16(16) + le32(4) + le16(1) + guid
        val ds64 = atom("ds64", le64(80) + le64(0x20746d66) + le64(4) + le32(0))
        // RF64's8-byte data-size peek literally starts with 'fmt '. It isn't a fmt header.
        val rf64 = ascii("RF64") + le32(-1) + ascii("WAVE") + ds64 + atom("fmt ", fmt) + ascii("data") + le32(-1) + ByteArray(8)
        for ((name, bytes) in listOf(
            "PCM" to wave(atom("fmt ", fmt), atom("data", ByteArray(8))),
            "float" to wave(atom("fmt ", format(type = 3, block = 4, bits = 32)), atom("data", ByteArray(16))),
            "extensible" to wave(atom("fmt ", extensible), atom("data", ByteArray(8))),
            "A-law" to wave(atom("fmt ", format(type = 6, block = 1, bits = 8)), atom("data", ByteArray(8))),
            "mu-law" to wave(atom("fmt ", format(type = 7, block = 1, bits = 8)), atom("data", ByteArray(8))),
            "RF64" to rf64,
            "IMA" to ima(),
        )) {
            val out = Capture()
            run(bytes, WavLimits(outputBytes = 64 * 1024), out, seeks = true)
            val expected = when (name) { "A-law" -> MimeTypes.AUDIO_ALAW; "mu-law" -> MimeTypes.AUDIO_MLAW; else -> MimeTypes.AUDIO_RAW }
            assertEquals(name, expected, out.format?.sampleMimeType)
            assertTrue(name, requireNotNull(out.map).isSeekable)
            assertEquals(name, 1, out.format?.channelCount)
        }
    }

    @Test fun fmtHeaderRefusesBeforeTheExtensionArrayOrItsPeek() {
        val pcm = format()
        val full = wave(atom("fmt ", pcm + ByteArray(4080)), atom("data", ByteArray(8)))
        run(full, WavLimits(formatBytes = 8192), Capture())
        val budget = Events()
        val error = assertThrows(StreamMetadataRefusal::class.java) {
            run(full, WavLimits(formatBytes = 1024), Capture(), budget)
        }
        assertFalse(error.contentIsMalformed)
        assertEquals(0, budget.largeExtraPeeks)
        assertTrue(error.message.contains("audio format"))
        assertFalse(error.message.contains("artwork"))
        val absent = wave(ascii("fmt ") + le32(4096) + pcm)
        val short = Events()
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            run(absent, WavLimits(), Capture(), short)
        }.contentIsMalformed)
        assertEquals(0, short.largeExtraPeeks)
    }

    @Test fun derivedWriterBuffersAndPublishedInputSizeAreAdmittedFromActualFields() {
        val pcm = wave(atom("fmt ", format(rate = 160_000)), atom("data", ByteArray(8)))
        for ((name, bytes) in listOf("PCM" to pcm, "IMA" to ima())) {
            val control = Capture()
            run(bytes, WavLimits(outputBytes = 64 * 1024), control)
            assertEquals(name, MimeTypes.AUDIO_RAW, control.format?.sampleMimeType)
            val refused = Capture()
            val error = assertThrows(StreamMetadataRefusal::class.java) {
                run(bytes, WavLimits(outputBytes = 16 * 1024), refused)
            }
            assertFalse(name, error.contentIsMalformed)
            assertNull(name, refused.format)
        }
        val highBit = wave(atom("fmt ", format(rate = Int.MIN_VALUE, average = 0)), atom("data", ByteArray(0)))
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            run(highBit, WavLimits(), Capture())
        }.contentIsMalformed) // Native's signed-u32 read fails too; no large allocation attempt.
    }

    @Test fun interruptedExtraPeekReopensAndReadmitsChangedFmtBeforeCommit() {
        val original = wave(atom("fmt ", format() + ByteArray(4)), atom("data", ByteArray(8)))
        val complete = Events(fault = 38) // Inside the unused extension, after fixed PCM fields.
        val out = Capture()
        run(original, WavLimits(), out, complete)
        assertEquals(-1L, complete.fault)
        assertEquals(MimeTypes.AUDIO_RAW, out.format?.sampleMimeType)
        val changed = Events(fault = 38, replacement = wave(ascii("fmt ") + le32(4096) + format()))
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            run(original, WavLimits(), Capture(), changed)
        }.contentIsMalformed)
        assertEquals(-1L, changed.fault)
        assertEquals("Changed fmt must be checked before its native extension allocation", 0, changed.largeExtraPeeks)
    }

    private fun run(bytes: ByteArray, limits: WavLimits, out: Capture, events: Events = Events(), seeks: Boolean = false) {
        var current = bytes
        fun open(at: Long): ExtractorInput = object : ForwardingExtractorInput(DefaultExtractorInput(
            Reader(current, at, events), at, current.size.toLong())) {
            override fun peekFully(target: ByteArray, offset: Int, length: Int) {
                if (length == 4080) events.largeExtraPeeks++
                super.peekFully(target, offset, length)
            }
        }
        val actual = DefaultExtractorsFactory().createExtractors().single { it is WavExtractor }
        val extractor = MetadataBoundedExtractor(actual, wavLimits = limits)
        var input = open(0)
        fun finish() {
            val holder = PositionHolder()
            repeat(1000) {
                val result = try { extractor.read(input, holder) } catch (_: Interrupted) {
                    current = events.replacement ?: current
                    input = open(input.position)
                    return@repeat
                }
                when (result) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_END_OF_INPUT -> return
                    else -> error("Unexpected WAV source seek")
                }
            }
            error("WAV fixture did not finish")
        }
        try {
            assertTrue(extractor.sniff(input)); input.resetPeekPosition()
            extractor.init(out); finish()
            if (seeks) {
                val point = requireNotNull(out.map).getSeekPoints(0).first
                extractor.seek(point.position, point.timeUs); input = open(point.position); finish()
                extractor.seek(0, 0); input = open(0); finish()
            }
        } finally { extractor.release() }
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
    private class Events(var fault: Long = -1, val replacement: ByteArray? = null) { var largeExtraPeeks = 0 }
    private class Interrupted : IOException("Injected format extra-data interruption")
    private class Reader(private val bytes: ByteArray, private var at: Long, private val events: Events) : DataReader {
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (at >= bytes.size) return C.RESULT_END_OF_INPUT
            var count = minOf(length.toLong(), bytes.size - at).toInt()
            if (events.fault >= at && events.fault < at + count) {
                if (events.fault == at) { events.fault = -1; throw Interrupted() }
                count = (events.fault - at).toInt()
            }
            bytes.copyInto(target, offset, at.toInt(), at.toInt() + count)
            at += count
            return count
        }
    }
    private fun ima(): ByteArray {
        val fmt = format(type = 17, rate = 160_000, block = 256, bits = 4, average = 160_000 * 256 / 505) + le16(2) + le16(505)
        return wave(atom("fmt ", fmt), atom("data", ByteArray(0)))
    }
    private fun format(type: Int = 1, rate: Int = 48_000, block: Int = 2, bits: Int = 16, average: Int = rate * block) =
        le16(type) + le16(1) + le32(rate) + le32(average) + le16(block) + le16(bits)
    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
    private fun le16(value: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    private fun le32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun le64(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    private fun atom(name: String, bytes: ByteArray) = ascii(name) + le32(bytes.size) + bytes
    private fun wave(vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ascii("WAVE")) { result, part -> result + part }
        return ascii("RIFF") + le32(body.size) + body
    }
}
