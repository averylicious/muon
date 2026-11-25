@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AudioOnlyPlaybackTest {
    private fun capabilities(type: Int) = object : RendererCapabilities {
        override fun getName() = "Controlled track type $type"
        override fun getTrackType() = type
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if (MimeTypes.getTrackType(format.sampleMimeType) == type) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

    @Test fun actualSelectorKeepsPreferredAudioAndTagsWhileSuppressingVisualOverrides() {
        val context = RuntimeEnvironment.getApplication()
        for ((mime, renderer) in listOf(MimeTypes.VIDEO_H265 to 1, MimeTypes.IMAGE_JPEG to 2)) {
            val english = TrackGroup("english", Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setLanguage("en").setChannelCount(2).setSampleRate(48_000).build())
            val japanese = TrackGroup("japanese", Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setLanguage("ja").setChannelCount(2).setSampleRate(48_000).build())
            val visual = TrackGroup("visual", Format.Builder().setSampleMimeType(mime).setWidth(32).setHeight(32).build())
            val tags = TrackGroup("tags", Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build())
            val groups = TrackGroupArray(english, japanese, visual, tags)
            val outputs = arrayOf(capabilities(C.TRACK_TYPE_AUDIO), capabilities(C.TRACK_TYPE_VIDEO),
                capabilities(C.TRACK_TYPE_IMAGE), capabilities(C.TRACK_TYPE_METADATA))
            val selector = DefaultTrackSelector(context)
            selector.init({}, DefaultBandwidthMeter.Builder(context).build())
            try {
                // A prior explicit visual override must not defeat the new disabled-type policy.
                val original = selector.parameters.buildUpon().setPreferredAudioLanguage("ja")
                    .addOverride(TrackSelectionOverride(visual, listOf(0))).build()
                selector.onParametersActivated(original)
                val before = selector.selectTracks(outputs, groups, MediaPeriodId("fixture"), Timeline.EMPTY)
                assertNotNull(before.selections[renderer])
                assertEquals(japanese, before.selections[0]?.trackGroup)
                assertEquals(tags, before.selections[3]?.trackGroup)

                selector.onParametersActivated(audioOnlyPlaybackParameters(original))
                val after = selector.selectTracks(outputs, groups, MediaPeriodId("fixture"), Timeline.EMPTY)
                assertEquals(japanese, after.selections[0]?.trackGroup)
                assertNotNull(after.rendererConfigurations[0])
                assertEquals(tags, after.selections[3]?.trackGroup)
                for (index in listOf(1, 2)) {
                    assertNull(after.selections[index])
                    assertNull(after.rendererConfigurations[index])
                }
                val visualOnly = selector.selectTracks(outputs, TrackGroupArray(visual), MediaPeriodId("visual-only"), Timeline.EMPTY)
                assertTrue(visualOnly.selections.all { it == null })
                assertTrue(visualOnly.rendererConfigurations.all { it == null })
            } finally { selector.release() }
        }
    }
}
