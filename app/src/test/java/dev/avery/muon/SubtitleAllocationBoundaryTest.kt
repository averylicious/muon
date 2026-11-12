@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.pgs.PgsParser
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Modest native PGS growth witnesses for embedded Matroska text; no image/OOM/phone probe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SubtitleAllocationBoundaryTest {
    private fun parser() = DefaultSubtitleParserFactory().create(
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build()).also {
        assertTrue(it is PgsParser)
    } as PgsParser

    @Test fun actualDefaultPgsParserInflatesSmallEncodedSampleIntoLargerRetainedBuffer() {
        // Four unknown-but-well-sized sections avoid a large parsing loop or bitmap output.
        val section = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte()) + ByteArray(65_535)
        val payload = section + section + section + section
        val encoded = ByteArrayOutputStream()
        val deflater = Deflater()
        try {
            deflater.setInput(payload); deflater.finish()
            val scratch = ByteArray(1024)
            while (!deflater.finished()) encoded.write(scratch, 0, deflater.deflate(scratch))
        } finally { deflater.end() }
        val bytes = encoded.toByteArray()
        assertTrue(bytes.size < 1024)
        val actual = parser()
        var deliveries = 0
        actual.parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) {
            assertTrue(it.cues.isEmpty()); deliveries++
        }
        val inflated = PgsParser::class.java.getDeclaredField("inflatedBuffer").also { it.isAccessible = true }
            .get(actual) as ParsableByteArray
        assertEquals(payload.size, inflated.limit())
        assertTrue(inflated.capacity() >= payload.size)
        assertEquals(1, deliveries)
        // Reflection observes the native scratch; no production private API or codec change.
    }

    @Test fun actualPgsBitmapSectionReservesItsDeclarationBeforePictureDataArrives() {
        // A base picture section declares32KiB bitmap data, but includes no picture bytes.
        val bytes = byteArrayOf(0x15, 0, 11, 0, 0, 0, 0x80.toByte(), 0, 0x80.toByte(), 4, 0, 1, 0, 1)
        val actual = parser()
        actual.parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) {
            assertTrue(it.cues.isEmpty())
        }
        val builder = PgsParser::class.java.getDeclaredField("cueBuilder").also { it.isAccessible = true }.get(actual)
        val bitmapData = builder.javaClass.getDeclaredField("bitmapData").also { it.isAccessible = true }
            .get(builder) as ParsableByteArray
        assertEquals(32 * 1024, bitmapData.limit())
        assertTrue(bitmapData.capacity() >= 32 * 1024)
        assertTrue(bytes.size < 32)
    }
}
