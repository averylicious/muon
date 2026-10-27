@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

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
