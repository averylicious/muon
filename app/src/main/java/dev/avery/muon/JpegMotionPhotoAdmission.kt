@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.MotionPhotoMetadata
import java.io.InterruptedIOException

/** The pinned default JPEG creates an internal ordinary MP4 reader, outside the app's factory.
 * Observe its actual MP4 sniff/header calls through public input/output forwarders. The first
 * 8-byte allow-EOF peek is the inner Sniffer's first atom; JPEG marker/XMP peeks use other shapes.
 * Before reads begin, verify that offset against the native image track's MotionPhotoMetadata.
 * Relative positions are private to the admission view; native bytes/positions/seek maps are intact.
 * This does not bound standalone image samples/decoding or every codec/whole-process allocation.
 */
internal class JpegMotionPhotoAdmission(private val limits: Mp4Limits,
    private val preambleBytes: Long = MP4_RETAINED_BYTES) {
    init { require(preambleBytes > 0) }
    private var start = -1L
    private var reading = false
    private val mp4 = Mp4MetadataAdmission(limits)

    fun seek(position: Long) {
        if (position == 0L) { start = -1; reading = false; mp4.seek(0) }
        else if (reading) mp4.seek(position - start)
    }

    fun sniffInput(input: ExtractorInput): ExtractorInput = Preamble(input)

    fun output(actual: ExtractorOutput): ExtractorOutput = object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = actual.track(id, type)
            if (type != C.TRACK_TYPE_IMAGE) return track
            return object : TrackOutput by track {
                override fun format(format: Format) {
                    format.metadata?.let { metadata ->
                        for (i in 0 until metadata.length()) {
                            val motion = metadata[i] as? MotionPhotoMetadata ?: continue
                            if (start < 0 || motion.videoStartPosition != start) throw StreamMetadataRefusal(true)
                            reading = true
                        }
                    }
                    track.format(format)
                }
            }
        }
        override fun endTracks() = actual.endTracks()
        override fun seekMap(seekMap: SeekMap) = actual.seekMap(seekMap)
    }

    fun input(actual: ExtractorInput): ExtractorInput = object : Preamble(actual) {
        // The native inner sniff completes in this same read invocation; a retry gets a fresh view.
        private var sniff: ExtractorInput? = null
        private fun reader(): ExtractorInput = if (reading) mp4.input(Relative(actual)) else actual
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (!reading && sniff == null && offset == 0 && length == 8 && allowEndOfInput && target.size == 64) {
                if (actual.peekPosition != actual.position) throw StreamMetadataRefusal(true)
                if (start >= 0 && start != actual.position) throw StreamMetadataRefusal(true)
                start = actual.position
                sniff = Mp4SniffInput(Relative(actual), limits.retainedBytes)
            }
            return sniff?.peekFully(target, offset, length, allowEndOfInput)
                ?: super.peekFully(target, offset, length, allowEndOfInput)
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            val selected = sniff
            if (selected == null) super.peekFully(target, offset, length)
            else selected.peekFully(target, offset, length)
        }
        override fun advancePeekPosition(length: Int) {
            val selected = sniff
            if (selected == null) super.advancePeekPosition(length) else selected.advancePeekPosition(length)
        }
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean =
            sniff?.advancePeekPosition(length, allowEndOfInput) ?: super.advancePeekPosition(length, allowEndOfInput)
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
            reader().readFully(target, offset, length, allowEndOfInput)
        override fun readFully(target: ByteArray, offset: Int, length: Int) = reader().readFully(target, offset, length)
    }

    /** A separate observation view; do not use Media3's library-restricted StartOffsetExtractorInput. */
    private inner class Relative(input: ExtractorInput) : ForwardingExtractorInput(input) {
        override fun getPosition() = super.getPosition() - start
        override fun getPeekPosition() = super.getPeekPosition() - start
        override fun getLength() = super.getLength().let { if (it == C.LENGTH_UNSET.toLong()) it else it - start }
        override fun <E : Throwable> setRetryPosition(position: Long, e: E) = super.setRetryPosition(position + start, e)
    }

    /** Native JPEG sniffs twice (selection and first read); both must bound cumulative peeks. */
    private open inner class Preamble(input: ExtractorInput) : ForwardingExtractorInput(input) {
        private val initial = input.peekPosition
        private fun admit(length: Int) {
            // Buffered preambles can scan without another interruptible upstream read.
            if (Thread.interrupted()) throw InterruptedIOException("Embedded information read cancelled")
            val used = peekPosition - initial
            if (length < 0 || used < 0 || used > preambleBytes || length.toLong() > preambleBytes - used)
                throw StreamMetadataRefusal()
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            admit(length); super.peekFully(target, offset, length)
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            admit(length); return super.peekFully(target, offset, length, allowEndOfInput)
        }
        override fun advancePeekPosition(length: Int) { admit(length); super.advancePeekPosition(length) }
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            admit(length); return super.advancePeekPosition(length, allowEndOfInput)
        }
    }
}
