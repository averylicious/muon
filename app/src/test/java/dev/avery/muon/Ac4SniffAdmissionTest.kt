@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ts.Ac4Extractor
import java.io.EOFException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Modest native sniff controls only, not encoded audio/decoder/heap-pressure/device tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class Ac4SniffAdmissionTest {
    @Test fun rawDefaultSnifferGrowsPeekStorageBeforeAMissingDeclaredBodyArrives() {
        for (known in listOf(false, true)) {
            val source = Opened(declared(), known)
            val raw = native()
            try {
                assertThrows(EOFException::class.java) { raw.sniff(source.input) }
                // Observe the actual backing passed to DataReader, no private production API.
                assertTrue(source.largestTarget >= 128 * 1024)
                assertEquals(0L, source.input.position)
            } finally { raw.release() }
        }
    }

    @Test fun guardedSniffRefusesBeforeGrowthAndStillLetsTheActualMp3CandidateTry() {
        for (known in listOf(false, true)) {
            val source = Opened(declared(), known)
            val bounded = MetadataBoundedExtractor(native(), ac4SniffBytes = 64 * 1024)
            try {
                assertFalse(bounded.sniff(source.input))
                assertTrue(source.largestTarget <= 64 * 1024)
                assertEquals(0L, source.input.position)
            } finally { bounded.release() }
        }
        // A sync-like junk prefix is not proof of AC-4. Refusal must not stop another format.
        val mpeg = ByteArray(417).also { byteArrayOf(-1, 0xfb.toByte(), 0x90.toByte(), 0).copyInto(it) }
        val source = Opened(declared() + mpeg + mpeg + mpeg + mpeg, false)
        val ac4 = MetadataBoundedExtractor(native(), ac4SniffBytes = 64 * 1024)
        val mp3 = MetadataBoundedExtractor(DefaultExtractorsFactory().createExtractors().single { it is Mp3Extractor })
        try {
            assertFalse(ac4.sniff(source.input)); source.input.resetPeekPosition()
            assertTrue(mp3.sniff(source.input))
            assertEquals(0L, source.input.position)
        } finally { ac4.release(); mp3.release() }
    }

    @Test fun normalSyncwordsAndLeadingTagsKeepNativeSniffIdentityAndExactBudget() {
        for (crc in listOf(false, true)) for (prefix in listOf(byteArrayOf(), "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, 0))) {
            val frame = ByteArray(32).also {
                it[0] = 0xac.toByte(); it[1] = (if (crc) 0x41 else 0x40).toByte()
                it[3] = (if (crc) 26 else 28).toByte()
            }
            val bytes = prefix + frame + frame + frame + frame
            val wanted = prefix.size + 3L * frame.size + 7 // Native accepts after the fourth header.
            val raw = native()
            val bounded = MetadataBoundedExtractor(native(), ac4SniffBytes = wanted)
            val refused = MetadataBoundedExtractor(native(), ac4SniffBytes = wanted - 1)
            try {
                assertTrue(raw.sniff(Opened(bytes, true).input))
                assertTrue(bounded.underlyingImplementation is Ac4Extractor)
                assertTrue(bounded.sniff(Opened(bytes, true).input))
                assertFalse(refused.sniff(Opened(bytes, true).input))
            } finally { raw.release(); bounded.release(); refused.release() }
        }
        // A small truncated known-length declaration fails before any requested body buffering,
        // even under the default generous resource allowance. Preserve native EOF, not a pass.
        val short = Opened(declared(), true)
        val bounded = MetadataBoundedExtractor(native())
        try {
            assertThrows(EOFException::class.java) { bounded.sniff(short.input) }
            assertTrue(short.largestTarget <= 64 * 1024)
        } finally { bounded.release() }
    }

    private fun native() = DefaultExtractorsFactory().createExtractors().single { it is Ac4Extractor }
    private fun declared() = byteArrayOf(0xac.toByte(), 0x40, -1, -1, 2, 0, 0, 0, 0, 0) //128KiB body.
    private class Opened(bytes: ByteArray, known: Boolean) {
        var largestTarget = 0
        private var at = 0
        private val reader = DataReader { target, offset, length ->
            largestTarget = maxOf(largestTarget, target.size)
            if (length == 0) 0 else if (at >= bytes.size) C.RESULT_END_OF_INPUT else
                minOf(length, bytes.size - at).also { bytes.copyInto(target, offset, at, at + it); at += it }
        }
        val input = DefaultExtractorInput(reader, 0, if (known) bytes.size.toLong() else C.LENGTH_UNSET.toLong())
    }
}
