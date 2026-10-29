@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.ts.AdtsReader
import androidx.media3.extractor.ts.Id3Reader
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #729 timed ID3 through the actual pinned Id3Reader/AdtsReader/AdtsExtractor into actual SampleQueues,
 * with a scaled cap. Declared tags stay modest (1MiB); no decoder, heap pressure, timing or phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TimedId3AdmissionTest {
    private val cap = 256 * 1024
    private val allocation = 64 * 1024

    /** Real SampleQueues behind an ExtractorOutput, one per track ID, as a media period keeps them. */
    private class Queues : ExtractorOutput, AutoCloseable {
        val allocator = DefaultAllocator(true, 64 * 1024)
        val queues = LinkedHashMap<Int, SampleQueue>()
        val types = LinkedHashMap<Int, Int>()
        override fun track(id: Int, type: Int): TrackOutput {
            types[id] = type
            return queues.getOrPut(id) { SampleQueue.createWithoutDrm(allocator) }
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
        override fun close() { queues.values.forEach(SampleQueue::release); allocator.trim() }
    }

    private fun tag(body: Int) = "ID3".toByteArray() + byteArrayOf(4, 0, 0) +
        ByteArray(4) { ((body ushr ((3 - it) * 7)) and 127).toByte() } + ByteArray(body)
    private fun feed(consume: (ParsableByteArray) -> Unit, bytes: ByteArray, end: Int = bytes.size) {
        var at = 0
        while (at < end) { val next = minOf(end, at + allocation); consume(ParsableByteArray(bytes.copyOfRange(at, next))); at = next }
    }
    private fun id3Reader(output: ExtractorOutput) = Id3Reader(MimeTypes.VIDEO_MP2T).also {
        it.createTracks(output, TsPayloadReader.TrackIdGenerator(0, 1))
    }
    private fun Id3Reader.start(timeUs: Long = 0) = packetStarted(timeUs, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR)

    @Test fun pesId3IsRefusedBeforeTheQueueWhileCompleteRestartedAndSeekedTagsKeepNativeBehavior() {
        // Raw control: the native queue accepts a whole uncommitted 1MiB declaration.
        Queues().use { raw ->
            val rawReader = id3Reader(raw).also { it.start() }
            feed(rawReader::consume, tag(1024 * 1024))
            assertTrue(raw.allocator.totalBytesAllocated >= 1024 * 1024)
            assertEquals(0, raw.queues.values.single().writeIndex)

            // Guarded: refused before the queue grows past the cap.
            Queues().use { guarded ->
                val admission = TimedId3Admission(cap)
                val reader = id3Reader(admission.output(guarded)).also { it.start() }
                assertThrows(StreamMetadataRefusal::class.java) { feed(reader::consume, tag(1024 * 1024)) }
                assertTrue(guarded.allocator.totalBytesAllocated <= cap + allocation)
                val queue = guarded.queues.values.single()
                assertEquals(0, queue.writeIndex)

                // Realistic seek boundary: reset native queued samples while retaining the upstream format.
                // SampleQueue.reset() calls reset(false) in the pinned source.
                admission.seek(); reader.seek(); queue.reset()
                repeat(3) { i -> reader.start(i * 1000L); reader.consume(ParsableByteArray(tag(1000))); reader.packetFinished() }
                assertEquals(3, queue.writeIndex)

                // A data-aligned restart abandons 200KiB without any output call: those bytes stay counted.
                reader.start(5000); feed(reader::consume, tag(300 * 1024), 200 * 1024)
                reader.start(6000)
                assertThrows(StreamMetadataRefusal::class.java) { feed(reader::consume, tag(300 * 1024), 100 * 1024) }
                assertEquals(3, queue.writeIndex)
                admission.seek(); reader.seek(); queue.reset()
                reader.start(7000); feed(reader::consume, tag(300 * 1024), 200 * 1024) // Within the cap again.
            }
        }
    }

    @Test fun midStreamAdtsId3IsRefusedButCompleteTagsAndAudioOutputsStayNative() {
        Queues().use { out ->
            val admission = TimedId3Admission(cap)
            val reader = AdtsReader(true, MimeTypes.AUDIO_AAC)
            reader.createTracks(admission.output(out), TsPayloadReader.TrackIdGenerator(0, 1))
            assertEquals(listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA), out.types.values.toList())
            // Audio is never wrapped: the same output instance reaches the caller.
            assertSame(out.track(0, C.TRACK_TYPE_AUDIO), admission.output(out).track(0, C.TRACK_TYPE_AUDIO))
            val id3 = out.queues.getValue(1)
            reader.packetStarted(0, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR)
            reader.consume(ParsableByteArray(tag(1000)))
            assertEquals(1, id3.writeIndex) // A complete tag commits normally.
            assertThrows(StreamMetadataRefusal::class.java) { feed(reader::consume, tag(1024 * 1024)) }
            assertTrue(out.allocator.totalBytesAllocated <= cap + 2 * allocation)
            assertEquals(1, id3.writeIndex)
        }
    }

    @Test fun writeAndCommitContractsAreCheckedBeforeForwarding() {
        val calls = ArrayList<String>()
        val recording = object : TrackOutput {
            override fun format(format: Format) {}
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                calls += "read $length"; return input.read(ByteArray(maxOf(length, 1)), 0, length)
            }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                calls += "data $length"; data.skipBytes(length)
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                calls += "commit $size $offset"
            }
        }
        val outer = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = recording
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        val admission = TimedId3Admission(10)
        val output = admission.output(outer)
        assertSame(recording, output.track(0, C.TRACK_TYPE_AUDIO))
        val id3Format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build()
        val track = output.track(1, C.TRACK_TYPE_METADATA).also { it.format(id3Format) }
        val partial = DataReader { _, _, length -> minOf(length, 6) }
        val ended = DataReader { _, _, _ -> C.RESULT_END_OF_INPUT }
        assertEquals(6, track.sampleData(partial, 8, true))           // A short read counts what arrived.
        assertEquals(C.RESULT_END_OF_INPUT, track.sampleData(ended, 4, true)) // End of input counts nothing.
        assertEquals(0, track.sampleData(partial, 0, true))           // Zero keeps the native contract.
        assertEquals(4, track.sampleData(partial, 8, true))           // Only the remaining room is requested.
        track.format(id3Format)                                        // A repeated format keeps the count.
        val before = calls.toList()
        assertThrows(StreamMetadataRefusal::class.java) { track.sampleData(partial, 1, true) }
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) { track.sampleData(partial, -1, true) }.contentIsMalformed)
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) { track.sampleData(ParsableByteArray(1), -1, TrackOutput.SAMPLE_DATA_PART_MAIN) }.contentIsMalformed)
        for ((size, offset) in listOf(11 to 0, 6 to -1, -1 to 0, 7 to 4)) assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            track.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, size, offset, null)
        }.contentIsMalformed)
        assertEquals("Invalid writes and ranges never reach the native queue", before, calls)
        track.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, 6, 4, null) // The next sample's 4 bytes stay counted.
        track.sampleData(ParsableByteArray(6), 6)
        assertThrows(StreamMetadataRefusal::class.java) { track.sampleData(ParsableByteArray(1), 1) }
        admission.seek()
        track.sampleData(ParsableByteArray(10), 10)
        // Another metadata format passes through unchanged, whatever its size.
        val other = output.track(2, C.TRACK_TYPE_METADATA).also { it.format(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build()) }
        other.sampleData(ParsableByteArray(100), 100)
        assertEquals(listOf("read 8", "read 4", "read 0", "read 4", "commit 6 4", "data 6", "data 10", "data 100"), calls)
    }

    @Test fun theDefaultAdtsExtractorIsGuardedThroughMetadataBoundedExtractor() {
        val bytes = byteArrayOf(0) + tag(1024 * 1024) // Mid-stream: not the leading tag peeked at position 0.
        Queues().use { out ->
            val actual = DefaultExtractorsFactory().createExtractors().single { it is AdtsExtractor }
            val extractor = MetadataBoundedExtractor(actual, timedId3Bytes = cap)
            try {
                extractor.init(out)
                assertEquals(listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA), out.types.values.toList())
                var at = 0
                val input = DefaultExtractorInput({ target, offset, length ->
                    if (length == 0) 0 else if (at >= bytes.size) C.RESULT_END_OF_INPUT
                    else minOf(length, bytes.size - at).also { bytes.copyInto(target, offset, at, at + it); at += it }
                }, 0, bytes.size.toLong())
                assertThrows(StreamMetadataRefusal::class.java) {
                    val holder = PositionHolder()
                    repeat(10_000) { if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return@assertThrows }
                }
                assertTrue(out.allocator.totalBytesAllocated <= cap + 2 * allocation)
                assertEquals(0, out.queues.getValue(1).writeIndex)
                extractor.seek(0, 0) // Seek is still forwarded to the native extractor.
            } finally { extractor.release() }
        }
    }
}
