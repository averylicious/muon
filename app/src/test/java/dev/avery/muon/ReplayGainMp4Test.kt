@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ReplayGainMp4Test {
    @Test fun realM4aDeliversRsgainNamespaceAndKeepsArtworkAndPeakProtection() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/replaygain/tagged-gain.m4a")).use { it.readBytes() }
        fun reader(start: Long): DataReader {
            var at = start.toInt()
            return DataReader { target, offset, length ->
                if (length == 0) 0 else if (at >= bytes.size) C.RESULT_END_OF_INPUT
                else minOf(length, bytes.size - at).also { bytes.copyInto(target, offset, at, at + it); at += it }
            }
        }
        var format: Format? = null
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
                override fun format(value: Format) { format = value }
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        val uri = Uri.parse("http://fixture.invalid/track.m4a")
        val adapter = BundledExtractorsAdapter(DefaultExtractorsFactory())
        try {
            adapter.init(reader(0), uri, emptyMap(), 0, bytes.size.toLong(), output)
            val holder = PositionHolder(); var done = false; var reads = 0
            while (!done) {
                check(reads++ < 1000) { "Fixture did not finish" }
                when (adapter.read(holder)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_SEEK -> adapter.init(reader(holder.position), uri, emptyMap(), holder.position, bytes.size.toLong(), output)
                    else -> done = true
                }
            }
            assertEquals(MimeTypes.AUDIO_AAC, format?.sampleMimeType)
            val metadata = requireNotNull(format?.metadata)
            val entries = (0 until metadata.length()).map { metadata.get(it) }
            assertTrue(entries.filterIsInstance<InternalFrame>().any {
                it.domain == "com.apple.iTunes" && it.description == "replaygain_track_gain" && it.text == "-2.00 dB"
            })
            assertTrue(entries.filterIsInstance<ApicFrame>().single().pictureData.isNotEmpty())
            val loudness = requireNotNull(trackLoudness(metadata))
            assertEquals(TrackLoudness(-2f, 2f), loudness)
            assertEquals(-6.0206f, appliedGainDb(loudness), 0.0001f)
        } finally { adapter.release() }
    }

    @Test fun onlyRecognizedMp4TrackFieldsUseTheExistingFiniteAndNoAmplificationRules() {
        val unrelated = Metadata(InternalFrame("other.example", "replaygain_track_gain", "-8 dB"),
            InternalFrame("com.apple.iTunes", "replaygain_album_gain", "-12 dB"),
            InternalFrame("com.apple.iTunes", "R128_TRACK_GAIN", "-1280"),
            InternalFrame("com.apple.iTunes", "x".repeat(4096), "-8 dB"))
        assertNull(trackLoudness(unrelated))
        for (value in listOf("NaN", "Infinity", "-90 dB", "not a gain"))
            assertNull(trackLoudness(Metadata(InternalFrame("com.apple.iTunes", "REPLAYGAIN_TRACK_GAIN", value))))
        val gain = requireNotNull(trackLoudness(Metadata(
            InternalFrame("com.apple.iTunes", "ReplayGain_Track_Gain", "+3 dB"),
            InternalFrame("com.apple.iTunes", "ReplayGain_Track_Peak", "Infinity"))))
        assertEquals(TrackLoudness(3f, null), gain)
        assertEquals(1f, volumeForGain(appliedGainDb(gain)), 0f)
    }
}
