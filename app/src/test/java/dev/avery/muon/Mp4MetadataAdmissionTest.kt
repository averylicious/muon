@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #720 admission through the actual pinned MP4 extractors, Sniffer and Muon's factory; demux and
 * metadata only. No audio decoding, heap pressure or phone. Declared sizes stay small, so a broken
 * guard fails these assertions instead of attempting a large allocation. The raw dependency
 * characterization stays in Mp4MetadataBoundaryTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class Mp4MetadataAdmissionTest {
    @Test fun validFixturesPlaySeekAndRestartThroughTheFactory() {
        val cases = listOf("control.m4a" to MimeTypes.AUDIO_AAC, "aac-edit.m4a" to MimeTypes.AUDIO_AAC,
            "alac.m4a" to MimeTypes.AUDIO_ALAC, "pcm.mov" to MimeTypes.AUDIO_RAW, "fragmented.m4a" to MimeTypes.AUDIO_AAC,
            "tail-control.m4a" to MimeTypes.AUDIO_AAC, "tail-aac-edit.m4a" to MimeTypes.AUDIO_AAC,
            "tail-alac.m4a" to MimeTypes.AUDIO_ALAC, "tail-pcm.mov" to MimeTypes.AUDIO_RAW)
        for ((name, mime) in cases) {
            val bytes = resource(name, tail = false)
            val output = Capture()
            val adapter = BundledExtractorsAdapter(PlaybackExtractors())
            try {
                drive(adapter, bytes, 0, output)
                assertEquals(name, mime, output.format?.sampleMimeType)
                val map = requireNotNull(output.map) { name }
                if (map.isSeekable) {
                    // A nonzero reopen continues with the admitted tables.
                    val point = map.getSeekPoints(map.durationUs / 2).first
                    adapter.seek(point.position, point.timeUs)
                    drive(adapter, bytes, point.position, output)
                }
                // A seek to the start reparses, and readmits, the whole movie box.
                adapter.seek(0, 0)
                drive(adapter, bytes, 0, output)
            } finally { adapter.release() }
        }
    }

    @Test fun parsedLeafHeadersAreBoundedBeforeTheirArraysExist() {
        val control = resource("control.m4a")
        mediaBeforeMovie(control)
        val moov = chain(control, "moov")
        val udtaAt = moov.last().end + 8 // Header end of a leaf appended to moov.
        val cases = listOf(
            // 2KiB of retained leaf under a 1KiB budget, refused from its header alone.
            Triple("retained", false, append(control, moov, atom("udta", ByteArray(2048)))),
            // A leaf declaring more than its container holds.
            Triple("parent", true, append(control, moov, be(4096) + ascii("udta") + ByteArray(16))),
            // A 64-bit header on a parsed leaf, which Mp4Extractor cannot allocate.
            Triple("extended leaf", true, append(control, moov, be(1) + ascii("udta") + be64(32) + ByteArray(16))),
            // A container whose 64-bit end overflows its parent.
            Triple("extended overflow", true, append(control, moov, be(1) + ascii("edts") + be64(Long.MAX_VALUE - 4))),
            // Size 0 resolves to the stream end, past moov when another box follows it.
            Triple("size zero", true, append(control, moov, be(0) + ascii("udta") + ByteArray(8)) + atom("free")),
        )
        // Read without sniffing, as a one-candidate factory does: Sniffer would otherwise peek the bytes first.
        for ((name, malformed, bytes) in cases) {
            val output = Capture()
            val faults = Faults()
            val extractor = MetadataBoundedExtractor(Mp4Extractor(), mp4Limits = Mp4Limits(retainedBytes = 1024))
            val error = assertThrows(name, StreamMetadataRefusal::class.java) {
                pump(extractor, start(extractor, output, bytes, faults, sniff = false), bytes, faults)
            }
            assertEquals(name, malformed, error.contentIsMalformed)
            assertNull(name, output.map)
            if (name == "retained") assertTrue("Body was read", faults.furthest <= udtaAt)
        }
        // A movie-level ISO meta box: Mp4Extractor skips its 4-byte version before its children.
        val hdlr = atom("hdlr", ByteArray(8) + ascii("mdir") + ByteArray(13))
        val meta = append(control, moov, atom("meta", ByteArray(4), hdlr))
        val output = Capture()
        val faults = Faults()
        val extractor = MetadataBoundedExtractor(Mp4Extractor(), mp4Limits = Mp4Limits(retainedBytes = 1024))
        pump(extractor, start(extractor, output, meta, faults, sniff = false), meta, faults)
        assertEquals(MimeTypes.AUDIO_AAC, output.format?.sampleMimeType)
    }

    @Test fun sampleTableCountsMustBeBackedAndShareOneBudgetAcrossTracks() {
        val control = resource("control.m4a")
        val edited = resource("aac-edit.m4a")
        mediaBeforeMovie(control); mediaBeforeMovie(edited)
        val stbl = arrayOf("moov", "trak", "mdia", "minf", "stbl")
        fun table(type: String, payload: ByteArray) = replace(control, chain(control, *stbl, type), atom(type, payload))
        val entries = payload(control, chain(control, *stbl, "stsd")).let { it.copyOfRange(8, it.size) }
        val malformed = listOf(
            // Variable sizes: 64 declared, one present.
            "stsz" to table("stsz", ByteArray(4) + be(0) + be(64) + be(10)),
            // 16-bit compact sizes: 64 declared, one present.
            "stz2" to replace(control, chain(control, *stbl, "stsz"), atom("stz2", ByteArray(4) + be(16) + be(64) + ByteArray(2))),
            "stco" to table("stco", ByteArray(4) + be(64) + be(0)),
            // 64 sample entries declared (each sizes a TrackEncryptionBox slot), one present.
            "stsd" to table("stsd", ByteArray(4) + be(64) + entries),
            "elst" to replace(edited, chain(edited, "moov", "trak", "edts", "elst"), atom("elst", ByteArray(4) + be(64))),
        )
        for ((name, bytes) in malformed) {
            val output = Capture(); val faults = Faults()
            val extractor = MetadataBoundedExtractor(Mp4Extractor())
            assertTrue(name, assertThrows(name, StreamMetadataRefusal::class.java) {
                pump(extractor, start(extractor, output, bytes, faults), bytes, faults)
            }.contentIsMalformed)
            assertNull(name, output.map)
        }
        // Fixed-size samples need no per-sample bytes: 300 declared rows fit a 1,000-row budget once,
        // not twice. Every track draws from the same movie budget.
        val one = fixedCount(control, 300)
        val two = append(one, chain(one, "moov"), one.copyOfRange(chain(one, "moov", "trak").last().start, chain(one, "moov", "trak").last().end))
        val limits = Mp4Limits(tableRows = 1000)
        assertNotNull(published(one, limits).map)
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) { published(two, limits) }.contentIsMalformed)
        // #721's 4,096 fixed-size rows, refused before parseStbl builds a seek map from them.
        val output = Capture()
        assertThrows(StreamMetadataRefusal::class.java) { published(fixedCount(control, 4096), limits, output) }
        assertNull(output.map)
    }

    @Test fun aLargeSkippedFinalChildStillAdmitsTablesBeforeTheyAreBuilt() {
        val control = fixedCount(resource("control.m4a"), 300)
        mediaBeforeMovie(control)
        // Over 256KiB: Mp4Extractor seeks past it and ends moov, building its tables, without another read.
        val padded = append(control, chain(control, "moov"), atom("free", ByteArray(300 * 1024)))
        val faults = Faults()
        val output = Capture()
        val extractor = MetadataBoundedExtractor(Mp4Extractor(), mp4Limits = Mp4Limits(tableRows = 500))
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            pump(extractor, start(extractor, output, padded, faults, sniff = false), padded, faults)
        }.contentIsMalformed)
        assertNull("Tables were built past the budget", output.map)
        // The same padding with tables inside the budget still plays.
        assertEquals(MimeTypes.AUDIO_AAC, published(padded, Mp4Limits()).format?.sampleMimeType)
    }

    @Test fun editCopiesAndRawPcmFollowTheirActualTablePaths() {
        val limits = Mp4Limits(tableRows = 1000)
        val edited = resource("aac-edit.m4a")
        mediaBeforeMovie(edited)
        assertNotNull(published(edited, limits).map)
        val elst = chain(edited, "moov", "trak", "edts", "elst")
        val original = payload(edited, elst)
        assertEquals(1, int(original, 4))
        val entry = original.copyOfRange(8, original.size)
        // 600 media edits may each copy every sample, which the budget must count.
        var entries = ByteArray(0)
        repeat(600) { entries += entry }
        val copies = replace(edited, elst, atom("elst", original.copyOfRange(0, 4), be(600), entries))
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) { published(copies, limits) }.contentIsMalformed)

        // Raw PCM counts one row per PCM frame, but Media3 rechunks it from its chunk table.
        val pcm = resource("pcm.mov")
        mediaBeforeMovie(pcm)
        val stbl = arrayOf("moov", "trak", "mdia", "minf", "stbl")
        val stsd = payload(pcm, chain(pcm, *stbl, "stsd"))
        assertTrue(String(stsd, 12, 4, Charsets.US_ASCII) in setOf("sowt", "twos", "lpcm"))
        val stsz = payload(pcm, chain(pcm, *stbl, "stsz"))
        assertTrue(int(stsz, 4) > 0 && int(stsz, 8) > 1000)
        val stts = payload(pcm, chain(pcm, *stbl, "stts"))
        assertEquals(1, int(stts, 4))
        assertEquals(MimeTypes.AUDIO_RAW, published(pcm, limits).format?.sampleMimeType)
        // Two timing entries take the normal per-sample path for the same samples.
        val count = int(stts, 8)
        val split = replace(pcm, chain(pcm, *stbl, "stts"),
            atom("stts", ByteArray(4), be(2), be(count - 1), be(int(stts, 12)), be(1), be(int(stts, 12))))
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) { published(split, limits) }.contentIsMalformed)
    }

    @Test fun interruptedBodyAndExtendedHeaderResumeAtTheSameAtom() {
        val control = resource("control.m4a")
        mediaBeforeMovie(control)
        val stsz = chain(control, "moov", "trak", "mdia", "minf", "stbl", "stsz").last()
        // Interrupted inside a leaf body; reopened at the extractor's position, as Media3 does.
        // Unsniffed, so Sniffer's peek does not consume the injected fault first.
        val inBody = Faults(stsz.start + 12L)
        val first = Capture()
        val extractor = MetadataBoundedExtractor(Mp4Extractor())
        pump(extractor, start(extractor, first, control, inBody, sniff = false), control, inBody)
        assertEquals(-1L, inBody.at)
        assertEquals(MimeTypes.AUDIO_AAC, first.format?.sampleMimeType)

        // moov rewritten with a 64-bit size, interrupted between its two header reads.
        val moov = chain(control, "moov")
        val body = payload(control, moov)
        val extended = replace(control, moov, be(1) + ascii("moov") + be64(16L + body.size) + body)
        val between = Faults(moov.last().start + 8L)
        val second = Capture()
        val again = MetadataBoundedExtractor(Mp4Extractor())
        pump(again, start(again, second, extended, between, sniff = false), extended, between)
        assertEquals(-1L, between.at)
        assertEquals(MimeTypes.AUDIO_AAC, second.format?.sampleMimeType)
    }

    @Test fun seekToStartRechecksAFreshMovie() {
        val control = resource("control.m4a")
        val output = Capture()
        val faults = Faults()
        val extractor = MetadataBoundedExtractor(Mp4Extractor())
        pump(extractor, start(extractor, output, control, faults), control, faults)
        assertNotNull(output.map)
        extractor.seek(0, 0)
        val stsz = chain(control, "moov", "trak", "mdia", "minf", "stbl", "stsz")
        val changed = replace(control, stsz, atom("stsz", ByteArray(4) + be(0) + be(64) + be(10)))
        val again = Faults()
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            pump(extractor, open(changed, 0, again, changed.size.toLong()), changed, again)
        }.contentIsMalformed)
    }

    @Test fun sniffingRefusesUnsafePeeksForBothMp4CandidatesWithoutFallingThrough() {
        // A moov header widens Sniffer's window; a 70KiB ftyp inside it would be allocated before its peek.
        val ftyp = be(100_000) + ascii("moov") + be(70_008) + ascii("ftyp")
        // A free box Sniffer would advance over, past a 16KiB peek budget. The trailing box keeps the
        // known length from clamping Sniffer's widened window below it.
        val free = be(40_016) + ascii("moov") + be(40_008) + ascii("free") + ByteArray(40_000) + atom("free")
        for (candidate in listOf<() -> Extractor>({ FragmentedMp4Extractor() }, { Mp4Extractor() })) {
            assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
                MetadataBoundedExtractor(candidate()).sniff(open(ftyp, 0, Faults(), UNKNOWN))
            }.contentIsMalformed)
            val limited = Mp4Limits(retainedBytes = 16 * 1024)
            assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
                MetadataBoundedExtractor(candidate(), mp4Limits = limited).sniff(open(free, 0, Faults(), free.size.toLong()))
            }.contentIsMalformed)
            // Within the default budget the same box is simply sniffed.
            MetadataBoundedExtractor(candidate()).sniff(open(free, 0, Faults(), free.size.toLong()))
        }
        // The factory reports the refusal; it does not fall through to another candidate.
        val adapter = BundledExtractorsAdapter(PlaybackExtractors())
        try {
            assertThrows(StreamMetadataRefusal::class.java) {
                adapter.init(Reader(ftyp, 0, Faults()), URI, emptyMap(), 0, UNKNOWN, Capture())
            }
        } finally { adapter.release() }
    }

    // Box editing for the committed fixtures. Each fixture keeps media before moov, so movie edits
    // never move the chunk offsets.

    private class Box(val type: String, val start: Int, val size: Int) { val end get() = start + size }

    private fun children(bytes: ByteArray, start: Int, end: Int): List<Box> = buildList {
        var at = start
        while (at < end) {
            val size = int(bytes, at)
            check(size >= 8 && at + size <= end) { "Unexpected fixture box at $at" }
            add(Box(String(bytes, at + 4, 4, Charsets.US_ASCII), at, size))
            at += size
        }
    }

    /** Boxes from the top level down to the first box named by [path]. */
    private fun chain(bytes: ByteArray, vararg path: String): List<Box> {
        var start = 0
        var end = bytes.size
        val boxes = ArrayList<Box>()
        for (name in path) {
            val box = children(bytes, start, end).first { it.type == name }
            boxes += box
            start = box.start + 8; end = box.end
        }
        return boxes
    }

    private fun payload(bytes: ByteArray, chain: List<Box>) = bytes.copyOfRange(chain.last().start + 8, chain.last().end)

    private fun replace(bytes: ByteArray, chain: List<Box>, replacement: ByteArray): ByteArray {
        val target = chain.last()
        val out = bytes.copyOfRange(0, target.start) + replacement + bytes.copyOfRange(target.end, bytes.size)
        for (box in chain.dropLast(1)) put(out, box.start, int(out, box.start) + replacement.size - target.size)
        return out
    }

    /** Appends [addition] inside the last box of [chain], growing it and its ancestors. */
    private fun append(bytes: ByteArray, chain: List<Box>, addition: ByteArray): ByteArray {
        val target = chain.last()
        val out = bytes.copyOfRange(0, target.end) + addition + bytes.copyOfRange(target.end, bytes.size)
        for (box in chain) put(out, box.start, int(out, box.start) + addition.size)
        return out
    }

    private fun mediaBeforeMovie(bytes: ByteArray) {
        val top = children(bytes, 0, bytes.size).map { it.type }
        assertTrue("Fixture must keep mdat before moov: $top", top.indexOf("mdat") in 0 until top.indexOf("moov"))
    }

    /** #721's fixed-size table: [count] samples declared in place, with matching timing and chunk entries. */
    private fun fixedCount(bytes: ByteArray, count: Int): ByteArray {
        val stbl = arrayOf("moov", "trak", "mdia", "minf", "stbl")
        val stsz = chain(bytes, *stbl, "stsz").last().start
        check(int(bytes, stsz) == 20 && int(bytes, stsz + 12) > 0) { "Fixture stsz is not fixed-size" }
        val stts = chain(bytes, *stbl, "stts").last().start
        val stsc = chain(bytes, *stbl, "stsc").last().start
        return bytes.copyOf().also {
            put(it, stsz + 16, count)
            put(it, stts + 12, 1); put(it, stts + 16, count)
            put(it, stsc + 20, count)
        }
    }

    private fun atom(type: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.fold(ByteArray(0)) { all, part -> all + part }
        return be(8 + payload.size) + ascii(type) + payload
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
    private fun be(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun be64(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
    private fun int(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).int
    private fun put(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes, at, 4).putInt(value) }

    // Mutation tests use original tail-moov fixtures so changing metadata preserves chunk offsets.
    // The valid-control matrix explicitly covers both tail and faststart layouts.
    private fun resource(name: String, tail: Boolean = true): ByteArray {
        val actual = if (tail && name != "fragmented.m4a" && !name.startsWith("tail-")) "tail-$name" else name
        return requireNotNull(javaClass.getResourceAsStream("/mp4-admission/$actual")) { actual }.use { it.readBytes() }
    }

    // Input like ProgressiveMediaPeriod's: an interruption or RESULT_SEEK reopens at a position.

    private class Faults(var at: Long = -1) { var furthest = 0L }
    private class Interrupted : IOException("Injected interruption")

    private class Reader(private val bytes: ByteArray, private var at: Long, private val faults: Faults) : DataReader {
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (at >= bytes.size) return C.RESULT_END_OF_INPUT
            var count = minOf(length.toLong(), bytes.size - at).toInt()
            val fail = faults.at
            if (fail >= at && fail < at + count) {
                if (fail == at) { faults.at = -1; throw Interrupted() }
                count = (fail - at).toInt()
            }
            bytes.copyInto(target, offset, at.toInt(), (at + count).toInt())
            at += count
            faults.furthest = maxOf(faults.furthest, at)
            return count
        }
    }

    private class Capture : ExtractorOutput {
        var format: Format? = null
        var map: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(format: Format) { this@Capture.format = format }
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }

    private fun open(bytes: ByteArray, at: Long, faults: Faults, length: Long): ExtractorInput =
        DefaultExtractorInput(Reader(bytes, at, faults), at, length)

    private fun start(extractor: Extractor, output: Capture, bytes: ByteArray, faults: Faults,
        sniff: Boolean = true): ExtractorInput {
        extractor.init(output)
        val input = open(bytes, 0, faults, bytes.size.toLong())
        if (sniff) { assertTrue(extractor.sniff(input)); input.resetPeekPosition() }
        return input
    }

    private fun pump(extractor: Extractor, first: ExtractorInput, bytes: ByteArray, faults: Faults,
        until: () -> Boolean = { false }) {
        var input = first
        val holder = PositionHolder()
        var reads = 0
        while (!until()) {
            check(reads++ < 10_000) { "Extractor did not finish" }
            val result = try { extractor.read(input, holder) } catch (_: Interrupted) {
                input = open(bytes, input.position, faults, bytes.size.toLong())
                continue
            }
            when (result) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> input = open(bytes, holder.position, faults, bytes.size.toLong())
                else -> return
            }
        }
    }

    /** Reads until the seek map is published: the sample tables were built and admitted. */
    private fun published(bytes: ByteArray, limits: Mp4Limits, output: Capture = Capture()): Capture {
        val faults = Faults()
        val extractor = MetadataBoundedExtractor(Mp4Extractor(), mp4Limits = limits)
        pump(extractor, start(extractor, output, bytes, faults), bytes, faults) { output.map != null }
        return output
    }

    private fun drive(adapter: BundledExtractorsAdapter, bytes: ByteArray, at: Long, output: Capture) {
        val faults = Faults()
        adapter.init(Reader(bytes, at, faults), URI, emptyMap(), at, bytes.size.toLong(), output)
        val holder = PositionHolder()
        var reads = 0
        while (true) {
            check(reads++ < 10_000) { "Adapter did not finish" }
            when (adapter.read(holder)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK ->
                    adapter.init(Reader(bytes, holder.position, faults), URI, emptyMap(), holder.position, bytes.size.toLong(), output)
                else -> return
            }
        }
    }

    private companion object {
        val UNKNOWN = C.LENGTH_UNSET.toLong()
        val URI: Uri = Uri.parse("http://fixture.invalid/song")
    }
}
