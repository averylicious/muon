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
import androidx.media3.extractor.flv.FlvExtractor
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #736 through the actual default FlvExtractor. Synthetic FLV/AMF snippets are modest and are not
 * encoded media; the fixture is a tiny FFmpeg AAC FLV. Demux only: no decoding, heap pressure or phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FlvMetadataAdmissionTest {
    @Test fun startupOffsetIsBoundedForSniffAndForAnUnsniffedRead() {
        val far = header(128 * 1024)
        for (known in listOf(false, true)) {
            val raw = Source(far)
            assertThrows(EOFException::class.java) { native().sniff(raw.open(0, known)) }
            assertTrue("Native peek storage grew for the declared offset", raw.largest >= 128 * 1024)
            // At the lookahead limit another format candidate may still try.
            val bounded = Source(far)
            val limits = FlvMetadataLimits(lookaheadBytes = 64 * 1024)
            assertFalse(MetadataBoundedExtractor(native(), flvLimits = limits).sniff(bounded.open(0, known)))
            assertTrue(bounded.largest <= 64 * 1024)
        }
        // A short known-length file keeps its ordinary EOF, before any declared lookahead is buffered.
        val short = Source(far)
        assertThrows(EOFException::class.java) { MetadataBoundedExtractor(native()).sniff(short.open(0, true)) }
        assertTrue(short.largest <= 64 * 1024)
        // A single-candidate factory reads without sniffing; the header read bounds the same offset.
        for ((offset, malformed) in listOf(128 * 1024 to false, 5 to true)) {
            val guarded = MetadataBoundedExtractor(native(), flvLimits = FlvMetadataLimits(lookaheadBytes = 64 * 1024))
            guarded.init(Capture())
            assertEquals(malformed, assertThrows(StreamMetadataRefusal::class.java) {
                pump(guarded, Source(header(offset)), known = false)
            }.contentIsMalformed)
        }
    }

    @Test fun aBufferedScriptDeclarationIsAdmittedBeforeItsNativeArray() {
        // Only the tag header exists; it declares a modest 128KiB SCRIPT body.
        val bytes = header(9) + be32(0) + tagHeader(18, 128 * 1024)
        for (known in listOf(false, true)) {
            val raw = Source(bytes)
            assertThrows(EOFException::class.java) { pump(native().also { it.init(Capture()) }, raw, known) }
            assertTrue("Native tag array, observed as its read target", 128 * 1024 in raw.targets)
            // Unknown length: over the script budget. Known length: not backed by the stream.
            val limits = if (known) FlvMetadataLimits() else FlvMetadataLimits(scriptBytes = 64 * 1024)
            val guarded = Source(bytes)
            val extractor = MetadataBoundedExtractor(native(), flvLimits = limits).also { it.init(Capture()) }
            assertEquals(known, assertThrows(StreamMetadataRefusal::class.java) { pump(extractor, guarded, known) }.contentIsMalformed)
            assertTrue("Only FLV and tag headers were read", guarded.targets.all { it <= 11 })
        }
        // A seek position can be supplied independently of actual body bytes. Reject wide
        // header/body arithmetic before the actual native reader can wrap its position or allocate.
        for ((start, size, expectedReads) in listOf(
            Triple(Long.MAX_VALUE - 5, 0, 0), Triple(Long.MAX_VALUE - 12, 2, 11),
        )) {
            val bytesAtPosition = tagHeader(18, size)
            var at = 0
            val reader = DataReader { target, offset, length ->
                if (length == 0) 0 else if (at == bytesAtPosition.size) C.RESULT_END_OF_INPUT else
                    minOf(length, bytesAtPosition.size - at).also {
                        bytesAtPosition.copyInto(target, offset, at, at + it); at += it
                    }
            }
            val input = DefaultExtractorInput(reader, start, C.LENGTH_UNSET.toLong())
            val guarded = MetadataBoundedExtractor(native())
            try {
                guarded.init(Capture()); guarded.seek(start, 0)
                assertTrue(assertThrows(StreamMetadataRefusal::class.java) { guarded.read(input, PositionHolder()) }.contentIsMalformed)
                assertEquals(expectedReads, at)
            } finally { guarded.release() }
        }
    }

    @Test fun realAacFixtureMatchesTheRawReaderAndRestartsThroughTheDefaultFactory() {
        val bytes = fixture()
        val raw = Capture()
        val rawAdapter = BundledExtractorsAdapter(DefaultExtractorsFactory())
        try { drive(rawAdapter, bytes, 0, raw) } finally { rawAdapter.release() }
        val output = Capture()
        val adapter = BundledExtractorsAdapter(PlaybackExtractors())
        try {
            drive(adapter, bytes, 0, output)
            val format = requireNotNull(output.format)
            assertEquals(MimeTypes.AUDIO_AAC, format.sampleMimeType)
            assertEquals(48_000, format.sampleRate)
            assertEquals(1, format.channelCount)
            val map = requireNotNull(output.map)
            // The fixture's own onMetaData was admitted and decoded natively, unchanged.
            assertEquals(requireNotNull(raw.map).durationUs, map.durationUs)
            assertEquals(raw.samples, output.samples)
            assertTrue(output.samples > 0)
            // Reopen from the start: native rereads the FLV header (rechecked) and, with its seek map
            // already output, skips the script tag again. Samples are extracted again.
            adapter.seek(0, 0)
            drive(adapter, bytes, 0, output)
            assertTrue(output.samples > raw.samples)
            assertEquals(MimeTypes.AUDIO_AAC, output.format?.sampleMimeType)
        } finally { adapter.release() }
    }

    @Test fun onMetaDataCollectionsShareSlotAndDepthBudgetsBeforeNativeDecode() {
        val duration = "duration" to number(1.5)
        // Raw route: native creates ArrayList(count), then reads past its 1-entry tag and fails.
        val unbacked = script(name() + byteArrayOf(8) + be32(1) + str("times") + byteArrayOf(10) + be32(1000))
        val failure = assertThrows(RuntimeException::class.java) { pump(native().also { it.init(Capture()) }, Source(unbacked)) }
        assertTrue(failure.stackTrace.any { it.methodName == "readAmfStrictArray" })
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) { guardedRun(unbacked, FlvMetadataLimits()) }.contentIsMalformed)

        // ECMA capacity, strict-array capacity and object entries draw from one budget.
        val cases = listOf(
            Triple("strict", ecma(duration, "times" to strict(*Array(8) { number(it.toDouble()) })), 2 + 8),
            Triple("map", ecma(duration, "a" to number(1.0), "b" to number(2.0)), 3),
            Triple("objects", ecma(duration, "o" to obj("p" to obj("q" to number(1.0)))), 2 + 1 + 1),
        )
        for ((label, metadata, slots) in cases) {
            val bytes = script(name() + metadata)
            assertEquals(label, 1_500_000L, guardedRun(bytes, FlvMetadataLimits(slots = slots)).map?.durationUs)
            assertFalse(label, assertThrows(label, StreamMetadataRefusal::class.java) {
                guardedRun(bytes, FlvMetadataLimits(slots = slots - 1))
            }.contentIsMalformed)
        }
        // The ECMA array is depth 1 and each nested object one deeper.
        val nested = script(name() + ecma(duration, "o" to obj("p" to obj("q" to number(1.0)))))
        assertEquals(1_500_000L, guardedRun(nested, FlvMetadataLimits(depth = 3)).map?.durationUs)
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            guardedRun(nested, FlvMetadataLimits(depth = 2))
        }.contentIsMalformed)
    }

    @Test fun scriptsNativeIgnoresStayIgnoredIncludingAfterTheSeekMap() {
        val huge = byteArrayOf(10) + be32(Int.MAX_VALUE) // Refused if it were ever scanned.
        // Another name, a non-ECMA metadata value and onMetaData with no value are ignored natively.
        for (body in listOf(name("onCuePoint") + huge, name() + huge, name())) {
            assertNull(guardedRun(script(body), FlvMetadataLimits(slots = 1)).map)
        }
        // An unsupported value type has no payload, exactly like native readAmfData returning null.
        val unknown = script(name() + byteArrayOf(8) + be32(2) + str("x") + byteArrayOf(12) + str("duration") + number(2.0))
        assertEquals(rawRun(unknown).map?.durationUs, guardedRun(unknown, FlvMetadataLimits()).map?.durationUs)
        assertEquals(2_000_000L, guardedRun(unknown, FlvMetadataLimits()).map?.durationUs)
        // Once a seek map is out, native skips later scripts: their size and counts are not refused.
        val first = name() + ecma("duration" to number(1.5))
        val later = name() + byteArrayOf(8) + be32(1) + str("t") + huge + ByteArray(80)
        val both = header(9) + be32(0) + tag(18, first) + tag(18, later)
        assertEquals(1_500_000L, guardedRun(both, FlvMetadataLimits(scriptBytes = 64, slots = 4)).map?.durationUs)
    }

    @Test fun everyDeliveredScriptBodyIsScannedWithinItsOwnTagIncludingAChangedRetry() {
        val good = script(name() + ecma("duration" to number(1.5), "times" to strict(number(0.0), number(1.0))))
        val bodyAt = 9L + 4 + 11
        // Interrupted inside the body: the unchanged retry is scanned again and decodes natively.
        assertEquals(1_500_000L, guardedRun(good, FlvMetadataLimits(slots = 4), fault = bodyAt + 4).map?.durationUs)
        // Same size, but the retry declares one more strict entry than the budget: nothing was cached.
        val changed = script(name() + ecma("duration" to number(1.5), "times" to byteArrayOf(10) + be32(3) + number(0.0) + number(1.0)))
        assertEquals(good.size, changed.size)
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            guardedRun(good, FlvMetadataLimits(slots = 4), fault = bodyAt + 4, retry = changed)
        }.contentIsMalformed)

        // Native reuses a larger tag array, but this pinned ParsableByteArray checks the delivered
        // limit and throws. The guard reports malformed input before that native parser runs.
        val first = name("onCuePoint") + ByteArray(64)
        val second = name() + byteArrayOf(8) + be32(1) + str("duration") + byteArrayOf(0) + number(3.0).copyOfRange(1, 5)
        val reused = header(9) + be32(0) + tag(18, first) + tag(18, second)
        assertThrows(IndexOutOfBoundsException::class.java) { rawRun(reused) }
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) { guardedRun(reused, FlvMetadataLimits()) }.contentIsMalformed)
    }

    // Inputs like ProgressiveMediaPeriod's: an interruption or RESULT_SEEK reopens at a position.

    private class Interrupted : IOException("Injected interruption")

    private class Source(var bytes: ByteArray, var fault: Long = -1) {
        val targets = ArrayList<Int>() // readFully targets, including native tag arrays.
        var largest = 0                // Storage passed to the DataReader, including peek buffers.
        fun open(at: Long, known: Boolean = true): ExtractorInput {
            val data = bytes
            var position = at
            val reader = DataReader { target, offset, length ->
                largest = maxOf(largest, target.size)
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
        var samples = 0
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(format: Format) { this@Capture.format = format }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                samples++
            }
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }

    private fun native() = DefaultExtractorsFactory().createExtractors().single { it is FlvExtractor }

    private fun pump(extractor: Extractor, source: Source, known: Boolean = true, retry: ByteArray? = null) {
        var input = source.open(0, known)
        val holder = PositionHolder()
        var reads = 0
        while (true) {
            check(reads++ < 10_000) { "FLV input did not finish" }
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

    private fun rawRun(bytes: ByteArray): Capture = Capture().also { output ->
        val extractor = native()
        extractor.init(output)
        pump(extractor, Source(bytes))
    }

    private fun guardedRun(bytes: ByteArray, limits: FlvMetadataLimits, fault: Long = -1, retry: ByteArray? = null): Capture =
        Capture().also { output ->
            val extractor = MetadataBoundedExtractor(native(), flvLimits = limits)
            extractor.init(output)
            pump(extractor, Source(bytes, fault), retry = retry)
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

    // FLV: 9-byte header (audio flag), each tag followed by its 4-byte previous-tag size. AMF0 is big-endian.

    private fun header(dataOffset: Int) = ascii("FLV") + byteArrayOf(1, 4) + be32(dataOffset)
    private fun tagHeader(type: Int, size: Int) =
        byteArrayOf(type.toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + ByteArray(7)
    private fun tag(type: Int, body: ByteArray) = tagHeader(type, body.size) + body + be32(11 + body.size)
    private fun script(body: ByteArray) = header(9) + be32(0) + tag(18, body)
    private fun str(text: String) = byteArrayOf((text.length ushr 8).toByte(), text.length.toByte()) + ascii(text)
    private fun name(text: String = "onMetaData") = byteArrayOf(2) + str(text)
    private fun number(value: Double) = byteArrayOf(0) + ByteBuffer.allocate(8).putDouble(value).array()
    private fun entries(values: Array<out Pair<String, ByteArray>>) =
        values.fold(ByteArray(0)) { all, (key, value) -> all + str(key) + value }
    private fun ecma(vararg values: Pair<String, ByteArray>) = byteArrayOf(8) + be32(values.size) + entries(values)
    private fun obj(vararg values: Pair<String, ByteArray>) = byteArrayOf(3) + entries(values) + str("") + byteArrayOf(9)
    private fun strict(vararg values: ByteArray) =
        byteArrayOf(10) + be32(values.size) + values.fold(ByteArray(0)) { all, value -> all + value }
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
    private fun be32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/flv-admission/control.flv")).use { it.readBytes() }

    private companion object {
        val URI: Uri = Uri.parse("http://fixture.invalid/control.flv")
    }
}
