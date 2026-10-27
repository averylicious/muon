@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.SeekMap
import java.io.InterruptedIOException

/**
 * FLV startup and SCRIPT metadata admission (#736), engineering proposals rather than measured safe
 * memory. Lookahead: the FLV header's data offset, for sniff peeks and the read-path skip. Script
 * bytes: a buffered SCRIPT tag body. Slots: AMF collection capacities and entries one onMetaData tag
 * reserves. Audio/video tag payloads, their reused buffer, codecs and decoders are not bounded here.
 */
internal class FlvMetadataLimits(
    val lookaheadBytes: Long = 16L * 1024 * 1024,
    val scriptBytes: Int = 4 * 1024 * 1024,
    val slots: Int = 65_536,
    val depth: Int = 16,
) { init { require(lookaheadBytes > 0 && scriptBytes >= 0 && slots >= 0 && depth > 0) } }

// Pinned FlvExtractor: readFully(headerBuffer, 0, 9, true) at 0, readFully(tagHeaderBuffer, 0, 11, true)
// per tag, and prepareTagData's readFully(tagData, 0, size) into a possibly larger reused array.
private const val FLV_HEADER = 9
private const val TAG_HEADER = 11
private const val TAG_SCRIPT = 18
private const val PREVIOUS_TAG_SIZE = 4
private val ON_METADATA = "onMetaData".toByteArray(Charsets.US_ASCII)

/**
 * Follows pinned FlvExtractor's own reads. Its header data offset sets the first skip (a sniff-free
 * single-candidate read included). A SCRIPT tag is buffered only while no seek map has been output,
 * so its declared size is admitted in the tag header read, before prepareTagData can allocate, and
 * the delivered body is scanned before ScriptTagPayloadReader parses it. Every delivered body is
 * scanned, including an I/O retry; nothing is cached as safe. After a seek map, scripts are skipped
 * natively and are not inspected. Audio/video tags are left entirely to the native reader.
 */
internal class FlvMetadataAdmission(private val limits: FlvMetadataLimits = FlvMetadataLimits()) {
    // Native outputSeekMap persists across seek; mirror it from the actual forwarded call.
    private var seekMapOutput = false
    private var scriptAt = -1L
    private var scriptBytes = -1

    /** Native seek(0) rereads the FLV header; any other position reads a tag header there. */
    fun seek() { scriptAt = -1; scriptBytes = -1 }

    fun output(actual: ExtractorOutput): ExtractorOutput = object : ExtractorOutput by actual {
        override fun seekMap(seekMap: SeekMap) {
            actual.seekMap(seekMap)
            seekMapOutput = true
        }
    }

    fun input(actual: ExtractorInput): ExtractorInput = object : ForwardingExtractorInput(actual) {
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val at = position
            val kind = watched(at, target, offset, length, allowEndOfInput)
            if (!super.readFully(target, offset, length, allowEndOfInput)) return false
            observed(kind, at, target, length, this.length)
            return true
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            val at = position
            val kind = watched(at, target, offset, length, false)
            super.readFully(target, offset, length)
            observed(kind, at, target, length, this.length)
        }
    }

    private enum class Read { NONE, HEADER, TAG, SCRIPT }

    /** Cancel only before a watched read consumes input, so native state and position stay aligned. */
    private fun watched(at: Long, target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Read {
        val kind = when {
            offset != 0 -> Read.NONE
            allowEndOfInput && at == 0L && length == FLV_HEADER && target.size == FLV_HEADER -> Read.HEADER
            allowEndOfInput && length == TAG_HEADER && target.size == TAG_HEADER -> Read.TAG
            !allowEndOfInput && at == scriptAt && length == scriptBytes && target.size >= length -> Read.SCRIPT
            else -> Read.NONE
        }
        if (kind == Read.TAG && (at < 0 || at > Long.MAX_VALUE - TAG_HEADER))
            throw StreamMetadataRefusal(true)
        if (kind != Read.NONE && Thread.interrupted()) throw InterruptedIOException("Embedded information read cancelled")
        return kind
    }

    private fun observed(kind: Read, at: Long, data: ByteArray, length: Int, streamLength: Long) {
        when (kind) {
            Read.HEADER -> header(data, streamLength)
            Read.TAG -> tag(at, data, streamLength)
            // A reused array can be larger than this tag: scan only the delivered bytes.
            Read.SCRIPT -> Amf(data, length, limits).scan()
            Read.NONE -> Unit
        }
    }

    /** Native skips dataOffset - 9 + 4 in int arithmetic before its first tag header. */
    private fun header(data: ByteArray, streamLength: Long) {
        scriptAt = -1; scriptBytes = -1
        val dataOffset = s32(data, 5).toLong()
        if (dataOffset < FLV_HEADER) throw StreamMetadataRefusal(true, audioFormat = true)
        if (dataOffset > limits.lookaheadBytes) throw StreamMetadataRefusal(audioFormat = true)
        if (streamLength != C.LENGTH_UNSET.toLong() && dataOffset + PREVIOUS_TAG_SIZE > streamLength)
            throw StreamMetadataRefusal(true, audioFormat = true)
    }

    private fun tag(at: Long, data: ByteArray, streamLength: Long) {
        scriptAt = -1; scriptBytes = -1
        val type = data[0].toInt() and 255
        val size = ((data[1].toInt() and 255) shl 16) or ((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255)
        // Check wide positions even for skipped/media tags; this imposes no payload-size policy.
        if (at < 0 || at > Long.MAX_VALUE - TAG_HEADER - size) throw StreamMetadataRefusal(true)
        if (type != TAG_SCRIPT || seekMapOutput) return // Native skips it or hands it to a media reader.
        if (size > limits.scriptBytes) throw StreamMetadataRefusal()
        val bodyAt = at + TAG_HEADER
        if (streamLength != C.LENGTH_UNSET.toLong() && (streamLength < bodyAt || size.toLong() > streamLength - bodyAt))
            throw StreamMetadataRefusal(true)
        scriptAt = bodyAt; scriptBytes = size
    }

    private fun s32(data: ByteArray, at: Int): Int = ((data[at].toInt() and 255) shl 24) or ((data[at + 1].toInt() and 255) shl 16) or
        ((data[at + 2].toInt() and 255) shl 8) or (data[at + 3].toInt() and 255)

    /**
     * ScriptTagPayloadReader's onMetaData walk without its objects or strings. Native legacy reads
     * enforce the delivered limit in tests, but not by default in production. Here every read
     * must be backed before native parsing, regardless of the test-only enforcement.
     * Unsupported value types consume nothing more, exactly as native readAmfData returns null.
     */
    private class Amf(private val data: ByteArray, private val end: Int, private val limits: FlvMetadataLimits) {
        private var at = 0
        private var slots = 0L

        fun scan() {
            if (u8() != 2) return // Native ignores a non-string name.
            val nameLength = u16()
            need(nameLength.toLong())
            val name = at
            at += nameLength
            if (nameLength != ON_METADATA.size || (0 until nameLength).any { data[name + it] != ON_METADATA[it] }) return
            if (at == end) return // Native ignores onMetaData with no value.
            if (u8() != 8) return // Only an ECMA array is parsed.
            ecma(1)
        }

        private fun value(type: Int, depth: Int) {
            when (type) {
                0 -> skip(8)
                1 -> skip(1)
                2 -> string()
                3 -> objectEntries(depth + 1)
                8 -> ecma(depth + 1)
                10 -> strict(depth + 1)
                11 -> skip(10)
                else -> Unit
            }
        }

        // Native: HashMap(count) capacity (not necessarily an eager table), then up to count entries.
        private fun ecma(depth: Int) {
            enter(depth)
            val count = count()
            if (count * 3 > end - at) throw StreamMetadataRefusal(true) // A key length and type each.
            reserve(count)
            for (i in 0 until count) { string(); value(u8(), depth) }
        }

        // Native: ArrayList(count) allocates its element array before any element is read.
        private fun strict(depth: Int) {
            enter(depth)
            val count = count()
            if (count > end - at) throw StreamMetadataRefusal(true) // A type byte each.
            reserve(count)
            for (i in 0 until count) value(u8(), depth)
        }

        // Native: entries until a key followed by the end-marker type 9.
        private fun objectEntries(depth: Int) {
            enter(depth)
            while (true) {
                string()
                val type = u8()
                if (type == 9) return
                reserve(1)
                value(type, depth)
            }
        }

        private fun enter(depth: Int) { if (depth > limits.depth) throw StreamMetadataRefusal() }
        private fun reserve(count: Long) {
            if (count > limits.slots - slots) throw StreamMetadataRefusal()
            slots += count
        }
        /** readUnsignedIntToInt refuses a value with its top bit set. */
        private fun count(): Long {
            need(4)
            val value = ((data[at].toLong() and 255) shl 24) or ((data[at + 1].toLong() and 255) shl 16) or
                ((data[at + 2].toLong() and 255) shl 8) or (data[at + 3].toLong() and 255)
            at += 4
            if (value > Int.MAX_VALUE) throw StreamMetadataRefusal(true)
            return value
        }
        private fun string() { skip(u16().toLong()) }
        private fun u8(): Int { need(1); return data[at++].toInt() and 255 }
        private fun u16(): Int { need(2); return ((data[at++].toInt() and 255) shl 8) or (data[at++].toInt() and 255) }
        private fun skip(bytes: Long) { need(bytes); at += bytes.toInt() }
        private fun need(bytes: Long) { if (bytes < 0 || bytes > end - at) throw StreamMetadataRefusal(true) }
    }
}
