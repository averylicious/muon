@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ReplayGainMetadataTest {
    @Test fun pinnedId3DecoderExposesRsgainScalarTagsForBothSupportedId3Versions() {
        for (version in listOf(3, 4)) {
            val data = id3(version, "replaygain_track_gain" to "-2.00 dB", "REPLAYGAIN_TRACK_PEAK" to "2.0")
            val decoded = Id3Decoder().decode(data, data.size)!!
            assertEquals(2, decoded.length())
            assertTrue((0 until decoded.length()).all { decoded.get(it) is TextInformationFrame })
            val loudness = trackLoudness(decoded)!!
            assertEquals(TrackLoudness(-2f, 2f), loudness)
            // ID3 takes the same no-amplification/peak-protection path as FLAC comments.
            assertEquals(-6.0206f, appliedGainDb(loudness), 0.0001f)
            assertEquals(volumeForGain(-6.0206f), volumeForGain(appliedGainDb(loudness)), 0.0001f)
        }
    }

    @Test fun unrelatedAmbiguousOrDamagedId3FramesDoNotInventTrackLoudness() {
        val unrelated = Metadata(
            TextInformationFrame("TIT2", "REPLAYGAIN_TRACK_GAIN", listOf("-8 dB")),
            TextInformationFrame("TXXX", "REPLAYGAIN_ALBUM_GAIN", listOf("-8 dB")),
            TextInformationFrame("TXXX", "R128_TRACK_GAIN", listOf("-1280")),
            TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_GAIN", listOf("-8 dB", "-18 dB")))
        assertNull(trackLoudness(unrelated))
        for (value in listOf("NaN", "Infinity", "-90 dB", "not a gain")) {
            assertNull(trackLoudness(Metadata(TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_GAIN", listOf(value)))))
        }
        val positive = trackLoudness(Metadata(
            TextInformationFrame("TXXX", "replaygain_track_gain", listOf("+3 dB")),
            TextInformationFrame("TXXX", "replaygain_track_peak", listOf("Infinity"))))!!
        assertEquals(TrackLoudness(3f, null), positive)
        assertEquals(0f, appliedGainDb(positive), 0f)
    }

    @Test fun caseInsensitiveXiphCommentsKeepTrackGainAndR128Precedence() {
        val comments = Metadata(VorbisComment("replaygain_track_gain", "-11 dB"),
            VorbisComment("ReplayGain_Track_Peak", "0.9"), VorbisComment("r128_track_gain", "-1280"))
        assertEquals(TrackLoudness(-11f, 0.9f), trackLoudness(comments))
        assertEquals(TrackLoudness(0f), trackLoudness(Metadata(VorbisComment("r128_track_gain", "-1280"))))
        assertNull(trackLoudness(Metadata(VorbisComment("TITLE", "Song"))))
    }

    // Real ID3v2.3/v2.4 byte input to the pinned decoder, not hand-created decoded ReplayGain frames.
    // rsgain's scalar TXXX layout is encoding, description, terminator, value, terminator.
    private fun id3(version: Int, vararg tags: Pair<String, String>): ByteArray {
        val frames = tags.fold(byteArrayOf()) { all, (key, value) ->
            val body = byteArrayOf(0) + key.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
                value.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
            val size = if (version == 4) syncSafe(body.size) else byteArrayOf(
                (body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte())
            all + "TXXX".toByteArray(Charsets.US_ASCII) + size + byteArrayOf(0, 0) + body
        }
        return "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(version.toByte(), 0, 0) + syncSafe(frames.size) + frames
    }
    private fun syncSafe(value: Int) = byteArrayOf((value ushr 21 and 127).toByte(),
        (value ushr 14 and 127).toByte(), (value ushr 7 and 127).toByte(), (value and 127).toByte())
}
