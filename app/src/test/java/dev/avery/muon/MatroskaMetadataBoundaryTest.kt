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
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.io.EOFException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Raw pinned library behavior for #726; small declarations, not guard/decoder/OOM/device proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MatroskaMetadataBoundaryTest {
    @Test fun originalAacControlUsesTheRealDefaultMatroskaCandidateAndSourceReopens() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/mkv-admission/control.mka")).use { it.readBytes() }
        val raw = actual()
        val out = Capture()
        var input: ExtractorInput = opened(bytes, 0)
        try {
            assertTrue(raw.sniff(input)); input.resetPeekPosition()
            raw.init(out)
            finish(raw, input) { at -> opened(bytes, at).also { input = it } }
            assertEquals(MimeTypes.AUDIO_AAC, out.format?.sampleMimeType)
            assertEquals(48_000, out.format?.sampleRate)
            assertNotNull(out.map)
        } finally { raw.release() }
    }

    @Test fun backedEbmlPrefixDoesNotBoundStringOrCodecPrivateArraysToTheirMissingBodies() {
        for ((name, leaf) in listOf(
            "string" to header(0x86, 4096), // CodecID string: byte array precedes UTF-8 parsing.
            "codec private" to (element(0x86, ascii("A_AAC")) + header(0x63a2, 4096)),
        )) {
            // The complete real-type EBML prefix sniffs successfully; only the nested body is
            // absent. Parent declarations describe that missing4KiB too, without allocating it.
            val entrySize = leaf.size + 4096
            val entry = header(0xae, entrySize.toLong()) + leaf
            val tracks = header(0x1654ae6b, entry.size.toLong() + 4096) + entry
            val bytes = ebml() + header(0x18538067, tracks.size.toLong() + 4096) + tracks
            assertTrue(bytes.size < 100)
            var observed = 0
            val input = object : ForwardingExtractorInput(opened(bytes, 0)) {
                override fun readFully(target: ByteArray, offset: Int, length: Int) {
                    if (offset == 0 && length == 4096) observed = target.size
                    super.readFully(target, offset, length)
                }
            }
            val raw = actual()
            try {
                assertTrue(name, raw.sniff(input)); input.resetPeekPosition()
                raw.init(Capture())
                assertThrows(EOFException::class.java) { finish(raw, input) { error("Unexpected malformed-source seek") } }
                assertEquals(name, 4096, observed)
            } finally { raw.release() }
        }
    }

    @Test fun unknownLengthSniffCanGrowItsPeekBufferBeforeTheDeclaredHeaderPayloadArrives() {
        val payload = 128 * 1024 // Moderate allocation, never a huge positive heap-pressure probe.
        val child = header(0x4282, payload.toLong())
        val bytes = header(0x1a45dfa3, child.size.toLong() + payload) + child
        val native = DefaultExtractorInput(reader(bytes, 0), 0, C.LENGTH_UNSET.toLong())
        val field = DefaultExtractorInput::class.java.getDeclaredField("peekBuffer").also { it.isAccessible = true }
        val before = (field.get(native) as ByteArray).size
        var advance = 0
        val input = object : ForwardingExtractorInput(native) {
            override fun advancePeekPosition(length: Int) { advance = length; super.advancePeekPosition(length) }
        }
        val raw = actual()
        try {
            assertThrows(EOFException::class.java) { raw.sniff(input) }
            assertEquals(payload, advance)
            assertTrue("Actual native peek array grew before the body EOF", (field.get(native) as ByteArray).size > before)
            assertTrue(bytes.size < 20)
        } finally { raw.release() }
    }

    @Test fun subtitleBlockAllocatesExpandedScratchBeforeItsMissingSampleBody() {
        val bodyBytes = 32 * 1024 // Small actual allocation; never an OOM/heap-pressure probe.
        val entry = element(0xae, element(0xd7, byteArrayOf(1)) +
            element(0x83, byteArrayOf(17)) + element(0x86, ascii("S_TEXT/UTF8")))
        val beforeCluster = element(0x1549a966, byteArrayOf()) + element(0x1654ae6b, entry)
        val block = header(0xa3, bodyBytes.toLong() + 4) + byteArrayOf(0x81.toByte(), 0, 0, 0)
        val clusterBody = element(0xe7, byteArrayOf(0)) + block
        val cluster = header(0x1f43b675, clusterBody.size.toLong() + bodyBytes) + clusterBody
        val bytes = ebml() + header(0x18538067, beforeCluster.size.toLong() + cluster.size + bodyBytes) +
            beforeCluster + cluster
        assertTrue(bytes.size < 128)
        var observed = 0
        val input = object : ForwardingExtractorInput(opened(bytes, 0)) {
            override fun readFully(target: ByteArray, offset: Int, length: Int) {
                if (length == bodyBytes && offset > 0) observed = target.size
                super.readFully(target, offset, length)
            }
        }
        val raw = actual()
        val output = Capture()
        try {
            assertTrue(raw.sniff(input)); input.resetPeekPosition()
            raw.init(output)
            assertThrows(EOFException::class.java) { finish(raw, input) { error("Unexpected fixture seek") } }
            // Default factory keeps its native extraction-time subtitle transcoding enabled.
            assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, output.format?.sampleMimeType)
            assertEquals(MimeTypes.APPLICATION_SUBRIP, output.format?.codecs)
            assertTrue("Native subtitle scratch reserves twice the declared sample plus its prefix",
                observed >= 2 * bodyBytes && observed < 2 * bodyBytes + 128)
        } finally { raw.release() }
    }

    @Test fun unfinishedXiphLacingGrowsNativeHeaderScratchBeforeAudioDelivery() {
        // Native MP3 track setup needs no codec-private data. These are parser/header controls,
        // not decoded MP3 audio or large-memory probes.
        val entry = element(0xae, element(0xd7, byteArrayOf(1)) +
            element(0x83, byteArrayOf(2)) + element(0x86, ascii("A_MPEG/L3")))
        val before = element(0x1549a966, byteArrayOf()) + element(0x1654ae6b, entry)
        for (laceBytes in listOf(32, 32 * 1024)) {
            // Two laced samples, but the first sample's Xiph size never terminates. Every FF
            // increases its length and requires another byte in the native readScratch array.
            val block = header(0xa3, laceBytes.toLong() + 32) +
                byteArrayOf(0x81.toByte(), 0, 0, 2, 1) + ByteArray(laceBytes) { 0xff.toByte() }
            val cluster = header(0x1f43b675, block.size.toLong() + 32) +
                element(0xe7, byteArrayOf(0)) + block
            val bytes = ebml() + header(0x18538067, before.size.toLong() + cluster.size + 32) + before + cluster
            var scratch = 0
            val input = object : ForwardingExtractorInput(opened(bytes, 0)) {
                override fun readFully(target: ByteArray, offset: Int, length: Int) {
                    if (length == 1 && offset >= 4) scratch = maxOf(scratch, target.size)
                    super.readFully(target, offset, length)
                }
            }
            val raw = actual()
            val output = Capture()
            try {
                assertTrue(raw.sniff(input)); input.resetPeekPosition(); raw.init(output)
                assertThrows(EOFException::class.java) { finish(raw, input) { error("Unexpected fixture seek") } }
                assertEquals(MimeTypes.AUDIO_MPEG, output.format?.sampleMimeType)
                assertTrue("Actual native header scratch grew with delivered lace bytes", scratch >= laceBytes + 4)
                assertEquals("No audio bytes reached the output during unfinished lacing", 0, output.sampleWrites)
                assertTrue(bytes.size < 40 * 1024)
            } finally { raw.release() }
        }
    }

    private fun actual() = DefaultExtractorsFactory().createExtractors().single { it is MatroskaExtractor }
    private fun finish(extractor: Extractor, start: ExtractorInput, reopen: (Long) -> ExtractorInput) {
        var input = start
        val holder = PositionHolder()
        repeat(1000) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> input = reopen(holder.position)
                Extractor.RESULT_END_OF_INPUT -> return
                else -> error("Unexpected extractor result")
            }
        }
        error("Tiny Matroska fixture did not finish")
    }
    private class Capture : ExtractorOutput {
        var format: Format? = null
        var map: SeekMap? = null
        var sampleWrites = 0
        override fun track(id: Int, type: Int): TrackOutput {
            val discarded = DiscardingTrackOutput()
            return object : TrackOutput by discarded {
                override fun format(value: Format) { format = value }
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    sampleWrites++
                    return discarded.sampleData(input, length, allowEndOfInput, sampleDataPart)
                }
            }
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }
    private fun opened(bytes: ByteArray, at: Long) = DefaultExtractorInput(reader(bytes, at), at, bytes.size.toLong())
    private fun reader(bytes: ByteArray, start: Long): DataReader {
        var at = start.toInt()
        return DataReader { target, offset, length ->
            if (length == 0) 0 else if (at >= bytes.size) C.RESULT_END_OF_INPUT
            else minOf(length, bytes.size - at).also { count -> bytes.copyInto(target, offset, at, at + count); at += count }
        }
    }
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
    private fun id(value: Int): ByteArray {
        val bytes = when { value > 0xffffff -> 4; value > 0xffff -> 3; value > 0xff -> 2; else -> 1 }
        return ByteArray(bytes) { i -> (value ushr ((bytes - 1 - i) * 8)).toByte() }
    }
    private fun size(value: Long): ByteArray {
        for (width in 1..8) if (value < (1L shl (7 * width)) - 1) {
            val marked = value or (1L shl (7 * width))
            return ByteArray(width) { i -> (marked ushr ((width - 1 - i) * 8)).toByte() }
        }
        error("Fixture declaration too large")
    }
    private fun header(id: Int, bytes: Long) = id(id) + size(bytes)
    private fun element(id: Int, bytes: ByteArray) = header(id, bytes.size.toLong()) + bytes
    private fun ebml() = element(0x1a45dfa3, element(0x4282, ascii("matroska")))
}
