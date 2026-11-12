@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.os.Bundle
import android.os.Parcel
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Modest synthetic boundary witnesses, not Binder transactions, audio decoding or a phone crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MetadataTransportBoundaryTest {
    @Test fun nativeAviStreamNameRemainsInThePublicTracksBundle() {
        val label = "L".repeat(2 * 1024 * 1024)
        val original = requireNotNull(javaClass.getResourceAsStream("/metadata-transport/control.avi")).use { it.readBytes() }
        val hdrlEnd = 20 + int(original, 16)
        var list = 24
        while (String(original, list, 4, Charsets.US_ASCII) != "LIST" ||
            String(original, list + 8, 4, Charsets.US_ASCII) != "strl") {
            list += 8 + int(original, list + 4)
            check(list + 12 <= hdrlEnd)
        }
        val end = list + 8 + int(original, list + 4)
        val added = "strn".toByteArray(Charsets.US_ASCII) + le(label.length) + label.toByteArray(Charsets.US_ASCII)
        val bytes = original.copyOfRange(0, end) + added + original.copyOfRange(end, original.size)
        put(bytes, list + 4, int(original, list + 4) + added.size)
        put(bytes, 16, int(original, 16) + added.size)
        put(bytes, 4, int(original, 4) + added.size)
        var at = 0
        val reader = DataReader { target, offset, length ->
            if (length == 0) 0 else if (at == bytes.size) C.RESULT_END_OF_INPUT
            else minOf(length, bytes.size - at).also { bytes.copyInto(target, offset, at, at + it); at += it }
        }
        val input = DefaultExtractorInput(reader, 0, bytes.size.toLong())
        val extractor = DefaultExtractorsFactory().createExtractors().single { it is AviExtractor }
        var format: Format? = null
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
                override fun format(value: Format) { format = value }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        try {
            val holder = PositionHolder()
            repeat(8) { if (format == null) extractor.read(input, holder) }
            val actual = requireNotNull(format)
            assertEquals(MimeTypes.AUDIO_RAW, actual.sampleMimeType)
            assertEquals(label, actual.label)
            val tracks = Tracks(listOf(Tracks.Group(TrackGroup(actual), false,
                intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
            val bundle = tracks.toBundle()
            assertEquals(label, Tracks.fromBundle(bundle).groups.single().mediaTrackGroup.getFormat(0).label)
            assertTrue("Public Tracks payload is not chunked by this API", parcelBytes(bundle) > 1024 * 1024)
        } finally { extractor.release() }
    }

    @Test fun codecInitializationAndPopulatedTextAreInlineButRawMetadataIsOmitted() {
        val text = "C".repeat(2 * 1024 * 1024)
        val frame = TextInformationFrame("TCOM", null, listOf(text))
        val metadata = MediaMetadata.Builder().also(frame::populateMediaMetadata).build()
        assertEquals(text, metadata.composer)
        assertTrue(parcelBytes(metadata.toBundle()) > 1024 * 1024)
        val codec = ByteArray(2 * 1024 * 1024) { 1 }
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_VORBIS)
            .setInitializationData(listOf(codec)).setMetadata(Metadata(frame)).build()
        val bundle = format.toBundle()
        val roundTrip = Format.fromBundle(bundle)
        assertNull("Raw frame objects are deliberately not serialized", roundTrip.metadata)
        assertArrayEquals(codec, roundTrip.initializationData.single())
        assertTrue(parcelBytes(bundle) > 1024 * 1024)
    }

    private fun parcelBytes(bundle: Bundle): Int {
        val parcel = Parcel.obtain()
        return try { parcel.writeBundle(bundle); parcel.dataSize() } finally { parcel.recycle() }
    }
    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun int(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun put(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value) }
}
