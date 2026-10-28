@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorInput
import java.io.InterruptedIOException

/** ID3 decoded-cost policies (#704), not prior sample-buffer or whole-heap limits. */
internal class Id3FrameLimits(
    val tags: Int = 4096, val frames: Int = 4096, val depth: Int = 16,
    val values: Int = 4096, val references: Long = 1_048_576, val unsyncWork: Long = 4L * 1024 * 1024 * 1024,
) { init { require(tags > 0 && frames > 0 && depth >= 0 && values > 0 && references > 0 && unsyncWork >= 0) } }

/** Inspect delivered ID3 before native decode; the peeker path also needs prefix byte admission. */
internal class Id3FrameAdmission(private val limits: Id3FrameLimits = Id3FrameLimits()) {
    fun input(actual: ExtractorInput): ExtractorInput = object : ForwardingExtractorInput(actual) {
        private fun delivered(data: ByteArray, offset: Int, length: Int) {
            if (offset != 10 || data.size < 10 || data.size - 10 != length ||
                data[0] != 'I'.code.toByte() || data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()) return
            admit(data, data.size)
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            super.peekFully(target, offset, length); delivered(target, offset, length)
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (!super.peekFully(target, offset, length, allowEndOfInput)) return false
            delivered(target, offset, length); return true
        }
    }

    /** Also used before ID3 metadata-renderer decode. Respect the delivered limit, not spare
     * ByteBuffer capacity: native setLimit must not expose an undelivered previous sample tail. */
    fun admit(data: ByteArray, size: Int) {
        if (size < 0 || size > data.size) throw StreamMetadataRefusal(true)
        if (size > STREAM_ID3_BYTES) throw StreamMetadataRefusal()
        if (size < 10 || data[0] != 'I'.code.toByte() || data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()) return
        val version = data[3].toInt() and 255
        if (version !in 2..4 || (version == 2 && data[5].toInt() and 0x40 != 0)) return // Native ignores it.
        var declared = 0
        for (i in 6..9) {
            val value = data[i].toInt() and 255
            if (value and 128 != 0) throw StreamMetadataRefusal(true)
            declared = (declared shl 7) or value
        }
        if (declared > size - 10) throw StreamMetadataRefusal(true)
        // Native ignores bytes after the declared tag; preserve that rather than counting a
        // reused buffer's capacity or PES/sample padding as another frame.
        Scan(data.copyOf(10 + declared), limits, version).admit()
    }

    /** A bounded private working copy preserves the original bytes for native unsynchronization. */
    private class Scan(private val data: ByteArray, private val limits: Id3FrameLimits, private val version: Int) {
        private val header = if (version == 2) 6 else 10
        private var frameCount = 0
        private var valueCount = 0
        private var referenceCount = 0L
        private var shiftedBytes = 0L
        private data class Frame(val id: Int, val flags: Int, val start: Int, val end: Int) {
            val size get() = end - start
        }
        fun admit() {
            var start = 10
            var bytes = sync(6)
            if (bytes != data.size - 10) malformed()
            val flags = u(5)
            if (version >= 3 && flags and 0x40 != 0) {
                need(start, 4, data.size)
                val extension = if (version == 4) sync(start).toLong() else uint(start, 4)
                val occupied = extension + if (version == 3) 4 else 0
                if (occupied < 4 || occupied > bytes) malformed()
                start += occupied.toInt(); bytes -= occupied.toInt()
            }
            if (version == 4 && flags and 0x10 != 0) bytes -= 10
            if (bytes < 0) malformed()
            if (version < 4 && flags and 0x80 != 0) bytes = unsync(start, bytes)
            val end = start + bytes
            val hack = if (valid(start, end, false)) false else if (version == 4 && valid(start, end, true)) true else return
            frames(start, end, 0, hack)
        }
        private fun valid(start: Int, end: Int, hack: Boolean): Boolean {
            var at = start
            var count = 0
            while (end - at >= header) {
                cancelled()
                val f = frame(at, end, hack) ?: return false
                if (f.id == 0 && f.size == 0 && f.flags == 0) return true
                if (++count > limits.frames) refuse()
                at = f.end
            }
            return true // Native ignores a short top-level tail.
        }
        private fun frame(at: Int, end: Int, hack: Boolean): Frame? {
            if (at < 0 || end - at < header) return null
            val width = if (version == 2) 3 else 4
            val id = uint(at, width).toInt()
            val flags = if (version == 2) 0 else (u(at + 8) shl 8) or u(at + 9)
            var bytes = uint(at + width, width)
            if (version == 4 && !hack) {
                if (bytes and 0x808080L != 0L) return null // Pinned v4 raw-size fallback.
                bytes = (bytes and 255) or (((bytes ushr 8) and 255) shl 7) or
                    (((bytes ushr 16) and 255) shl 14) or (((bytes ushr 24) and 255) shl 21)
            }
            val minimum = (if (group(flags)) 1 else 0) + (if (lengthFlag(flags)) 4 else 0)
            if (bytes < minimum || bytes > end - at - header) return null
            return Frame(id, flags, at + header, at + header + bytes.toInt())
        }
        private fun frames(start: Int, end: Int, depth: Int, hack: Boolean) {
            if (depth > limits.depth) refuse()
            var at = start
            while (at < end) {
                cancelled()
                if (end - at < header) {
                    // Native nested decoding can read across its parent. Do not silently skip
                    // a partial child header that could hide a later allocation outside it.
                    if (depth > 0) malformed() else return
                }
                val f = frame(at, end, hack) ?: malformed()
                if (f.id == 0 && f.size == 0 && f.flags == 0) return
                if (frameCount >= limits.frames) refuse()
                frameCount++
                at = f.end
                val unsupported = if (version == 3) f.flags and 0xc0 != 0 else version == 4 && f.flags and 0x0c != 0
                if (unsupported) continue // Native compressed/encrypted frames allocate no decoded payload.
                val body = f.start + (if (group(f.flags)) 1 else 0) + (if (lengthFlag(f.flags)) 4 else 0)
                val size = if (version == 4 && f.flags and 2 != 0) unsync(body, f.end - body) else f.end - body
                val limit = body + size
                when {
                    f.id == 0x4d4c4c54 -> mllt(body, limit)
                    f.id == 0x43484150 -> chapter(body, limit, depth, hack)
                    f.id == 0x43544f43 -> toc(body, limit, depth, hack)
                    (f.id ushr if (version == 2) 16 else 24) == 'T'.code -> text(f.id, body, limit)
                }
            }
        }
        private fun mllt(at: Int, end: Int) {
            if (end - at < 10) return // Native catches malformed short frames.
            val bits = u(at + 8) + u(at + 9)
            if (bits == 0) return // Native catches division-by-zero and drops this frame.
            val rows = 8L * (end - at - 10) / bits
            if (rows > limits.references - referenceCount) refuse()
            referenceCount += rows
        }
        private fun chapter(at: Int, end: Int, depth: Int, hack: Boolean) {
            val afterId = zero(at, end) + 1
            need(afterId, 16, end)
            if (uint(afterId, 4).toInt() > uint(afterId + 4, 4).toInt()) return // Native ignores it.
            frames(afterId + 16, end, depth + 1, hack)
        }
        private fun toc(at: Int, end: Int, depth: Int, hack: Boolean) {
            var child = zero(at, end) + 1
            need(child, 2, end)
            val count = u(child + 1); child += 2
            repeat(count) { countValue(); child = zero(child, end) + 1 }
            frames(child, end, depth + 1, hack)
        }
        private fun text(id: Int, at: Int, end: Int) {
            if (at >= end) return
            val encoding = u(at)
            // Native uses two-byte delimiters for ALL values except0/3, even invalid encodings.
            val width = if (encoding == 0 || encoding == 3) 1 else 2
            var start = at + 1
            if (id == 0x54585858 || (version == 2 && id == 0x545858)) start = terminator(start, end, width) + width
            if (start >= end) { countValue(); return }
            var stop = terminator(start, end, width)
            var values = 0
            while (start < stop) {
                countValue(); values++
                start = stop + width; stop = terminator(start, end, width)
            }
            if (values == 0) countValue() // Native retains an empty string when no nonempty values exist.
        }
        private fun countValue() { if (valueCount >= limits.values) refuse(); valueCount++ }
        private fun terminator(at: Int, end: Int, width: Int): Int {
            var i = at
            while (i < end) {
                if (u(i) == 0 && (width == 1 || (i + 1 < end && u(i + 1) == 0))) return i
                i += width
            }
            return end
        }
        private fun zero(at: Int, end: Int): Int {
            var i = at
            while (i < end) { if (u(i) == 0) return i; i++ }
            malformed() // Native might scan beyond the parent; admission is deliberately stricter.
        }
        private fun unsync(at: Int, size: Int): Int {
            need(at, size, data.size)
            var read = at
            var write = at
            val end = at + size
            while (read < end) {
                cancelled()
                val value = data[read++]
                data[write++] = value
                if (value == 0xff.toByte() && read < end && data[read] == 0.toByte()) {
                    read++
                    // Pinned native removal arraycopies the remaining region for EACH match.
                    // Linear normalization here also accounts for that later native copy work.
                    val shifted = (end - read).toLong()
                    if (shifted > limits.unsyncWork - shiftedBytes) refuse()
                    shiftedBytes += shifted
                }
            }
            return write - at
        }
        private fun group(flags: Int) = if (version == 3) flags and 0x20 != 0 else version == 4 && flags and 0x40 != 0
        private fun lengthFlag(flags: Int) = if (version == 3) flags and 0x80 != 0 else version == 4 && flags and 1 != 0
        private fun u(at: Int) = data[at].toInt() and 255
        private fun uint(at: Int, bytes: Int): Long {
            need(at, bytes, data.size)
            var value = 0L
            repeat(bytes) { value = (value shl 8) or u(at + it).toLong() }
            return value
        }
        private fun sync(at: Int): Int {
            need(at, 4, data.size)
            var value = 0
            repeat(4) { if (u(at + it) and 128 != 0) malformed(); value = (value shl 7) or u(at + it) }
            return value
        }
        private fun need(at: Int, bytes: Int, end: Int) { if (at < 0 || bytes < 0 || at > end - bytes) malformed() }
        private fun cancelled() { if (Thread.interrupted()) throw InterruptedIOException("Embedded information read cancelled") }
        private fun refuse(): Nothing = throw StreamMetadataRefusal()
        private fun malformed(): Nothing = throw StreamMetadataRefusal(true)
    }
}
