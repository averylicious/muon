package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.avif.AvifExtractor
import androidx.media3.extractor.bmp.BmpExtractor
import androidx.media3.extractor.flv.FlvExtractor
import androidx.media3.extractor.heif.HeifExtractor
import androidx.media3.extractor.jpeg.JpegExtractor
import androidx.media3.extractor.png.PngExtractor
import androidx.media3.extractor.ts.Ac4Extractor
import androidx.media3.extractor.webp.WebpExtractor

/** Muon displays its own song artwork, not Media3 video/image output. Keep those decoders
 * unselected while retaining audio preferences, tags and the caller's other track policy.
 * Extractors still read container/codec metadata; this is not their allocation/heap guard.
 */
internal fun audioOnlyPlaybackParameters(current: TrackSelectionParameters): TrackSelectionParameters =
    current.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        .setTrackTypeDisabled(C.TRACK_TYPE_IMAGE, true)
        .build()

@UnstableApi
private val EXCLUDED_EXTRACTORS: Set<Class<out Extractor>> = setOf(
    AviExtractor::class.java,
    FlvExtractor::class.java,
    JpegExtractor::class.java,
    PngExtractor::class.java,
    WebpExtractor::class.java,
    BmpExtractor::class.java,
    HeifExtractor::class.java,
    AvifExtractor::class.java,
    Ac4Extractor::class.java,
)

/**
 * Excludes non-music video/image and AC-4 extractors via Media3's public
 * [ForwardingExtractorsFactory] and [Extractor.getUnderlyingImplementation] APIs while keeping all
 * remaining audio extractors in their default order.
 */
@UnstableApi
internal fun audioExtractorsFactory(
    delegate: ExtractorsFactory = DefaultExtractorsFactory(),
): ExtractorsFactory = object : ForwardingExtractorsFactory(delegate) {
    private fun filter(extractors: Array<Extractor>): Array<Extractor> =
        extractors.filterNot { it.underlyingImplementation.javaClass in EXCLUDED_EXTRACTORS }.toTypedArray()

    override fun createExtractors(): Array<Extractor> = filter(super.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        filter(super.createExtractors(uri, responseHeaders))
}
