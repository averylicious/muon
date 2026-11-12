@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.AdtsReader
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.Id3Reader
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native pre-decoder buffering only; no playback, network, OOM or whole-player bound claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TimedId3BufferCharacterizationTest {
    @Test fun transportStreamId3QueuesPartialTagBeforeCommittingAnySample() {
        characterize(Id3Reader(MimeTypes.VIDEO_MP2T))
    }

    @Test fun adtsId3QueuesPartialTagBeforeCommittingAnySample() {
        characterize(AdtsReader(true, MimeTypes.AUDIO_AAC))
    }

    private fun characterize(reader: ElementaryStreamReader) {
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val queue = SampleQueue.createWithoutDrm(allocator)
        var metadataTracks = 0
        reader.createTracks(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_METADATA) {
                metadataTracks++
                queue
            } else DiscardingTrackOutput()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        }, TsPayloadReader.TrackIdGenerator(0, 1))
        try {
            assertEquals(1, metadataTracks)
            assertEquals(MimeTypes.APPLICATION_ID3, queue.upstreamFormat?.sampleMimeType)
            reader.packetStarted(0L, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR)
            // Declared 8 MiB body; inject only 5 MiB using one reusable 64 KiB input block.
            val declared = 8 * 1024 * 1024
            val header = byteArrayOf(73, 68, 51, 4, 0, 0,
                ((declared ushr 21) and 127).toByte(), ((declared ushr 14) and 127).toByte(),
                ((declared ushr 7) and 127).toByte(), (declared and 127).toByte())
            reader.consume(ParsableByteArray(header))
            val block = ParsableByteArray(ByteArray(64 * 1024))
            repeat(5 * 1024 * 1024 / block.limit()) {
                block.setPosition(0)
                reader.consume(block)
                assertEquals(0, block.bytesLeft())
            }
            reader.packetFinished()
            assertEquals("No complete metadata sample reached a renderer", 0, queue.writeIndex)
            assertTrue("Actual native sample storage exceeds the later 4 MiB decoder guard",
                allocator.totalBytesAllocated >= 5 * 1024 * 1024)
        } finally {
            queue.release()
        }
        assertEquals("Queue release returns all live native allocations", 0, allocator.totalBytesAllocated)
        allocator.trim()
    }
}
