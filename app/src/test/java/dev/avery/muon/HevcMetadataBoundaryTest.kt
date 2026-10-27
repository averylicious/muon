@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.container.NalUnitUtil
import androidx.media3.container.ParsableNalUnitBitArray
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Safe-sized raw dependency controls, not a codec guard, huge allocation or device probe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HevcMetadataBoundaryTest {
    @Test fun layeredVpsHeaderReachesItsArrayBackedBodyBeforeMissingLayerFlagsFail() {
        val backed = vps(2, true)
        assertNotNull(NalUnitUtil.parseH265VpsNalUnit(backed, 0, backed.size))
        val missing = vps(64, false) // Native builds64-by-2 plus two64-row arrays, all modest.
        val failure = assertThrows(RuntimeException::class.java) {
            NalUnitUtil.parseH265VpsNalUnit(missing, 0, missing.size)
        }
        assertTrue(failure.stackTrace.any { it.className == NalUnitUtil::class.java.name &&
            it.methodName.contains("parseH265VpsNalUnitPayload") })
        assertTrue(missing.size < 64)
        // Allocation order is pinned source evidence; no heap peak or giant count is measured.
    }

    @Test fun spsReferencePictureHelperReachesItsCountDerivedArrayBeforeMissingDeltasFail() {
        val method = NalUnitUtil::class.java.getDeclaredMethod(
            "skipH265ShortTermReferencePictureSets", ParsableNalUnitBitArray::class.java).also { it.isAccessible = true }
        val backed = Bits().ue(1).ue(2).ue(0).ue(0).bit(true).ue(0).bit(true).bytes()
        assertNull(method.invoke(null, ParsableNalUnitBitArray(backed, 0, backed.size)))
        val missing = Bits().ue(1).ue(64).ue(0).bytes() // One modest64-int target before delta reads.
        val wrapped = assertThrows(InvocationTargetException::class.java) {
            method.invoke(null, ParsableNalUnitBitArray(missing, 0, missing.size))
        }
        val failure = requireNotNull(wrapped.cause)
        assertTrue(failure is RuntimeException)
        assertTrue(failure.stackTrace.any { it.className == NalUnitUtil::class.java.name &&
            it.methodName.contains("skipH265ShortTermReferencePictureSets") })
        assertTrue(missing.size < 16)
        // Private-helper reflection is TEST ONLY; this alone is not full SPS/container reachability.
    }

    @Test fun completeTinyMatroskaCodecEnvelopeReachesTheDefaultReadersVpsParser() {
        for (backed in listOf(true, false)) {
            val nal = vps(if (backed) 2 else 64, backed)
            val hvcc = ByteArray(23).also {
                it[0] = 1; it[1] = 1; it[12] = 120; it[13] = 0xf0.toByte()
                it[15] = 0xfc.toByte(); it[16] = 0xfd.toByte(); it[17] = 0xf8.toByte(); it[18] = 0xf8.toByte()
                it[21] = 3; it[22] = 1
            } + byteArrayOf(32, 0, 1, (nal.size ushr 8).toByte(), nal.size.toByte()) + nal
            val entry = element(0xae, element(0xd7, byteArrayOf(1)) + element(0x83, byteArrayOf(1)) +
                element(0x86, "V_MPEGH/ISO/HEVC".toByteArray()) + element(0x63a2, hvcc) +
                element(0xe0, element(0xb0, byteArrayOf(16)) + element(0xba, byteArrayOf(16))))
            val bytes = element(0x1a45dfa3, element(0x4282, "matroska".toByteArray())) +
                element(0x18538067, element(0x1654ae6b, entry))
            assertTrue(bytes.size < 256)
            var position = 0
            val reader = DataReader { target, offset, length ->
                if (length == 0) 0 else if (position >= bytes.size) C.RESULT_END_OF_INPUT else
                    minOf(length, bytes.size - position).also { bytes.copyInto(target, offset, position, position + it); position += it }
            }
            val input = DefaultExtractorInput(reader, 0, bytes.size.toLong())
            val raw = DefaultExtractorsFactory().createExtractors().single { it is MatroskaExtractor }
            var format: Format? = null
            try {
                assertTrue(raw.sniff(input)); input.resetPeekPosition()
                raw.init(object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
                        override fun format(value: Format) { format = value }
                    }
                    override fun endTracks() {}
                    override fun seekMap(seekMap: SeekMap) {}
                })
                fun finish() {
                    val holder = PositionHolder()
                    repeat(100) {
                        when (raw.read(input, holder)) {
                            Extractor.RESULT_END_OF_INPUT -> return
                            Extractor.RESULT_CONTINUE -> Unit
                            else -> error("Unexpected tiny-envelope seek")
                        }
                    }
                    error("Tiny envelope did not finish")
                }
                if (backed) {
                    finish()
                    assertEquals(MimeTypes.VIDEO_H265, format?.sampleMimeType)
                } else {
                    val error = assertThrows(Exception::class.java) { finish() }
                    val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
                    assertTrue(chain.any { cause -> cause.stackTrace.any { it.className == NalUnitUtil::class.java.name &&
                        it.methodName.contains("parseH265VpsNalUnitPayload") } })
                    assertNull(format)
                }
            } finally { raw.release() }
        }
        // Real default-reader metadata reachability only: no encoded frames or video decoder.
    }

    private fun element(id: Int, bytes: ByteArray): ByteArray {
        val width = when { id > 0xffffff -> 4; id > 0xffff -> 3; id > 0xff -> 2; else -> 1 }
        val prefix = ByteArray(width) { (id ushr ((width - 1 - it) * 8)).toByte() }
        val size = if (bytes.size < 127) byteArrayOf((0x80 or bytes.size).toByte()) else
            byteArrayOf((0x40 or (bytes.size ushr 8)).toByte(), bytes.size.toByte())
        return prefix + size + bytes
    }

    private fun vps(sets: Int, backed: Boolean): ByteArray {
        val bits = Bits().bits(0x4001, 16) // VPS NAL type32, layer0, temporal-id-plus-one1.
            .bits(0, 4).bit(true).bit(true).bits(1, 6) // Two base-included layers.
            .bits(0, 3).bits(0x1ffff, 17)
            .bits(1, 8).bits(0, 32).bits(0, 48).bits(120, 8) // Profile-tier-level, no sublayers.
            .bit(false).ue(0).ue(0).ue(0) // One sublayer ordering record.
            .bits(1, 6).ue(sets - 1) // max-layer-id1, declared layer-set count.
        if (backed) {
            repeat(sets - 1) { bits.bit(true).bit(true) }
            bits.bit(false).bit(false) // No timing info or layered extension; native fallback.
        }
        val output = ByteArrayOutputStream()
        var zeros = 0
        for (b in bits.bytes()) {
            if (zeros >= 2 && b.toInt() and 255 <= 3) { output.write(3); zeros = 0 }
            output.write(b.toInt() and 255)
            zeros = if (b == 0.toByte()) zeros + 1 else 0
        }
        return output.toByteArray()
    }
    private class Bits {
        private val bits = StringBuilder()
        fun bit(value: Boolean) = apply { bits.append(if (value) '1' else '0') }
        fun bits(value: Long, count: Int) = apply { for (i in count - 1 downTo 0) bit((value ushr i) and 1L != 0L) }
        fun ue(value: Int) = apply {
            require(value >= 0)
            val code = value.toLong() + 1
            val width = 64 - java.lang.Long.numberOfLeadingZeros(code)
            repeat(width - 1) { bit(false) }; bits(code, width)
        }
        fun bytes(): ByteArray = ByteArray((bits.length + 7) / 8) { at ->
            var value = 0
            repeat(8) { bit -> value = (value shl 1) or (if (at * 8 + bit < bits.length && bits[at * 8 + bit] == '1') 1 else 0) }
            value.toByte()
        }
    }
}
