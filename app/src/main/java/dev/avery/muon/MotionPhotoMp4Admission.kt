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

/** The pinned JPEG and HEIC motion-photo readers create internal MP4 readers outside the app factory.
 * Observe their actual MP4 sniff/header calls through public input/output forwarders. The first
 * 8-byte allow-EOF peek is the inner Sniffer's first atom; outer JPEG/HEIC peeks use other shapes.
 * Verify that offset against the native image track's public MotionPhotoMetadata. JPEG emits it
 * after inner sniffing; HEIC emits it before. For HEIC, enable read admission only when the first
 * actual inner read follows its sniff, so an interrupted sniff remains guarded on reopening.
 * Relative positions are private to the admission view; native bytes/positions/seek maps are intact.
 * This does not bound standalone image samples/decoding or every codec/whole-process allocation.
 */
internal class MotionPhotoMp4Admission(private val limits: Mp4Limits,
    private val preambleBytes: Long = MP4_RETAINED_BYTES,
    private val metadataBeforeSniff: Boolean = false) {
    init { require(preambleBytes > 0) }
    private var start = -1L
    private var reading = false
    private var announcedStart = -1L
    private val mp4 = Mp4MetadataAdmission(limits)

    fun seek(position: Long) {
        if (position == 0L) { start = -1; announcedStart = -1; reading = false; mp4.seek(0) }
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
                            if (metadataBeforeSniff) {
                                val offset = motion.videoStartPosition
                                if (offset <= 0 || (start >= 0 && offset != start) ||
                                    (announcedStart >= 0 && offset != announcedStart)) throw StreamMetadataRefusal(true)
                                announcedStart = offset
                            } else {
                                if (start < 0 || motion.videoStartPosition != start) throw StreamMetadataRefusal(true)
                                reading = true
                            }
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
        private fun reader(): ExtractorInput {
            // Native Sniffer only peeks. The first following read means HEIC's inner sniff succeeded;
            // do not arm early, or a failed sniff's fresh retry view could lose its sniff admission.
            if (!reading && metadataBeforeSniff && sniff != null) reading = true
            if (reading) sniff = null // The sniff/preamble budgets end when MP4 reading starts.
            return if (reading) mp4.input(Relative(actual)) else actual
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (reading) return actual.peekFully(target, offset, length, allowEndOfInput)
            if (!reading && sniff == null && offset == 0 && length == 8 && allowEndOfInput && target.size == 64) {
                if (actual.peekPosition != actual.position) throw StreamMetadataRefusal(true)
                if (metadataBeforeSniff && announcedStart != actual.position) throw StreamMetadataRefusal(true)
                if (start >= 0 && start != actual.position) throw StreamMetadataRefusal(true)
                start = actual.position
                sniff = Mp4SniffInput(Relative(actual), limits.retainedBytes)
            }
            return sniff?.peekFully(target, offset, length, allowEndOfInput)
                ?: super.peekFully(target, offset, length, allowEndOfInput)
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            if (reading) { actual.peekFully(target, offset, length); return }
            val selected = sniff
            if (selected == null) super.peekFully(target, offset, length)
            else selected.peekFully(target, offset, length)
        }
        override fun advancePeekPosition(length: Int) {
            if (reading) { actual.advancePeekPosition(length); return }
            val selected = sniff
            if (selected == null) super.advancePeekPosition(length) else selected.advancePeekPosition(length)
        }
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean =
            if (reading) actual.advancePeekPosition(length, allowEndOfInput)
            else sniff?.advancePeekPosition(length, allowEndOfInput) ?: super.advancePeekPosition(length, allowEndOfInput)
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

    /** Native motion-photo selection and first-read sniffs must both bound cumulative peeks. */
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
