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
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.PsshAtomUtil
import java.util.UUID
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real default-factory MFRA route; modest synthetic declarations, no audio decode or heap stress. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FragmentedMp4AdmissionTest {
    @Test fun actualTailLengthIsAdmittedBeforeTheMfraArray() {
        val bytes = index(entries = 1, padding = 4096).also {
            // Native readMfra ignores the outer size except for its extended-header marker.
            // The declared8-byte box must not hide its actual larger allocation suffix.
            ByteBuffer.wrap(it, top(it, "mfra"), 4).putInt(8)
        }
        val accepted = Capture(); val normal = Faults()
        run(bytes, Mp4Limits(retainedBytes = 8192), accepted, normal)
        assertEquals(MimeTypes.AUDIO_AAC, accepted.format?.sampleMimeType)
        assertTrue(requireNotNull(accepted.map).isSeekable)
        assertTrue(normal.headerSeen && normal.bodyReads > 0 && normal.seeks > 0)

        val refused = Capture(); val small = Faults()
        val error = assertThrows(StreamMetadataRefusal::class.java) {
            run(bytes, Mp4Limits(retainedBytes = 1024), refused, small)
        }
        assertFalse(error.contentIsMalformed)
        assertEquals(MimeTypes.AUDIO_AAC, refused.format?.sampleMimeType)
        assertTrue("Must reach the actual MFRA header, not fail initial sniffing", small.headerSeen)
        assertEquals("Tail allocation must be refused before its body read", 0, small.bodyReads)
        assertNull(refused.map)
    }

    @Test fun backedIndexCountsShareAWeightedTableBudget() {
        val bytes = index(entries = 64)
        val accepted = Capture(); val normal = Faults()
        run(bytes, Mp4Limits(tableRows = 200), accepted, normal)
        assertTrue(requireNotNull(accepted.map).isSeekable)
        assertTrue(normal.bodyReads > 0)
        // Two64-entry long arrays: small even without a guard. These index points deliberately
        // exercise declarations, not a claim that all named future times are decodable audio.
        val refused = Capture(); val small = Faults()
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            run(bytes, Mp4Limits(tableRows = 100), refused, small)
        }.contentIsMalformed)
        assertTrue("Check actual delivered suffix before native time/offset arrays", small.bodyReads > 0)
        assertNull(refused.map)
    }

    @Test fun interruptedTailReopensWithoutLosingAdmissionOrSeekMap() {
        val bytes = fixture()
        val faults = Faults(at = top(bytes, "mfra") + 8L)
        val out = Capture()
        run(bytes, Mp4Limits(), out, faults)
        assertEquals(-1L, faults.at)
        assertTrue(faults.bodyReads >= 2 && faults.seeks > 0)
        assertEquals(MimeTypes.AUDIO_AAC, out.format?.sampleMimeType)
        assertTrue(requireNotNull(out.map).isSeekable)
    }

    @Test fun initialMovieTablesCannotBypassAdmissionThroughFragmentedSelection() {
        val path = arrayOf("moov", "trak", "mdia", "minf", "stbl")
        var bytes = fixture()
        // Coherent modest64-row initial sample table; named audio isn't decoded by this test.
        for ((name, table) in listOf(
            "stsz" to atom("stsz", be(0), be(4), be(64)),
            "stts" to atom("stts", be(0), be(1), be(64), be(1024)),
            "stsc" to atom("stsc", be(0), be(1), be(1), be(64), be(1)),
            "stco" to atom("stco", be(0), be(1), be(0)),
        )) bytes = replace(bytes, path + name) { table }
        val accepted = Capture()
        run(bytes, Mp4Limits(tableRows = 200), accepted, Faults(), stopAtFormat = true)
        assertEquals(MimeTypes.AUDIO_AAC, accepted.format?.sampleMimeType)
        val refused = Capture()
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            run(bytes, Mp4Limits(tableRows = 100), refused, Faults(), stopAtFormat = true)
        }.contentIsMalformed)
        assertNull("Refuse before native moov parsing publishes the track", refused.format)
    }

    @Test fun initialDrmKeyCountsAreBackedAndShareTheMetadataBudget() {
        val pssh = PsshAtomUtil.buildPsshAtom(UUID(1, 2), arrayOf(UUID(3, 4), UUID(5, 6)), byteArrayOf(7))
        fun added(box: ByteArray) = replace(fixture(), arrayOf("moov")) { old ->
            atom("moov", old.copyOfRange(8, old.size), box)
        }
        val accepted = Capture()
        run(added(pssh), Mp4Limits(), accepted, Faults(), stopAtFormat = true)
        assertEquals(UUID(1, 2), requireNotNull(accepted.format?.drmInitData).get(0).uuid)
        val keys = Array(20) { UUID(3, it.toLong()) }
        val twenty = PsshAtomUtil.buildPsshAtom(UUID(1, 2), keys, byteArrayOf(7))
        val one = Capture()
        run(added(twenty), Mp4Limits(metadataEntries = 32), one, Faults(), stopAtFormat = true)
        assertEquals(MimeTypes.AUDIO_AAC, one.format?.sampleMimeType)
        val small = Capture()
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            run(added(twenty + twenty), Mp4Limits(metadataEntries = 32), small, Faults(), stopAtFormat = true)
        }.contentIsMalformed) // Both individually backed20-key boxes share the movie budget.
        assertNull(small.format)
        val bad = pssh.copyOf().also { ByteBuffer.wrap(it, 28, 4).putInt(64) }
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            run(added(bad), Mp4Limits(), Capture(), Faults(), stopAtFormat = true)
        }.contentIsMalformed)
        // A declared parsed leaf must also be bounded BEFORE its missing body is requested.
        val truncated = replace(fixture(), arrayOf("moov")) { old ->
            atom("moov", old.copyOfRange(8, old.size), be(4096), "pssh".toByteArray(Charsets.US_ASCII))
        }
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            run(truncated, Mp4Limits(), Capture(), Faults(), stopAtFormat = true)
        }.contentIsMalformed)
    }

    private fun run(bytes: ByteArray, limits: Mp4Limits, out: Capture, faults: Faults, stopAtFormat: Boolean = false) {
        val mfra = top(bytes, "mfra").toLong()
        fun open(at: Long): ExtractorInput = object : ForwardingExtractorInput(
            DefaultExtractorInput(Reader(bytes, at, faults), at, bytes.size.toLong())) {
            override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
                if (peekPosition == mfra && offset == 0 && length == 8) faults.headerSeen = true
                return super.peekFully(target, offset, length, allowEndOfInput)
            }
            override fun readFully(target: ByteArray, offset: Int, length: Int) {
                if (position == mfra && offset == 0 && length.toLong() == bytes.size - mfra) faults.bodyReads++
                super.readFully(target, offset, length)
            }
        }
        // The actual default factory adds MFRA support; the bare deprecated constructor does not.
        val actual = DefaultExtractorsFactory().createExtractors().single { it is FragmentedMp4Extractor }
        val extractor = MetadataBoundedExtractor(actual, mp4Limits = limits)
        var input = open(0)
        try {
            extractor.init(out)
            assertTrue(extractor.sniff(input)); input.resetPeekPosition()
            val holder = PositionHolder(); var reads = 0
            while (true) {
                check(reads++ < 1000) { "Fixture did not finish" }
                val result = try { extractor.read(input, holder) } catch (_: Interrupted) {
                    input = open(input.position)
                    continue
                }
                if (stopAtFormat && out.format != null) return
                when (result) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_SEEK -> { faults.seeks++; input = open(holder.position) }
                    else -> return
                }
            }
        } finally { extractor.release() }
    }

    private class Capture : ExtractorOutput {
        var format: Format? = null
        var map: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(value: Format) { format = value }
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }
    private class Faults(var at: Long = -1) {
        var headerSeen = false; var bodyReads = 0; var seeks = 0
    }
    private class Interrupted : IOException("Injected tail read interruption")
    private class Reader(private val bytes: ByteArray, private var at: Long, private val faults: Faults) : DataReader {
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (at >= bytes.size) return C.RESULT_END_OF_INPUT
            var count = minOf(length.toLong(), bytes.size - at).toInt()
            if (faults.at >= at && faults.at < at + count) {
                if (faults.at == at) { faults.at = -1; throw Interrupted() }
                count = (faults.at - at).toInt()
            }
            bytes.copyInto(target, offset, at.toInt(), at.toInt() + count)
            at += count
            return count
        }
    }

    private fun index(entries: Int, padding: Int = 0): ByteArray {
        val original = fixture()
        val moof = top(original, "moof")
        val mfra = top(original, "mfra")
        var points = ByteArray(0)
        repeat(entries) { points += be64(it * 1024L) + be64(moof.toLong()) + byteArrayOf(1, 1, 1) }
        val tfra = atom("tfra", be(1 shl 24), be(1), be(0), be(entries), points)
        val free = if (padding == 0) ByteArray(0) else atom("free", ByteArray(padding))
        val size = 8 + tfra.size + free.size + 16
        val tail = atom("mfra", tfra, free, atom("mfro", be(0), be(size)))
        return original.copyOfRange(0, mfra) + tail
    }
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/mp4-admission/fragmented.m4a")).use { it.readBytes() }
    private fun top(bytes: ByteArray, name: String): Int {
        var at = 0
        while (at < bytes.size) {
            val size = ByteBuffer.wrap(bytes, at, 4).int
            check(size >= 8 && size <= bytes.size - at)
            if (String(bytes, at + 4, 4, Charsets.US_ASCII) == name) return at
            at += size
        }
        error("Missing fixture box $name")
    }
    /** Rewrite only framed fixture ancestors; this is fixture construction, not admission logic. */
    private fun replace(bytes: ByteArray, path: Array<String>, change: (ByteArray) -> ByteArray): ByteArray {
        val at = top(bytes, path.first())
        val size = ByteBuffer.wrap(bytes, at, 4).int
        val original = bytes.copyOfRange(at, at + size)
        val updated = if (path.size == 1) change(original) else atom(path.first(),
            replace(original.copyOfRange(8, original.size), path.drop(1).toTypedArray(), change))
        return bytes.copyOfRange(0, at) + updated + bytes.copyOfRange(at + size, bytes.size)
    }
    private fun be(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun be64(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
    private fun atom(name: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.fold(ByteArray(0)) { result, part -> result + part }
        return be(payload.size + 8) + name.toByteArray(Charsets.US_ASCII) + payload
    }
}
