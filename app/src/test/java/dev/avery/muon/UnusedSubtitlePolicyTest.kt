@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual pinned public factory/selection contracts used by the service; no service/audio/network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UnusedSubtitlePolicyTest {
    @Test fun sourceFactoryPropagatesUnsupportedParsersThroughTheSuppliedFactory() {
        var supplied: SubtitleParser.Factory? = null
        val extractors = object : ForwardingExtractorsFactory(DefaultExtractorsFactory()) {
            override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory {
                supplied = factory
                return super.setSubtitleParserFactory(factory) // Preserve native return semantics.
            }
        }
        val factory = DefaultMediaSourceFactory(DataSource.Factory { error("No I/O expected") }, extractors)
            .setSubtitleParserFactory(SubtitleParser.Factory.UNSUPPORTED)
        factory.createMediaSource(MediaItem.fromUri("http://fixture.invalid/control.mka"))
        assertSame(SubtitleParser.Factory.UNSUPPORTED, supplied)
        val mkv = extractors.createExtractors().single { it is MatroskaExtractor }
        try {
            // Observe actual configured native instance, not merely our capture variable.
            val parser = MatroskaExtractor::class.java.getDeclaredField("subtitleParserFactory")
                .also { it.isAccessible = true }.get(mkv)
            assertSame(SubtitleParser.Factory.UNSUPPORTED, parser)
        } finally { mkv.release() }
    }

    @Test fun actualTranscodingOutputPassesUnusedImageCaptionsAsRawBytesWithoutParsing() {
        val audio = DiscardingTrackOutput()
        var seenFormat: Format? = null
        var seenBytes = byteArrayOf()
        var commits = 0
        val text = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(format: Format) { seenFormat = format }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                seenBytes = ByteArray(length).also { data.readBytes(it, 0, length) }
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                assertEquals(123L, timeUs); assertEquals(seenBytes.size, size); commits++
            }
        }
        val output = SubtitleTranscodingExtractorOutput(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_AUDIO) audio else text
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        }, SubtitleParser.Factory.UNSUPPORTED)
        assertSame(audio, output.track(0, C.TRACK_TYPE_AUDIO))
        val raw = output.track(1, C.TRACK_TYPE_TEXT)
        raw.format(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build())
        output.endTracks()
        // If native PGS parsing ran, this tiny declaration reserves a derived32KiB buffer.
        val bytes = byteArrayOf(0x15, 0, 11, 0, 0, 0, 0x80.toByte(), 0, 0x80.toByte(), 4, 0, 1, 0, 1)
        raw.sampleData(ParsableByteArray(bytes), bytes.size)
        raw.sampleMetadata(123, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
        assertEquals(MimeTypes.APPLICATION_PGS, seenFormat?.sampleMimeType)
        assertArrayEquals(bytes, seenBytes)
        assertEquals(1, commits)
    }

    @Test fun actualSelectorKeepsAudioButDisablesAnOtherwiseSelectedRawTextTrack() {
        val context = RuntimeEnvironment.getApplication()
        val text = TextRenderer(object : TextOutput {}, Looper.getMainLooper())
        val caption = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).setLanguage("en")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        // The pinned renderer advertises this format despite legacy decoding being disabled.
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(text.supportsFormat(caption)))
        val audio = object : RendererCapabilities {
            override fun getName() = "Controlled audio capability"
            override fun getTrackType() = C.TRACK_TYPE_AUDIO
            override fun supportsFormat(format: Format) = RendererCapabilities.create(
                if (format.sampleMimeType == MimeTypes.AUDIO_AAC) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)
            override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
        }
        val groups = TrackGroupArray(TrackGroup("audio", Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setLanguage("en").setChannelCount(2).setSampleRate(48_000).build()), TrackGroup("caption", caption))
        val selector = DefaultTrackSelector(context)
        selector.init({}, DefaultBandwidthMeter.Builder(context).build())
        try {
            val capabilities = arrayOf<RendererCapabilities>(audio, text.capabilities)
            val before = selector.selectTracks(capabilities, groups, MediaPeriodId("fixture"), Timeline.EMPTY)
            assertNotNull(before.selections[0]); assertNotNull(before.selections[1])
            // Same supported parameter change as PlaybackService; activate on the selector as ExoPlayer does.
            val parameters = selector.parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            selector.onParametersActivated(parameters)
            val after = selector.selectTracks(capabilities, groups, MediaPeriodId("fixture"), Timeline.EMPTY)
            assertEquals(groups[0], after.selections[0]?.trackGroup)
            assertNull(after.selections[1]); assertNull(after.rendererConfigurations[1])
            assertNotNull(after.rendererConfigurations[0])
        } finally { selector.release() }
    }
}
