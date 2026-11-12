@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * Timed ID3 in TS PES and mid-stream ADTS (#704/#729), checked before bytes reach the SampleQueue.
 * Pinned Id3Reader and AdtsReader send every tag byte through sampleData and commit a complete tag
 * with sampleMetadata(offset 0). Per APPLICATION_ID3 output this counts the bytes sent since its last
 * commit, and refuses BEFORE forwarding a write past [maximum]. A truncated PES restart makes no
 * output call, so its uncommitted bytes stay counted. An extractor seek clears every count lazily
 * through one shared generation (no registry or map); a repeated format() keeps the count. Invalid
 * write lengths and metadata ranges are refused before the native queue sees them. Audio and other
 * outputs are returned unwrapped. A resource refusal, not a player or whole-heap bound.
 */
internal class TimedId3Admission(private val maximum: Int = STREAM_ID3_BYTES) {
    init { require(maximum > 0) }
    private var generation = 0L // Extractor read and seek both run on the loading thread.

    fun seek() { generation++ }

    fun output(actual: ExtractorOutput): ExtractorOutput = object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = actual.track(id, type)
            return if (type == C.TRACK_TYPE_METADATA) Guarded(track) else track
        }
        override fun endTracks() = actual.endTracks()
        override fun seekMap(seekMap: SeekMap) = actual.seekMap(seekMap)
    }

    private inner class Guarded(private val actual: TrackOutput) : TrackOutput {
        private var id3 = false
        private var pending = 0L
        private var seen = generation
        private fun pending(): Long {
            if (seen != generation) { seen = generation; pending = 0 }
            return pending
        }

        override fun format(format: Format) {
            // Only the type changes; an unfinished count survives a repeated format.
            id3 = format.sampleMimeType == MimeTypes.APPLICATION_ID3
            actual.format(format)
        }
        override fun durationUs(durationUs: Long) = actual.durationUs(durationUs)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (!id3) return actual.sampleData(input, length, allowEndOfInput, sampleDataPart)
            if (length < 0) throw StreamMetadataRefusal(true)
            // Zero keeps the native zero/end-of-input contract.
            if (length == 0) return actual.sampleData(input, 0, allowEndOfInput, sampleDataPart)
            val room = maximum - pending()
            if (room <= 0) throw StreamMetadataRefusal()
            // A DataReader write may return fewer bytes; never request more than the remaining room.
            val read = actual.sampleData(input, minOf(length.toLong(), room).toInt(), allowEndOfInput, sampleDataPart)
            if (read > 0) pending += read // C.RESULT_END_OF_INPUT is not counted.
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (!id3) return actual.sampleData(data, length, sampleDataPart)
            if (length < 0) throw StreamMetadataRefusal(true)
            if (length.toLong() > maximum - pending()) throw StreamMetadataRefusal()
            actual.sampleData(data, length, sampleDataPart)
            pending += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!id3) return actual.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            // The sample ends [offset] bytes before the last written byte; both must lie within
            // the bytes written since the previous commit.
            val now = pending()
            if (size < 0 || offset < 0 || size.toLong() + offset > now) throw StreamMetadataRefusal(true)
            actual.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            pending = offset.toLong() // Bytes after the committed sample belong to the next one.
        }
    }
}
