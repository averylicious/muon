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

    @Test fun interruptedTailAndFragmentBodiesReopenWithoutLosingAdmissionOrSeekMap() {
        val bytes = fixture()
        for ((name, at) in listOf(
            "tail" to top(bytes, "mfra") + 8L,
            "track header" to position(bytes, arrayOf("moof", "traf", "tfhd")) + 8L,
            "sample run" to position(bytes, arrayOf("moof", "traf", "trun")) + 8L,
        )) {
            val faults = Faults(at)
            val out = Capture()
            run(bytes, Mp4Limits(), out, faults)
            assertEquals(name, -1L, faults.at)
            assertTrue(name, faults.bodyReads >= if (name == "tail") 2 else 1)
            assertTrue(name, faults.seeks > 0)
            assertEquals(name, MimeTypes.AUDIO_AAC, out.format?.sampleMimeType)
            assertTrue(name, requireNotNull(out.map).isSeekable)
            assertEquals(name, 4, out.samples)
        }
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

    @Test fun sampleRunsAreBackedAndAdmittedTogetherBeforeTheirArrays() {
        val accepted = Capture()
        run(withRuns(listOf(8)), Mp4Limits(tableRows = 100), accepted, Faults(), stopAtSample = true)
        assertEquals(1, accepted.samples)
        val small = Capture()
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            run(withRuns(listOf(8, 8)), Mp4Limits(tableRows = 70), small, Faults(), stopAtSample = true)
        }.contentIsMalformed) // Each run fits alone; together they need20-entry headroom arrays.
        assertEquals(0, small.samples)
        assertEquals(MimeTypes.AUDIO_AAC, small.format?.sampleMimeType)
        val original = fixture()
        val unbacked = replace(original, arrayOf("moof", "traf", "trun")) {
            atom("trun", be(0x201), be(8), be(108), be(4)) // Only one of eight size fields.
        }
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            run(unbacked, Mp4Limits(), Capture(), Faults(), stopAtSample = true)
        }.contentIsMalformed)
    }

    @Test fun differentTracksRetainTheirFragmentHighWaterCapacities() {
        val bytes = twoTrackFragments()
        val accepted = Capture()
        run(bytes, Mp4Limits(tableRows = 100), accepted, Faults())
        assertEquals(16, accepted.samples)
        val small = Capture()
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            run(bytes, Mp4Limits(tableRows = 70), small, Faults())
        }.contentIsMalformed)
        assertEquals("The first track played; refuse before allocating the second track arrays", 8, small.samples)
    }

    @Test fun segmentIndexesStayBoundedWithoutCountingDuplicateStartsAsNewRetention() {
        fun indexes(starts: List<Int>, declared: Int = 8): ByteArray {
            val reference = be(4) + be(1024) + be(0)
            var entries = ByteArray(0)
            repeat(8) { entries += reference }
            val indexes = starts.fold(ByteArray(0)) { result, start -> result +
                atom("sidx", be(0), be(1), be(48_000), be(start), be(0),
                    byteArrayOf(0, 0, (declared shr 8).toByte(), declared.toByte()), entries) }
            val original = fixture()
            val at = top(original, "moof")
            return original.copyOfRange(0, at) + indexes + original.copyOfRange(at, original.size)
        }
        val distinct = Capture()
        run(indexes(listOf(0, 8192, 16384)), Mp4Limits(tableRows = 300), distinct, Faults())
        assertEquals(3, distinct.maps)
        assertEquals(4, distinct.samples)
        val duplicate = Capture()
        run(indexes(listOf(0, 0, 0)), Mp4Limits(tableRows = 100), duplicate, Faults())
        assertEquals(3, duplicate.maps) // Native publishes all three; its merger retains one.
        assertEquals(4, duplicate.samples)
        val small = Capture()
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            run(indexes(listOf(0, 8192, 16384)), Mp4Limits(tableRows = 150), small, Faults())
        }.contentIsMalformed)
        assertEquals("Third distinct index refused before its native arrays/publication", 2, small.maps)
        val bad = Capture()
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            run(indexes(listOf(0), declared = 64), Mp4Limits(), bad, Faults())
        }.contentIsMalformed)
        assertEquals(0, bad.maps)
    }

    @Test fun selectedDefaultSizesAreAdmittedButExplicitOverridesAndIgnoredDefaultsStayPlayable() {
        fun tfhdSize(bytes: ByteArray, value: Int) = replace(bytes, arrayOf("moof", "traf", "tfhd")) {
            it.copyOf().also { out -> ByteBuffer.wrap(out, 20, 4).putInt(value) }
        }
        val normal = Capture()
        run(fixture(), Mp4Limits(sampleBytes = 4), normal, Faults(), stopAtSample = true)
        assertEquals(1, normal.samples)
        for ((value, malformed) in listOf(32 to false, Int.MIN_VALUE to true)) {
            val out = Capture()
            assertEquals(malformed, assertThrows(StreamMetadataRefusal::class.java) {
                run(tfhdSize(fixture(), value), Mp4Limits(sampleBytes = 4), out, Faults(), stopAtSample = true)
            }.contentIsMalformed)
            assertEquals(0, out.samples)
        }
        fun inherited(value: Int, ignored: Int): ByteArray {
            var bytes = replace(fixture(), arrayOf("moof", "traf", "tfhd")) { old ->
                // Remove default_sample_size; actual size now comes from the sole trex.
                (old.copyOfRange(0, 20) + old.copyOfRange(24, old.size)).also {
                    ByteBuffer.wrap(it, 0, 4).putInt(it.size)
                    ByteBuffer.wrap(it, 8, 4).putInt(0x20028)
                }
            }
            bytes = replace(bytes, arrayOf("moov", "mvex", "trex")) { old -> old.copyOf().also {
                // Pinned getDefaultSampleValues ignores mismatched ID if there is only one trex.
                ByteBuffer.wrap(it, 12, 4).putInt(7)
                ByteBuffer.wrap(it, 24, 4).putInt(value)
            } }
            bytes = replace(bytes, arrayOf("moov")) { old -> atom("moov", old.copyOfRange(8, old.size),
                atom("mvex", atom("trex", be(0), be(7), be(1), be(0), be(ignored), be(0)))) }
            val offset = child(bytes, arrayOf("moof")).size + 8
            return replace(bytes, arrayOf("moof", "traf", "trun")) { old -> old.copyOf().also {
                ByteBuffer.wrap(it, 16, 4).putInt(offset)
            } }
        }
        val fallback = Capture()
        run(inherited(4, ignored = 32), Mp4Limits(sampleBytes = 4), fallback, Faults(), stopAtSample = true)
        assertEquals(1, fallback.samples)
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            run(inherited(32, ignored = 4), Mp4Limits(sampleBytes = 4), Capture(), Faults(), stopAtSample = true)
        }.contentIsMalformed)
        // All sample sizes are explicit: the oversized but UNUSED tfhd/trex defaults may remain.
        var explicit = replace(tfhdSize(fixture(), 32), arrayOf("moof", "traf", "trun")) {
            atom("trun", be(0x201), be(4), be(124), be(4), be(4), be(4), be(4))
        }
        explicit = replace(explicit, arrayOf("moov", "mvex", "trex")) { old -> old.copyOf().also {
            ByteBuffer.wrap(it, 24, 4).putInt(32)
        } }
        val overridden = Capture()
        run(explicit, Mp4Limits(sampleBytes = 4), overridden, Faults(), stopAtSample = true)
        assertEquals(1, overridden.samples)
    }

    private fun run(bytes: ByteArray, limits: Mp4Limits, out: Capture, faults: Faults,
        stopAtFormat: Boolean = false, stopAtSample: Boolean = false) {
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
                if ((stopAtFormat && out.format != null) || (stopAtSample && out.samples > 0)) return
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
        var samples = 0
        var maps = 0
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(value: Format) { format = value }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) { samples++ }
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { map = seekMap; maps++ }
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
    private fun withRuns(counts: List<Int>, track: Int = 1): ByteArray {
        val original = fixture()
        //20-byte trun with tfhd defaults: positive counts need no per-sample payload.
        val moofSize = 116 - 36 + 20 * counts.size
        var offset = moofSize + 8
        val runs = counts.fold(ByteArray(0)) { result, count ->
            val run = atom("trun", be(1), be(count), be(offset))
            offset += count * 4
            result + run
        }
        var bytes = replace(original, arrayOf("moof", "traf", "trun")) { runs }
        bytes = replace(bytes, arrayOf("moof", "traf", "tfhd")) { old ->
            old.copyOf().also { ByteBuffer.wrap(it, 12, 4).putInt(track) }
        }
        return bytes
    }

    private fun twoTrackFragments(): ByteArray {
        val original = fixture()
        var movie = original.copyOfRange(0, top(original, "moof"))
        val track = child(original, arrayOf("moov", "trak")).let { bytes ->
            replace(bytes, arrayOf("trak", "tkhd")) { old ->
                old.copyOf().also { ByteBuffer.wrap(it, 20, 4).putInt(2) }
            }
        }
        movie = replace(movie, arrayOf("moov")) { old -> atom("moov", old.copyOfRange(8, old.size), track) }
        val trex = child(original, arrayOf("moov", "mvex", "trex")).also { ByteBuffer.wrap(it, 12, 4).putInt(2) }
        movie = replace(movie, arrayOf("moov", "mvex")) { old -> atom("mvex", old.copyOfRange(8, old.size), trex) }
        val first = child(withRuns(listOf(8), track = 1), arrayOf("moof"))
        val second = child(withRuns(listOf(8), track = 2), arrayOf("moof"))
        // Repeated original AAC silence packet bytes, parsed/discarded without decoding.
        val packet = original.copyOfRange(top(original, "mdat") + 8, top(original, "mdat") + 12)
        var media = ByteArray(0)
        repeat(8) { media += packet }
        val tail = child(original, arrayOf("mfra")).also { ByteBuffer.wrap(it, 40, 8).putLong(movie.size.toLong()) }
        return movie + first + atom("mdat", media) + second + atom("mdat", media) + tail
    }

    private fun position(bytes: ByteArray, path: Array<String>): Int {
        val at = top(bytes, path.first())
        val size = ByteBuffer.wrap(bytes, at, 4).int
        return if (path.size == 1) at else at + 8 +
            position(bytes.copyOfRange(at + 8, at + size), path.drop(1).toTypedArray())
    }

    private fun child(bytes: ByteArray, path: Array<String>): ByteArray {
        val at = top(bytes, path.first())
        val size = ByteBuffer.wrap(bytes, at, 4).int
        return if (path.size == 1) bytes.copyOfRange(at, at + size)
            else child(bytes.copyOfRange(at + 8, at + size), path.drop(1).toTypedArray())
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
