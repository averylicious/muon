@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.util.WavUtil
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorInput

/** Proposed resource policies, not whole-song limits or a global decoder/heap guarantee (#723). */
internal class WavLimits(val formatBytes: Long = 64 * 1024, val outputBytes: Long = 16L * 1024 * 1024) {
    init { require(formatBytes >= 16 && outputBytes > 0) }
}

/**
 * Pinned WavHeaderReader peeks a chunk header then16 fixed fmt bytes, allocates its extension,
 * peeks that body, and commits the format with skipFully. Admit before the extension array and
 * before WavExtractor constructs the writer's derived buffers. Never mark done before commit:
 * a failed extra-data peek reopens at the chunk header and may deliver different fields.
 */
internal class WavMetadataAdmission(private val limits: WavLimits = WavLimits()) {
    private data class Fmt(val type: Int, val channels: Int, val rate: Long, val block: Int, val bits: Int)
    private var at = -1L
    private var size = 0L
    private var fmt: Fmt? = null
    private var ready = false
    private var done = false

    fun seek(position: Long) {
        if (position != 0L) return
        at = -1; size = 0; fmt = null; ready = false; done = false
        // A native writer already initialized at dataStartPosition reuses that accepted format
        // on seek0; no new metadata arrays are built on its cached fast path.
    }
    fun input(actual: ExtractorInput): ExtractorInput = if (done) actual else object : ForwardingExtractorInput(actual) {
        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            val start = peekPosition
            super.peekFully(target, offset, length)
            inspect(target, offset, length, start, position, this.length)
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val start = peekPosition
            if (!super.peekFully(target, offset, length, allowEndOfInput)) return false
            inspect(target, offset, length, start, position, this.length)
            return true
        }
        override fun skipFully(length: Int) {
            super.skipFully(length)
            committed(position)
        }
        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            val skipped = super.skipFully(length, allowEndOfInput)
            if (skipped) committed(position)
            return skipped
        }
    }

    private fun inspect(data: ByteArray, offset: Int, length: Int, peek: Long, position: Long, total: Long) {
        if (done || offset != 0) return
        // RF64's sample-data-size body also peeks8bytes; it is NOT a chunk header at position.
        if (length == 8 && peek == position && data[0] == 'f'.code.toByte() && data[1] == 'm'.code.toByte() &&
            data[2] == 't'.code.toByte() && data[3] == ' '.code.toByte()) {
            val declared = data.u32(4)
            if (declared < 16 || peek > Long.MAX_VALUE - 8 - declared) refuse(true)
            if (declared > limits.formatBytes) refuse()
            if (total != C.LENGTH_UNSET.toLong() && peek + 8 + declared > total) refuse(true)
            at = peek; size = declared; fmt = null; ready = false
        } else if (at >= 0 && peek == at + 8 && length == 16) {
            val parsed = Fmt(data.u16(0), data.u16(2), data.u32(4), data.u16(12), data.u16(14))
            if (parsed.rate > Int.MAX_VALUE || data.u32(8) > Int.MAX_VALUE) refuse(true)
            fmt = parsed
            if (parsed.type == WavUtil.TYPE_IMA_ADPCM && size < 20) refuse(true)
            if (parsed.type != WavUtil.TYPE_IMA_ADPCM && !(parsed.type == WavUtil.TYPE_WAVE_FORMAT_EXTENSIBLE && size == 40L)) {
                admitWriter(parsed, null)
                ready = true
            }
        } else if (at >= 0 && peek == at + 24 && length.toLong() == size - 16) {
            val fixed = fmt ?: refuse(true)
            val effective = if (fixed.type == WavUtil.TYPE_WAVE_FORMAT_EXTENSIBLE && length == 24)
                fixed.copy(type = data.u16(8)) else fixed
            admitWriter(effective, data)
            ready = true
        }
    }
    private fun committed(position: Long) {
        if (ready && at >= 0 && position == at + 8 + size) {
            done = true; fmt = null
        }
    }
    private fun admitWriter(fmt: Fmt, extra: ByteArray?) {
        if (fmt.type == WavUtil.TYPE_IMA_ADPCM) {
            if (extra == null || extra.size < 4 || fmt.channels == 0 || fmt.rate == 0L || fmt.bits != 4 || fmt.block < fmt.channels * 4) refuse(true)
            val frames = extra.u16(2).toLong()
            val expected = ((fmt.block - fmt.channels * 4L) * 8) / (fmt.bits * fmt.channels) + 1
            if (frames == 0L || frames != expected) refuse(true)
            val target = maxOf(1L, fmt.rate / 10)
            val blocks = (target + frames - 1) / frames
            val input = blocks * fmt.block
            val decoded = blocks * frames * fmt.channels * 2
            val maximum = target * fmt.channels * 2
            if (input + decoded > limits.outputBytes || maximum > limits.outputBytes ||
                input > Int.MAX_VALUE || decoded > Int.MAX_VALUE || maximum > Int.MAX_VALUE) refuse()
        } else if (fmt.type == WavUtil.TYPE_ALAW || fmt.type == WavUtil.TYPE_MLAW ||
            WavUtil.getPcmEncodingForType(fmt.type, fmt.bits) != C.ENCODING_INVALID) {
            val frame = fmt.channels * fmt.bits.toLong() / 8
            if (fmt.channels == 0 || fmt.rate == 0L || frame == 0L || frame != fmt.block.toLong()) refuse(true)
            val maximum = maxOf(frame, fmt.rate * frame / 10)
            if (maximum > limits.outputBytes || fmt.rate * frame > Int.MAX_VALUE) refuse()
        } // Unsupported formats retain native validation; they never reach a writer allocation.
    }
    private fun refuse(malformed: Boolean = false): Nothing = throw StreamMetadataRefusal(malformed, audioFormat = true)
    private fun ByteArray.u16(at: Int) = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
    private fun ByteArray.u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
}
