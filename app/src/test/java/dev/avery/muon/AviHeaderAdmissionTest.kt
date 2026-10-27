@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
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
import androidx.media3.extractor.avi.AviExtractor
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #734 hdrl admission through the actual default AviExtractor. Header snippets are synthetic and
 * modest; the fixture is a tiny FFmpeg PCM AVI. Demux only: no audio decoding, heap pressure or phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AviHeaderAdmissionTest {
    @Test fun rawNativeAllocatesTheDeclaredHdrlBodyBeforeItsMissingBytesFail() {
        // Only 32 bytes exist; the hdrl LIST declares a modest 128KiB body.
        val bytes = unbacked(128 * 1024)
        for (known in listOf(true, false)) {
            val source = Source(bytes)
            val output = Capture()
            val raw = native()
            try {
                raw.init(output)
                assertThrows(EOFException::class.java) { pump(raw, source, known) }
                assertTrue("Native body array, observed as its read target", 128 * 1024 in source.targets)
                assertEquals(0, output.tracks)
            } finally { raw.release() }
        }
    }

    @Test fun guardedDeclarationsAreRefusedBeforeTheNativeBodyArrayExists() {
        val bytes = unbacked(128 * 1024)
        // Unknown length: over the header budget. Known length: not backed by the stream.
        for ((known, limits) in listOf(false to AviHeaderLimits(hdrlBytes = 64 * 1024), true to AviHeaderLimits())) {
            val source = Source(bytes)
            val output = Capture()
            val guarded = MetadataBoundedExtractor(native(), aviLimits = limits)
            try {
                guarded.init(output)
                val error = assertThrows(StreamMetadataRefusal::class.java) { pump(guarded, source, known) }
                assertEquals(known, error.contentIsMalformed)
                assertTrue("Only the 12-byte header was read", source.targets.all { it <= 12 })
                assertEquals(0, output.tracks)
            } finally { guarded.release() }
        }
        // Native int size - 4: negative, or overflowing from Int.MIN_VALUE to a huge positive array.
        for (size in listOf(-1, 3, Int.MIN_VALUE)) {
            val source = Source(unbacked(0).also { put(it, 16, size) })
            val guarded = MetadataBoundedExtractor(native())
            try {
                guarded.init(Capture())
                assertTrue(assertThrows(StreamMetadataRefusal::class.java) { pump(guarded, source) }.contentIsMalformed)
                assertTrue(source.targets.all { it <= 12 })
            } finally { guarded.release() }
        }
    }

    @Test fun realPcmFixtureExtractsSeeksAndRestartsThroughTheDefaultFactory() {
        val bytes = fixture()
        val output = Capture()
        val adapter = BundledExtractorsAdapter(PlaybackExtractors())
        try {
            drive(adapter, bytes, 0, output)
            val format = requireNotNull(output.format)
            assertEquals(MimeTypes.AUDIO_RAW, format.sampleMimeType)
            assertEquals(48_000, format.sampleRate)
            assertEquals(1, format.channelCount)
            val map = requireNotNull(output.map)
            assertTrue("Original fixture has idx1 and must exercise a real nonzero seek", map.isSeekable)
            run {
                // A nonzero reopen continues with the admitted header.
                val point = map.getSeekPoints(map.durationUs / 2).first
                adapter.seek(point.position, point.timeUs)
                drive(adapter, bytes, point.position, output)
            }
            // Seek to the start: the prepared native reader finds movi again without rereading hdrl.
            adapter.seek(0, 0)
            drive(adapter, bytes, 0, output)
            assertEquals(MimeTypes.AUDIO_RAW, output.format?.sampleMimeType)
        } finally { adapter.release() }
    }

    @Test fun listDepthChunkStreamBudgetsAndParentDamageAreRefusedBeforeTheNativeGraph() {
        fun list(type: String, vararg children: ByteArray) = chunk("LIST", children.fold(ascii(type)) { all, child -> all + child })
        val twoDeep = list("test", list("test"))
        val threeDeep = list("test", list("test", list("test")))
        val strls = list("strl") + list("strl") + list("strl")
        val junk = (1..64).fold(ByteArray(0)) { all, _ -> all + chunk("JUNK", ByteArray(0)) }
        // Inside its limits each addition still reaches the native track graph.
        val tight = AviHeaderLimits(depth = 2, streams = 4)
        for (extra in listOf(twoDeep, strls, junk)) {
            assertEquals(MimeTypes.AUDIO_RAW, published(withHdrl(extra), tight).format?.sampleMimeType)
        }
        val budgets = listOf(
            Triple("depth", threeDeep, tight),
            Triple("chunks", junk, AviHeaderLimits(chunks = 32)),
            Triple("streams", strls, AviHeaderLimits(streams = 2)),
        )
        for ((name, extra, limits) in budgets) {
            val output = Capture()
            assertFalse(name, assertThrows(name, StreamMetadataRefusal::class.java) {
                published(withHdrl(extra), limits, output)
            }.contentIsMalformed)
            assertEquals(name, 0, output.tracks)
            assertFalse(name, output.ended)
        }
        // A child running past its LIST, which native would read across, and a negative chunk size.
        val crossing = ascii("LIST") + le(13) + ascii("test") + ascii("JUNK") + le(64) + ByteArray(64)
        val negative = ascii("JUNK") + le(-4) + ByteArray(8)
        for ((name, extra) in listOf("crossing" to crossing, "negative" to negative)) {
            val output = Capture()
            assertTrue(name, assertThrows(name, StreamMetadataRefusal::class.java) {
                published(withHdrl(extra), AviHeaderLimits(), output)
            }.contentIsMalformed)
            assertEquals(name, 0, output.tracks)
        }
    }

    @Test fun indexDeclarationIsAdmittedBeforeItsNativeBodyAndRowArrays() {
        val bytes = fixture()
        val movi = String(bytes, Charsets.ISO_8859_1).indexOf("movi")
        val indexAt = movi + int(bytes, movi - 4)
        assertEquals("idx1", String(bytes, indexAt, 4, Charsets.US_ASCII))
        val missing = bytes.copyOf(indexAt + 8).also { put(it, indexAt + 4, 128 * 1024) }
        for (known in listOf(false, true)) {
            val raw = native(); val source = Source(missing)
            try {
                raw.init(Capture())
                assertThrows(EOFException::class.java) { pump(raw, source, known) }
                assertTrue(128 * 1024 in source.targets)
            } finally { raw.release() }
            val guarded = MetadataBoundedExtractor(native(), aviLimits =
                if (known) AviHeaderLimits() else AviHeaderLimits(indexRows = 4))
            val limited = Source(missing); val out = Capture()
            try {
                guarded.init(out)
                val error = assertThrows(StreamMetadataRefusal::class.java) { pump(guarded, limited, known) }
                assertEquals(known, error.contentIsMalformed)
                assertFalse(128 * 1024 in limited.targets)
                assertEquals(MimeTypes.AUDIO_RAW, out.format?.sampleMimeType)
                assertNull(out.map)
            } finally { guarded.release() }
        }
        val rows = int(bytes, indexAt + 4) / 16
        assertEquals(3, rows)
        for (budget in listOf(rows, rows - 1)) {
            val guarded = MetadataBoundedExtractor(native(), aviLimits = AviHeaderLimits(indexRows = budget))
            val out = Capture()
            try {
                guarded.init(out)
                if (budget == rows) { pump(guarded, Source(bytes)); assertTrue(requireNotNull(out.map).isSeekable) }
                else assertThrows(StreamMetadataRefusal::class.java) { pump(guarded, Source(bytes)) }
            } finally { guarded.release() }
        }
    }

    @Test fun negativeUnknownIndexSearchChunksCannotSeekBackToTheSameHeader() {
        val bytes = fixture()
        val movi = String(bytes, Charsets.ISO_8859_1).indexOf("movi")
        val at = movi + int(bytes, movi - 4)
        val damaged = bytes.copyOf().also { ascii("JUNK").copyInto(it, at); put(it, at + 4, -8) }
        val guarded = MetadataBoundedExtractor(native())
        try {
            guarded.init(Capture())
            assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
                pump(guarded, Source(damaged))
            }.contentIsMalformed)
        } finally { guarded.release() }
    }

    @Test fun anInterruptedBodyIsCheckedAgainWhenItsRetryDeliversDifferentBytes() {
        val bytes = fixture()
        // Fault inside the hdrl body (it starts at 24); native keeps its size and reads it again.
        val same = Capture()
        published(bytes, AviHeaderLimits(), same, fault = 28)
        assertEquals(MimeTypes.AUDIO_RAW, same.format?.sampleMimeType)
        // The retry delivers the first child's size damaged: no earlier admission may be reused.
        val damaged = bytes.copyOf().also { put(it, 28, 0x7fff0000) }
        val output = Capture()
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            published(bytes, AviHeaderLimits(), output, fault = 28, retry = damaged)
        }.contentIsMalformed)
        assertEquals(0, output.tracks)
    }

    // Inputs like ProgressiveMediaPeriod's: an interruption or RESULT_SEEK reopens at a position.

    private class Interrupted : IOException("Injected interruption")

    private class Source(var bytes: ByteArray, var fault: Long = -1) {
        val targets = ArrayList<Int>()
        fun open(at: Long, known: Boolean = true): ExtractorInput {
            val data = bytes
            var position = at
            val reader = DataReader { target, offset, length ->
                if (length == 0) return@DataReader 0
                if (position >= data.size) return@DataReader C.RESULT_END_OF_INPUT
                var count = minOf(length.toLong(), data.size - position).toInt()
                if (fault >= position && fault < position + count) {
                    if (fault == position) { fault = -1; throw Interrupted() }
                    count = (fault - position).toInt()
                }
                data.copyInto(target, offset, position.toInt(), (position + count).toInt())
                position += count
                count
            }
            val streamLength = if (known) data.size.toLong() else C.LENGTH_UNSET.toLong()
            // Observes each actual read target, including the native hdrl body array.
            return object : ForwardingExtractorInput(DefaultExtractorInput(reader, at, streamLength)) {
                override fun readFully(target: ByteArray, offset: Int, length: Int) {
                    targets += target.size
                    super.readFully(target, offset, length)
                }
                override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
                    targets += target.size
                    return super.readFully(target, offset, length, allowEndOfInput)
                }
            }
        }
    }

    private class Capture : ExtractorOutput {
        var format: Format? = null
        var map: SeekMap? = null
        var tracks = 0
        var ended = false
        override fun track(id: Int, type: Int): TrackOutput {
            tracks++
            return object : TrackOutput by DiscardingTrackOutput() {
                override fun format(format: Format) { this@Capture.format = format }
            }
        }
        override fun endTracks() { ended = true }
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }

    private fun native() = DefaultExtractorsFactory().createExtractors().single { it is AviExtractor }

    private fun pump(extractor: Extractor, source: Source, known: Boolean = true,
        retry: ByteArray? = null, until: () -> Boolean = { false }) {
        var input = source.open(0, known)
        val holder = PositionHolder()
        var reads = 0
        while (!until()) {
            check(reads++ < 10_000) { "AVI input did not finish" }
            val result = try { extractor.read(input, holder) } catch (_: Interrupted) {
                if (retry != null) source.bytes = retry
                input = source.open(input.position, known)
                continue
            }
            when (result) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> input = source.open(holder.position, known)
                else -> return
            }
        }
    }

    /** Reads until the native header graph has published its format. */
    private fun published(bytes: ByteArray, limits: AviHeaderLimits, output: Capture = Capture(),
        fault: Long = -1, retry: ByteArray? = null): Capture {
        val extractor = MetadataBoundedExtractor(native(), aviLimits = limits)
        try {
            extractor.init(output)
            pump(extractor, Source(bytes, fault), retry = retry) { output.format != null }
        } finally { extractor.release() }
        return output
    }

    private fun drive(adapter: BundledExtractorsAdapter, bytes: ByteArray, at: Long, output: Capture) {
        fun reader(start: Long): DataReader {
            var position = start.toInt()
            return DataReader { target, offset, length ->
                if (length == 0) 0 else if (position >= bytes.size) C.RESULT_END_OF_INPUT
                else minOf(length, bytes.size - position).also { bytes.copyInto(target, offset, position, position + it); position += it }
            }
        }
        adapter.init(reader(at), URI, emptyMap(), at, bytes.size.toLong(), output)
        val holder = PositionHolder()
        var reads = 0
        while (true) {
            check(reads++ < 10_000) { "Adapter did not finish" }
            when (adapter.read(holder)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> adapter.init(reader(holder.position), URI, emptyMap(), holder.position, bytes.size.toLong(), output)
                else -> return
            }
        }
    }

    // Synthetic RIFF headers, and additions appended to the fixture's own hdrl body. The fixture's
    // idx1 offsets are relative to movi, so growing hdrl and RIFF together keeps it valid.

    private fun unbacked(body: Int): ByteArray =
        ascii("RIFF") + le(4 + 12 + 8) + ascii("AVI ") + ascii("LIST") + le(body + 4) + ascii("hdrl") + ByteArray(8)

    private fun withHdrl(extra: ByteArray): ByteArray {
        val bytes = fixture()
        check(String(bytes, 12, 4, Charsets.US_ASCII) == "LIST" && String(bytes, 20, 4, Charsets.US_ASCII) == "hdrl")
        val end = 20 + int(bytes, 16)
        val out = bytes.copyOfRange(0, end) + extra + bytes.copyOfRange(end, bytes.size)
        put(out, 16, int(bytes, 16) + extra.size)
        put(out, 4, int(bytes, 4) + extra.size)
        return out
    }

    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/avi-admission/control.avi")).use { it.readBytes() }
    private fun chunk(type: String, payload: ByteArray) = ascii(type) + le(payload.size) + payload
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun int(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun put(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value) }

    private companion object {
        val URI: Uri = Uri.parse("http://fixture.invalid/control.avi")
    }
}
