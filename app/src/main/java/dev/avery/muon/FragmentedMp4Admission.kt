@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorInput

/**
 * Default-factory fragmented MP4's MFRA tail index only (#720). Its readMfra peeks an 8-byte mfra
 * header, then allocates input.length-position bytes, not merely the size in that header. Refuse
 * before that array and check actual delivered tfra declarations before their two long arrays.
 * Ordinary atom/trun/DRM/sidx admission is separate work; this is not whole-fragmented-MP4 clearance.
 */
internal class FragmentedMp4Admission(private val limits: Mp4Limits = Mp4Limits(),
    private val admitRows: (Long) -> Unit = {}) {
    private var mfraAt = -1L
    private var mfraBytes = 0

    fun seek() { mfraAt = -1; mfraBytes = 0 }
    fun input(actual: ExtractorInput): ExtractorInput = object : ForwardingExtractorInput(actual) {
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (!super.peekFully(target, offset, length, allowEndOfInput)) return false
            // Pinned readMfra is the fragmented reader's only allow-end 8-byte header peek. The
            // meta helper's peek is the other overload and must not be mistaken for this state.
            if (allowEndOfInput && offset == 0 && length == 8 && target.u32(4) == MFRA) {
                val bytes = this.length - position
                if (bytes < 8 || bytes > Int.MAX_VALUE) throw StreamMetadataRefusal(true)
                if (bytes > limits.retainedBytes) throw StreamMetadataRefusal()
                mfraAt = position; mfraBytes = bytes.toInt()
            }
            return true
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            val at = position
            super.readFully(target, offset, length)
            if (at == mfraAt && offset == 0 && length == mfraBytes) {
                admitMfra(target, length)
                mfraAt = -1
            }
        }
    }

    private fun admitMfra(data: ByteArray, size: Int) {
        var at = if (data.u32(0) == 1L) 16 else 8
        var boxes = 0
        var rows = 0L
        while (size - at >= 8) {
            val declared = data.u32(at)
            val header = if (declared == 1L) 16 else 8
            if (size - at < header) { admitRows(rows); return } // Native parser stops on a truncated child header.
            val bytes = when (declared) {
                0L -> (size - at).toLong()
                1L -> data.s64(at + 8)
                else -> declared
            }
            if (bytes < header || bytes > size - at) { admitRows(rows); return } // Native parser breaks before allocation.
            if (++boxes > limits.boxes) throw StreamMetadataRefusal()
            if (data.u32(at + 4) == TFRA && bytes >= header + 16) {
                val fields = at + header
                val wide = data.u32(fields) ushr 24 == 1L
                val lengths = data.u32(fields + 8)
                val count = data.u32(fields + 12)
                val entryBytes = (if (wide) 16L else 8L) +
                    ((lengths ushr 4) and 3) + ((lengths ushr 2) and 3) + (lengths and 3) + 3
                // Pinned readMfra checks remaining WHOLE suffix, not just the current child's size.
                // Charge every potentially allocating table, including unknown track IDs (which the
                // parser can skip), so no track-lookup assumption can understate the admission.
                if (count * entryBytes <= size.toLong() - fields - 16) {
                    rows += count * 2 // Native time and offset long arrays.
                    if (rows > limits.tableRows) throw StreamMetadataRefusal()
                }
            }
            at += bytes.toInt()
        }
        admitRows(rows)
    }

    private companion object {
        const val MFRA = 0x6d667261L
        const val TFRA = 0x74667261L
        fun ByteArray.u32(at: Int): Long = ((this[at].toLong() and 255) shl 24) or
            ((this[at + 1].toLong() and 255) shl 16) or ((this[at + 2].toLong() and 255) shl 8) or
            (this[at + 3].toLong() and 255)
        fun ByteArray.s64(at: Int): Long = (u32(at) shl 32) or u32(at + 4)
    }
}
