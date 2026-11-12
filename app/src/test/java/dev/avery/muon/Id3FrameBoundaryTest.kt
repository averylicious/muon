@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.MlltFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.mp3.Mp3Extractor
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Modest real library allocations/objects, never an OOM/stack-pressure or audio/device probe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class Id3FrameBoundaryTest {
    @Test fun smallMlltBecomesTwoDeviationArraysAndTwoAdditionalDefaultMp3SeekArrays() {
        val packed = 4096
        // Source accepts one bit for each deviation; this is a library control, not a claim
        // about normative tag authoring or an encoded song. All target arrays remain modest.
        val body = byteArrayOf(0, 1, 0, 0, 1, 0, 0, 1, 1, 1) + ByteArray(packed)
        val tag = tag(frame("MLLT", body))
        val mpeg = ByteArray(417).also { byteArrayOf(-1, 0xfb.toByte(), 0x90.toByte(), 0).copyInto(it) }
        val bytes = tag + mpeg + mpeg + mpeg + mpeg
        val raw = DefaultExtractorsFactory().createExtractors().single { it is Mp3Extractor }
        val output = Capture()
        val input = input(bytes)
        try {
            raw.init(output)
            assertTrue(raw.sniff(input))
            val holder = PositionHolder()
            repeat(12) { if (output.format == null) assertNotEquals(Extractor.RESULT_SEEK, raw.read(input, holder)) }
            assertEquals(MimeTypes.AUDIO_MPEG, output.format?.sampleMimeType)
            val mllt = requireNotNull(output.format?.metadata).entries().filterIsInstance<MlltFrame>().single()
            val references = packed * 8 / 2
            assertEquals(references, mllt.bytesDeviations.size)
            assertEquals(references, mllt.millisecondsDeviations.size)
            val seeker = requireNotNull(output.map)
            assertEquals("MlltSeeker", seeker.javaClass.simpleName)
            for (name in listOf("referencePositions", "referenceTimesMs")) {
                val field = seeker.javaClass.getDeclaredField(name).also { it.isAccessible = true }
                assertEquals(references + 1, (field.get(seeker) as LongArray).size)
            }
            assertTrue(tag.size < 4200)
        } finally { raw.release() }
    }

    @Test fun backedShortChaptersAreRecursivelyDecodedWithoutAnExplicitDepthAdmission() {
        var bytes = frame("TIT2", byteArrayOf(0) + "Leaf".toByteArray())
        repeat(12) { index ->
            val fixed = ByteBuffer.allocate(16).putInt(0).putInt(1000).putInt(-1).putInt(-1).array()
            bytes = frame("CHAP", "c$index\u0000".toByteArray() + fixed + bytes)
        }
        val metadata = requireNotNull(Id3Decoder().decode(tag(bytes), tag(bytes).size))
        var item = metadata.get(0)
        repeat(12) {
            val chapter = item as ChapterFrame
            assertEquals(1, chapter.getSubFrameCount())
            item = chapter.getSubFrame(0)
        }
        assertEquals(listOf("Leaf"), (item as TextInformationFrame).values)
        assertTrue(bytes.size < 500)
    }

    @Test fun aBackedSmallTextFrameCanRetainManySeparateStringValues() {
        val values = "a\u0000".repeat(1024).toByteArray()
        val bytes = tag(frame("TPE1", byteArrayOf(0) + values))
        val metadata = requireNotNull(Id3Decoder().decode(bytes, bytes.size))
        val artists = metadata.get(0) as TextInformationFrame
        assertEquals(1024, artists.values.size)
        assertTrue(artists.values.all { it == "a" })
        assertTrue(bytes.size < 2100)
    }

    private fun Metadata.entries() = (0 until length()).map { get(it) }
    private fun tag(frames: ByteArray) = "ID3".toByteArray() + byteArrayOf(3, 0, 0) +
        ByteArray(4) { i -> ((frames.size ushr ((3 - i) * 7)) and 127).toByte() } + frames
    private fun frame(id: String, body: ByteArray) = id.toByteArray() + ByteBuffer.allocate(4).putInt(body.size).array() +
        byteArrayOf(0, 0) + body
    private fun input(bytes: ByteArray): DefaultExtractorInput {
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
