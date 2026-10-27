@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.MlltFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.mp3.Mp3Extractor
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class Id3FrameAdmissionTest {
    @Test fun actualPeekerRefusesMlltBeforeDecodeAndKeepsAnExactlyAdmittedTable() {
        val bytes = tag(frame("MLLT", mllt(64)))
        for (sniff in listOf(false, true)) {
            val bounded = wrapped(Id3FrameLimits(references = 255))
            try {
                bounded.init(Capture())
                assertThrows(StreamMetadataRefusal::class.java) {
                    val source = source(bytes)
                    if (sniff) bounded.sniff(source) else bounded.read(source, PositionHolder())
                }
            } finally { bounded.release() }
        }
        val out = play(bytes, Id3FrameLimits(references = 256))
        assertEquals(256, entries(out).filterIsInstance<MlltFrame>().single().bytesDeviations.size)
        assertEquals("MlltSeeker", requireNotNull(out.map).javaClass.simpleName)
    }

    @Test fun realFrameDepthAndValueBudgetsPreserveTightSmallControls() {
        var nested = frame("TIT2", byteArrayOf(0) + "Leaf".toByteArray())
        repeat(3) { nested = frame("CHAP", chapterPrefix() + nested) }
        val siblings = frame("XXXX", byteArrayOf(1)) + frame("XXXX", byteArrayOf(2)) + frame("XXXX", byteArrayOf(3))
        val artists = frame("TPE1", byteArrayOf(0) + "a\u0000b\u0000c\u0000".toByteArray())
        for ((frames, bad, good) in listOf(
            Triple(nested, Id3FrameLimits(depth = 2), Id3FrameLimits(depth = 3)),
            Triple(siblings, Id3FrameLimits(frames = 2), Id3FrameLimits(frames = 3)),
            Triple(artists, Id3FrameLimits(values = 2), Id3FrameLimits(values = 3)),
        )) {
            assertThrows(StreamMetadataRefusal::class.java) { play(tag(frames), bad) }
            assertEquals(MimeTypes.AUDIO_MPEG, play(tag(frames), good).format?.sampleMimeType)
        }
    }

    @Test fun versionsExtendedHeadersFooterAndRawV4SizeFallbackPreserveGainMetadata() {
        assertEquals(listOf("Title"), entries(play(tag(frame("TT2", byteArrayOf(0) + "Title".toByteArray(), version = 2), 2)))
            .filterIsInstance<TextInformationFrame>().single().values)
        val gain = gainBody()
        val v3Extended = byteArrayOf(0, 0, 0, 6) + ByteArray(6) + frame("TXXX", gain)
        val v4Extended = sync(6) + byteArrayOf(0, 0) + frame("TXXX", gain, version = 4) + ByteArray(10)
        val fallback = frame("TXXX", gain.copyOf(130), version = 4, rawV4Size = true)
        for (bytes in listOf(tag(v3Extended, flags = 0x40), tag(v4Extended, 4, 0x50), tag(fallback, 4))) {
            assertGain(play(bytes))
        }
    }

    @Test fun unsynchronizationUsesAPrivateWorkingCopyAndUnsupportedFramesStayIgnored() {
        val binary = byteArrayOf(-1, 0xe0.toByte())
        val logical = frame("XXXX", binary) + frame("TXXX", gainBody())
        val unsynchronized = logical.flatMap { if (it == (-1).toByte()) listOf(it, 0.toByte()) else listOf(it) }.toByteArray()
        val v3 = tag(unsynchronized, flags = 0x80)
        val v4 = tag(frame("XXXX", byteArrayOf(-1, 0, 0xe0.toByte()), 4, flags = 2) + frame("TXXX", gainBody(), 4), 4)
        for (bytes in listOf(v3, v4)) {
            val before = bytes.copyOf()
            val out = play(bytes)
            assertGain(out)
            assertArrayEquals(binary, entries(out).filterIsInstance<BinaryFrame>().single().data)
            assertArrayEquals(before, bytes)
        }
        for ((version, flags) in listOf(3 to 0x80, 4 to 4)) {
            val ignored = frame("MLLT", ByteArray(80), version, flags = flags) + frame("TXXX", gainBody(), version)
            assertGain(play(tag(ignored, version), Id3FrameLimits(references = 1)))
        }
    }

    @Test fun nativeIgnoredTopLevelDamageStaysIgnoredButNestedParentCrossingRefuses() {
        val damaged = "TXXX".toByteArray() + ByteBuffer.allocate(4).putInt(64).array() + byteArrayOf(0, 0, 0, 1, 2)
        val ignored = play(tag(damaged))
        assertEquals(MimeTypes.AUDIO_MPEG, ignored.format?.sampleMimeType)
        assertNull(ignored.format?.metadata)
        val nested = frame("CHAP", chapterPrefix() + "ML".toByteArray()) + frame("XXXX", ByteArray(32))
        val refusal = assertThrows(StreamMetadataRefusal::class.java) { play(tag(nested)) }
        assertTrue(refusal.contentIsMalformed)
        val unsupportedTags = tag(ByteArray(0), 9) + tag(ByteArray(0), 9) + tag(frame("TXXX", gainBody()))
        assertThrows(StreamMetadataRefusal::class.java) { play(unsupportedTags, Id3FrameLimits(tags = 2)) }
        assertGain(play(unsupportedTags, Id3FrameLimits(tags = 3)))
    }

    @Test fun interruptedBodyPeekCannotCertifyAChangedRetryBeforeNativeDecode() {
        val bounded = wrapped(Id3FrameLimits(references = 32))
        val failed = object : ForwardingExtractorInput(source(tag(frame("MLLT", mllt(4))))) {
            override fun peekFully(target: ByteArray, offset: Int, length: Int) {
                if (offset == 10 && target.size == length + 10) throw IOException("Injected body retry")
                super.peekFully(target, offset, length)
            }
        }
        try {
            bounded.init(Capture())
            assertThrows(IOException::class.java) { bounded.sniff(failed) }
            val changed = source(tag(frame("MLLT", mllt(16))))
            assertThrows(StreamMetadataRefusal::class.java) { bounded.sniff(changed) }
        } finally { bounded.release() }
    }

    private fun wrapped(limits: Id3FrameLimits) = MetadataBoundedExtractor(
        DefaultExtractorsFactory().createExtractors().single { it is Mp3Extractor }, id3Limits = limits)
    private fun play(tag: ByteArray, limits: Id3FrameLimits = Id3FrameLimits()): Capture {
        val bounded = wrapped(limits)
        val out = Capture()
        try {
            bounded.init(out)
            val input = source(tag)
            assertTrue(bounded.sniff(input))
            repeat(12) { if (out.format == null) bounded.read(input, PositionHolder()) }
            assertNotNull(out.format)
            return out
        } finally { bounded.release() }
    }
    private fun entries(out: Capture) = requireNotNull(out.format?.metadata).let { metadata -> (0 until metadata.length()).map { metadata.get(it) } }
    private fun assertGain(out: Capture) {
        assertEquals(listOf("-8 dB"), entries(out).filterIsInstance<TextInformationFrame>()
            .single { it.description == "REPLAYGAIN_TRACK_GAIN" }.values)
    }
    private fun mllt(packed: Int) = byteArrayOf(0, 1, 0, 0, 1, 0, 0, 1, 1, 1) + ByteArray(packed)
    private fun chapterPrefix() = byteArrayOf('c'.code.toByte(), 0) + ByteBuffer.allocate(16).putInt(0).putInt(1000).putInt(-1).putInt(-1).array()
    private fun gainBody() = byteArrayOf(0) + "REPLAYGAIN_TRACK_GAIN\u0000-8 dB\u0000".toByteArray()
    private fun sync(value: Int) = ByteArray(4) { i -> ((value ushr ((3 - i) * 7)) and 127).toByte() }
    private fun tag(frames: ByteArray, version: Int = 3, flags: Int = 0) = "ID3".toByteArray() +
        byteArrayOf(version.toByte(), 0, flags.toByte()) + sync(frames.size) + frames
    private fun frame(id: String, body: ByteArray, version: Int = 3, flags: Int = 0, rawV4Size: Boolean = false): ByteArray {
        val size = if (version == 2) ByteArray(3) { i -> (body.size ushr ((2 - i) * 8)).toByte() }
            else if (version == 4 && !rawV4Size) sync(body.size) else ByteBuffer.allocate(4).putInt(body.size).array()
        return id.toByteArray() + size + (if (version == 2) byteArrayOf() else byteArrayOf((flags ushr 8).toByte(), flags.toByte())) + body
    }
    private fun source(tag: ByteArray): ExtractorInput {
        val mpeg = ByteArray(417).also { byteArrayOf(-1, 0xfb.toByte(), 0x90.toByte(), 0).copyInto(it) }
        val bytes = tag + mpeg + mpeg + mpeg + mpeg
        var at = 0
        val reader = DataReader { target, offset, length ->
            if (length == 0) 0 else if (at >= bytes.size) C.RESULT_END_OF_INPUT else
                minOf(length, bytes.size - at).also { bytes.copyInto(target, offset, at, at + it); at += it }
        }
        return DefaultExtractorInput(reader, 0, bytes.size.toLong())
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
}
