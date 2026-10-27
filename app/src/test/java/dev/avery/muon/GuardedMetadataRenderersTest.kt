@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.content.Context
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.metadata.MetadataDecoderFactory
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.extractor.metadata.MetadataDecoder
import androidx.media3.extractor.metadata.MetadataInputBuffer
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GuardedMetadataRenderersTest {
    @Test fun refusesDecodedCostAndUndeliveredTailBeforeDelegateWhileOtherFormatsPassThrough() {
        var calls = 0
        val decoder = MetadataDecoder { calls++; Metadata() }
        val actual = object : MetadataDecoderFactory {
            override fun supportsFormat(format: Format) = MetadataDecoderFactory.DEFAULT.supportsFormat(format)
            override fun createDecoder(format: Format): MetadataDecoder = decoder
        }
        val factory = GuardedMetadataDecoders(Id3FrameLimits(values = 1), actual)
        for (mime in listOf(MimeTypes.APPLICATION_ID3, MimeTypes.APPLICATION_EMSG, MimeTypes.APPLICATION_ICY,
            MimeTypes.APPLICATION_SCTE35, MimeTypes.APPLICATION_AIT, "application/unknown")) {
            val f = Format.Builder().setSampleMimeType(mime).build()
            assertEquals(MetadataDecoderFactory.DEFAULT.supportsFormat(f), factory.supportsFormat(f))
            if (mime != MimeTypes.APPLICATION_ID3) assertSame(decoder, factory.createDecoder(f))
        }
        val guarded = factory.createDecoder(id3Format())
        assertNull(guarded.decode(buffer(tag("a\u0000b\u0000"))))
        assertEquals(0, calls)
        // Capacity contains zeros from another use; only10 bytes were delivered this time.
        val missing = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, 32)
        assertNull(guarded.decode(buffer(missing, 128)))
        assertEquals(0, calls)
        assertNotNull(guarded.decode(buffer(tag("a\u0000"))))
        assertEquals(1, calls)
    }

    @Test fun nativeDecoderKeepsNormalTagPaddingAndCancellationDoesNotDecodeOrSwallowInterrupt() {
        val factory = GuardedMetadataDecoders()
        val decoder = factory.createDecoder(id3Format())
        val body = byteArrayOf(0) + "REPLAYGAIN_TRACK_GAIN\u0000-8 dB\u0000".toByteArray()
        val frames = "TXXX".toByteArray() + ByteBuffer.allocate(4).putInt(body.size).array() + byteArrayOf(0, 0) + body
        val raw = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + sync(frames.size) + frames + ByteArray(20)
        val metadata = requireNotNull(decoder.decode(buffer(raw, 256)))
        val gain = (0 until metadata.length()).map { metadata.get(it) }.filterIsInstance<TextInformationFrame>().single()
        assertEquals(listOf("-8 dB"), gain.values)
        val hadInterrupt = Thread.interrupted()
        try {
            Thread.currentThread().interrupt()
            assertNull(decoder.decode(buffer(tag("a\u0000"))))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted(); if (hadInterrupt) Thread.currentThread().interrupt() }
    }

    @Test fun actualMetadataSlotsKeepDefaultCountOrderAndUseGuardedDecoders() {
        val context = RuntimeEnvironment.getApplication()
        val output = MetadataOutput { }
        val looper = requireNotNull(Looper.getMainLooper())
        val marker = MetadataRenderer(output, looper)
        val expected = arrayListOf<Renderer>(marker)
        val actual = arrayListOf<Renderer>(marker)
        val parameterTypes = arrayOf(Context::class.java, MetadataOutput::class.java, Looper::class.java,
            Integer.TYPE, java.util.ArrayList::class.java)
        DefaultRenderersFactory::class.java.getDeclaredMethod("buildMetadataRenderers", *parameterTypes)
            .also { it.isAccessible = true }.invoke(DefaultRenderersFactory(context), context, output, looper, 0, expected)
        GuardedMetadataRenderers::class.java.getDeclaredMethod("buildMetadataRenderers", *parameterTypes)
            .also { it.isAccessible = true }.invoke(GuardedMetadataRenderers(context, Id3FrameLimits(values = 1)),
                context, output, looper, 0, actual)
        assertSame(marker, actual.first())
        assertTrue(actual.size > 1)
        assertEquals(expected.map { it.javaClass }, actual.map { it.javaClass })
        val field = MetadataRenderer::class.java.getDeclaredField("decoderFactory").also { it.isAccessible = true }
        for (renderer in actual.drop(1)) {
            val factory = field.get(renderer) as MetadataDecoderFactory
            assertTrue(factory is GuardedMetadataDecoders)
            assertNull(factory.createDecoder(id3Format()).decode(buffer(tag("a\u0000b\u0000"))))
        }
        // Reflection is test-only; no real player, audio/video sink or phone was created.
    }

    private fun id3Format() = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build()
    private fun tag(values: String): ByteArray {
        val body = byteArrayOf(0) + values.toByteArray()
        val frame = "TPE1".toByteArray() + ByteBuffer.allocate(4).putInt(body.size).array() + byteArrayOf(0, 0) + body
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + sync(frame.size) + frame
    }
    private fun sync(value: Int) = ByteArray(4) { i -> ((value ushr ((3 - i) * 7)) and 127).toByte() }
    private fun buffer(bytes: ByteArray, capacity: Int = bytes.size): MetadataInputBuffer = MetadataInputBuffer().apply {
        ensureSpaceForWrite(maxOf(bytes.size, capacity)); requireNotNull(data).put(bytes); flip()
    }
}
