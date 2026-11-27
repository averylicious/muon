@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.avif.AvifExtractor
import androidx.media3.extractor.bmp.BmpExtractor
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.flv.FlvExtractor
import androidx.media3.extractor.heif.HeifExtractor
import androidx.media3.extractor.jpeg.JpegExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.png.PngExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.Ac4Extractor
import androidx.media3.extractor.wav.WavExtractor
import androidx.media3.extractor.webp.WebpExtractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ConnectException

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

    @Test fun audioExtractorsFactoryDropsVideoImageAndAc4WhilePreservingAudioOrder() {
        val excluded = setOf<Class<out Extractor>>(
            AviExtractor::class.java, FlvExtractor::class.java,
            JpegExtractor::class.java, PngExtractor::class.java, WebpExtractor::class.java,
            BmpExtractor::class.java, HeifExtractor::class.java, AvifExtractor::class.java,
            Ac4Extractor::class.java,
        )
        val defaultClasses = DefaultExtractorsFactory().createExtractors()
            .map { it.underlyingImplementation.javaClass }
        val filteredClasses = audioExtractorsFactory().createExtractors()
            .map { it.underlyingImplementation.javaClass }

        assertEquals(defaultClasses.filterNot { it in excluded }, filteredClasses)
        assertTrue(filteredClasses.none { it in excluded })
        for (expected in listOf(
            FlacExtractor::class.java, Mp3Extractor::class.java, OggExtractor::class.java,
            Mp4Extractor::class.java, FragmentedMp4Extractor::class.java,
            WavExtractor::class.java, MatroskaExtractor::class.java,
        )) {
            assertTrue("Expected $expected to remain enabled", expected in filteredClasses)
        }
        // URI/header hints for excluded formats still produce none of the excluded extractors.
        for (hint in listOf("clip.avi", "clip.flv", "cover.jpg", "cover.heic", "audio.ac4")) {
            val hinted = audioExtractorsFactory().createExtractors(Uri.parse("http://fixture.invalid/$hint"), emptyMap())
                .map { it.underlyingImplementation.javaClass }
            assertTrue("Expected no excluded extractor for $hint", hinted.none { it in excluded })
        }
    }

    @Test fun audioExtractorsFactoryUnwrapsDelegatesAndPreservesUnsupportedSubtitles() {
        val wrappedExcluded = object : Extractor {
            private val inner = AviExtractor()
            override fun sniff(input: ExtractorInput) = false
            override fun init(output: ExtractorOutput) = Unit
            override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
            override fun seek(position: Long, timeUs: Long) = Unit
            override fun release() = Unit
            override fun getUnderlyingImplementation(): Extractor = inner
        }
        val custom = audioExtractorsFactory(ExtractorsFactory { arrayOf(wrappedExcluded, FlacExtractor()) })
        assertEquals(listOf(FlacExtractor::class.java), custom.createExtractors().map { it.javaClass })

        val factory = audioExtractorsFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(DataSource.Factory { error("No I/O expected") }, factory)
            .setSubtitleParserFactory(SubtitleParser.Factory.UNSUPPORTED)
        mediaSourceFactory.createMediaSource(MediaItem.fromUri("http://fixture.invalid/control.mka"))
        val mkv = factory.createExtractors().single { it.underlyingImplementation is MatroskaExtractor }
        try {
            val parser = MatroskaExtractor::class.java.getDeclaredField("subtitleParserFactory")
                .also { it.isAccessible = true }.get(mkv.underlyingImplementation)
            assertSame(SubtitleParser.Factory.UNSUPPORTED, parser)
        } finally { mkv.release() }
    }

    @Test fun friendlyErrorAndPlaybackErrorMessageMapParserAndLoaderFailures() {
        val malformed = ParserException.createForMalformedContainer("internal box size 999999", null)
        val oom = Loader.UnexpectedLoaderException(OutOfMemoryError("heap exhausted"))
        assertEquals(UNREADABLE_AUDIO_FILE, friendlyError(malformed))
        assertEquals(UNREADABLE_AUDIO_FILE, friendlyError(oom))

        val parsingError = PlaybackException(
            "Source error", malformed, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        )
        assertEquals(
            "ERROR_CODE_PARSING_CONTAINER_MALFORMED: $UNREADABLE_AUDIO_FILE",
            playbackErrorMessage(parsingError),
        )
        // Binder serialization turns ParserException into RemoteException; errorCode still maps cleanly.
        val roundTrippedParsing = PlaybackException.fromBundle(parsingError.toBundle())
        assertEquals(
            "ERROR_CODE_PARSING_CONTAINER_MALFORMED: $UNREADABLE_AUDIO_FILE",
            playbackErrorMessage(roundTrippedParsing),
        )

        val loaderError = PlaybackException("Source error", oom, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals("ERROR_CODE_IO_UNSPECIFIED: $UNREADABLE_AUDIO_FILE", playbackErrorMessage(loaderError))
        val roundTrippedLoader = PlaybackException.fromBundle(loaderError.toBundle())
        assertEquals("ERROR_CODE_IO_UNSPECIFIED: $UNREADABLE_AUDIO_FILE", playbackErrorMessage(roundTrippedLoader))

        val networkError = PlaybackException(
            "Source error", ConnectException("ECONNREFUSED"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        assertTrue(playbackErrorMessage(networkError).contains("Cannot reach Tauon."))
    }
}
