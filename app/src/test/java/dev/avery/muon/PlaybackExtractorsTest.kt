@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.DataReader
import androidx.media3.extractor.*
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.mp3.Mp3Extractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackExtractorsTest {
    @Test fun pinnedPeekerSearchLimitDoesNotBoundItsDeclaredTagAllocation() {
        val declared = 1024 * 1024 // Controlled1MiB probe; no large payload or OOM attempt.
        val actual = input(id3Header(declared))
        var allocated = 0
        val observing = object : ForwardingExtractorInput(actual) {
            override fun peekFully(target: ByteArray, offset: Int, length: Int) {
                allocated = target.size
                throw IOException("Probe stopped before reading any tag body")
            }
        }
        assertThrows(IOException::class.java) { Id3Peeker().peekId3Data(observing, null, 128 * 1024) }
        assertEquals(declared + 10, allocated)
        assertEquals(0L, actual.position)
    }

    @Test fun oversizedOrInvalidLeadingTagsRefuseBeforeEitherDelegateEntryPoint() {
        for (reading in listOf(false, true)) {
            val marker = MarkerExtractor()
            val wrapped = MetadataBoundedExtractor(marker)
            val bytes = id3Header(STREAM_ID3_BYTES + 1)
            val source = input(bytes)
            val error = assertThrows(StreamMetadataRefusal::class.java) {
                if (reading) wrapped.read(source, PositionHolder()) else wrapped.sniff(source)
            }
            assertEquals(0, marker.entered)
            assertEquals(0L, source.position)
            assertEquals(0L, source.peekPosition)
            assertFalse(error.contentIsMalformed)
            assertEquals(error.message, friendlyError(error))
            assertFalse(error.message.contains("dataType"))
            val malformed = id3Header(0).also { it[9] = 0x80.toByte() }
            assertTrue(assertThrows(StreamMetadataRefusal::class.java) { wrapped.sniff(input(malformed)) }.contentIsMalformed)
        }
    }

    @Test fun cumulativeTagsAndMp3JunkSearchCannotHideAnOversizedDeclaration() {
        val limit = 1024
        val chained = id3Header(600) + ByteArray(600) + id3Header(600)
        val hidden = ByteArray(13) + id3Header(limit + 1)
        for (bytes in listOf(chained, hidden)) {
            val source = input(bytes)
            assertThrows(StreamMetadataRefusal::class.java) {
                MetadataBoundedExtractor(Mp3Extractor(), limit).sniff(source)
            }
            assertEquals(0L, source.position)
            assertEquals(0L, source.peekPosition)
        }
    }

    @Test fun normalMp3StillPublishesItsId3FormatAndFlacSniffingRemainsReadable() {
        val body = byteArrayOf(0) + "REPLAYGAIN_TRACK_GAIN\u0000-8 dB\u0000".toByteArray(Charsets.ISO_8859_1)
        val frame = "TXXX".toByteArray() + byteArrayOf(0, 0, 0, body.size.toByte(), 0, 0) + body
        val tag = id3Header(frame.size) + frame
        val mpegFrame = ByteArray(417).also {
            byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0).copyInto(it)
        }
        val source = input(tag + mpegFrame + mpegFrame + mpegFrame + mpegFrame)
        val mp3 = MetadataBoundedExtractor(Mp3Extractor())
        var format: Format? = null
        mp3.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
                override fun format(value: Format) { format = value }
            }
            override fun endTracks() {}
            override fun seekMap(map: SeekMap) {}
        })
        assertTrue(mp3.sniff(source))
        repeat(8) { if (format == null) mp3.read(source, PositionHolder()) }
        assertEquals(MimeTypes.AUDIO_MPEG, format!!.sampleMimeType)
        val tags = format!!.metadata!!
        val gain = (0 until tags.length()).map { tags.get(it) }.filterIsInstance<TextInformationFrame>()
            .single { it.description == "REPLAYGAIN_TRACK_GAIN" }
        assertEquals(listOf("-8 dB"), gain.values)
        mp3.release()
        assertTrue(MetadataBoundedExtractor(FlacExtractor()).sniff(input("fLaC".toByteArray() + ByteArray(12))))
    }

    @Test fun factoriesPreserveOrderAndUnderlyingIdentityAndNonzeroReadsStillDelegate() {
        val plain = DefaultExtractorsFactory()
        val guarded = PlaybackExtractors()
        for (uri in listOf<Uri?>(null, Uri.parse("http://127.0.0.1/song.mp3"))) {
            val before = if (uri == null) plain.createExtractors() else plain.createExtractors(uri, emptyMap())
            val after = if (uri == null) guarded.createExtractors() else guarded.createExtractors(uri, emptyMap())
            assertEquals(before.map { it.underlyingImplementation.javaClass }, after.map { it.underlyingImplementation.javaClass })
            assertTrue(after.all { it is MetadataBoundedExtractor })
        }
        val marker = MarkerExtractor()
        val wrapped = MetadataBoundedExtractor(marker)
        val atRange = DefaultExtractorInput(Reader(id3Header(STREAM_ID3_BYTES + 1)), 7, C.LENGTH_UNSET.toLong())
        wrapped.read(atRange, PositionHolder())
        assertSame(marker, wrapped.underlyingImplementation)
        // A non-MP3 candidate must not scan arbitrary payload bytes for an ID3-looking sequence.
        assertTrue(wrapped.sniff(input("RIFFpayload".toByteArray() + id3Header(STREAM_ID3_BYTES + 1))))
        wrapped.seek(7, 0); wrapped.release()
        assertEquals(4, marker.entered)
    }

    private fun input(bytes: ByteArray) = DefaultExtractorInput(Reader(bytes), 0, bytes.size.toLong())
    private fun id3Header(bytes: Int) = "ID3".toByteArray() + byteArrayOf(3, 0, 0,
        (bytes ushr 21 and 127).toByte(), (bytes ushr 14 and 127).toByte(),
        (bytes ushr 7 and 127).toByte(), (bytes and 127).toByte())
    private class Reader(private val bytes: ByteArray) : DataReader {
        private var position = 0
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(target, offset, position, position + count); position += count
            return count
        }
    }
    private class MarkerExtractor : Extractor {
        var entered = 0
        override fun sniff(input: ExtractorInput): Boolean { entered++; return true }
        override fun init(output: ExtractorOutput) {}
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int { entered++; return C.RESULT_END_OF_INPUT }
        override fun seek(position: Long, timeUs: Long) { entered++ }
        override fun release() { entered++ }
    }
}
