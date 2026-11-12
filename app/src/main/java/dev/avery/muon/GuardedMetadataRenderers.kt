@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.content.Context
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.metadata.MetadataDecoderFactory
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.extractor.metadata.MetadataDecoder
import java.io.InterruptedIOException

/** Supplemental ID3 is optional: refuse decoded resource cost by dropping metadata, not audio.
 * This runs after a sample buffer exists, so it does NOT bound that prior buffer allocation.
 */
internal class GuardedMetadataDecoders(
    limits: Id3FrameLimits = Id3FrameLimits(),
    private val actual: MetadataDecoderFactory = MetadataDecoderFactory.DEFAULT,
) : MetadataDecoderFactory {
    private val admission = Id3FrameAdmission(limits)
    override fun supportsFormat(format: Format) = actual.supportsFormat(format)
    override fun createDecoder(format: Format): MetadataDecoder {
        val decoder = actual.createDecoder(format)
        if (format.sampleMimeType != MimeTypes.APPLICATION_ID3) return decoder
        return MetadataDecoder { input ->
            val buffer = input.data
            // Keep the native decoder contract for invalid callers; don't copy arbitrary direct
            // or offset buffers. MetadataRenderer supplies an array with position/offset0.
            if (buffer == null || !buffer.hasArray() || buffer.position() != 0 || buffer.arrayOffset() != 0)
                return@MetadataDecoder decoder.decode(input)
            try {
                admission.admit(buffer.array(), buffer.limit())
            } catch (_: StreamMetadataRefusal) {
                return@MetadataDecoder null
            } catch (_: InterruptedIOException) {
                Thread.currentThread().interrupt()
                return@MetadataDecoder null
            }
            decoder.decode(input)
        }
    }
}

/** Preserve the pinned default slots/order and all audio/video/text/sink options. Replace only
 * its freshly constructed, not-yet-initialized MetadataRenderer slots using the public factory.
 * Default metadata output is not early; review that contract before changing the Media3 pin.
 */
internal class GuardedMetadataRenderers(context: Context, limits: Id3FrameLimits = Id3FrameLimits()) : DefaultRenderersFactory(context) {
    private val decoders = GuardedMetadataDecoders(limits)
    override fun buildMetadataRenderers(context: Context, output: MetadataOutput, outputLooper: Looper,
        extensionRendererMode: Int, out: ArrayList<Renderer>) {
        val first = out.size
        super.buildMetadataRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (i in first until out.size) if (out[i] is MetadataRenderer)
            out[i] = MetadataRenderer(output, outputLooper, decoders)
    }
}
