@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorInput
import java.io.InterruptedIOException

/**
 * AVI hdrl admission (#734), engineering proposals rather than measured safe memory. Header bytes:
 * the body pinned AviExtractor allocates from its hdrl LIST size. Chunks/depth/streams: what its
 * recursive ListChunk parse and processStreamList build from that body. Index bytes/rows are admitted separately before native allocation; samples/codecs remain outside.
 */
internal class AviHeaderLimits(
    val hdrlBytes: Int = 16 * 1024 * 1024,
    val chunks: Int = 4096,
    val depth: Int = 16,
    val streams: Int = 128,
    val indexBytes: Int = 16 * 1024 * 1024,
    val indexRows: Int = 65_536,
) { init { require(hdrlBytes >= 0 && chunks > 0 && depth >= 0 && streams >= 0 && indexBytes >= 0 && indexRows >= 0) } }

private const val LIST = 0x5453494c // Little-endian fourccs, as pinned AviExtractor reads them.
private const val HDRL = 0x6c726468
private const val IDX1 = 0x31786469
private const val STRL = 0x6c727473
/** Pinned STATE_READING_HDRL_HEADER: readFully(scratch, 0, 12) after skipping the 12-byte RIFF header. */
private const val HEADER_AT = 12L
private const val HEADER_BYTES = 12
private const val BODY_AT = HEADER_AT + HEADER_BYTES

/**
 * Follows pinned AviExtractor's two startup reads. Its hdrl header is read at position 12, then the
 * next read allocates `size - 4` bytes before reading the body: refuse that declaration in the header
 * read itself. When that exact body has been delivered, check its LIST structure before ListChunk
 * parses it, mirroring ListChunk's `bytesLeft() > 8` loop (no RIFF pad bytes; a short tail is ignored).
 * A failed body read keeps the admitted size for the native retry, and every completed body read is
 * checked again; nothing is cached as already safe. Only ever reads what the extractor reads.
 */
internal class AviHeaderAdmission(private val limits: AviHeaderLimits = AviHeaderLimits()) {
    private var bodyBytes = -1

    /** Native seek either rereads hdrl from its header (unprepared seek to 0) or skips it entirely. */
    fun seek() { bodyBytes = -1 }

    fun input(actual: ExtractorInput): ExtractorInput = object : ForwardingExtractorInput(actual) {
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val at = before(offset, length)
            if (!super.readFully(target, offset, length, allowEndOfInput)) return false
            observed(at, target, offset, length, this.length)
            return true
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            val at = before(offset, length)
            super.readFully(target, offset, length)
            observed(at, target, offset, length, this.length)
        }
        // Cancel only before a watched read consumes input, so native state and position stay aligned.
        private fun before(offset: Int, length: Int): Long {
            val at = position
            val watched = offset == 0 && ((at == HEADER_AT && length == HEADER_BYTES) || (at == BODY_AT && length == bodyBytes))
            if (watched && Thread.interrupted()) throw InterruptedIOException("Embedded information read cancelled")
            return at
        }
    }

    private fun observed(at: Long, data: ByteArray, offset: Int, length: Int, streamLength: Long) {
        if (offset != 0) return
        // Pinned AVI uses its unchanged12-byte scratch for the8-byte idx1-search header.
        // hdrl/idx1 payload arrays have their exact length; SampleDataQueue uses read(), not this call.
        if (length == 8 && data.size == HEADER_BYTES) indexHeader(at, data, streamLength)
        if (at == HEADER_AT && length == HEADER_BYTES && data.size >= HEADER_BYTES) header(data, streamLength)
        else if (at == BODY_AT && bodyBytes >= 0 && length == bodyBytes && data.size == length) {
            scan(data, length)
        }
    }

    private fun header(data: ByteArray, streamLength: Long) {
        bodyBytes = -1
        // The nested ChunkHeaderHolder validates LIST; the outer state validates hdrl.
        if (le(data, 0) != LIST || le(data, 8) != HDRL) return
        // Native computes size - 4 in int arithmetic: negative, or overflowing to a huge array.
        val size = le(data, 4).toLong()
        if (size < 4) throw StreamMetadataRefusal(true, audioFormat = true)
        val body = size - 4
        if (body > limits.hdrlBytes) throw StreamMetadataRefusal(audioFormat = true)
        if (streamLength != C.LENGTH_UNSET.toLong() && body > streamLength - BODY_AT) throw StreamMetadataRefusal(true, audioFormat = true)
        bodyBytes = body.toInt()
    }

    private fun indexHeader(at: Long, data: ByteArray, streamLength: Long) {
        val bytes = le(data, 4)
        // A negative unknown-chunk size can otherwise make index search seek to itself/backwards.
        if (bytes < 0 || at < 0 || at > Long.MAX_VALUE - 8 - bytes) throw StreamMetadataRefusal(true, audioFormat = true)
        if (le(data, 0) != IDX1) return
        // Native walks complete16-byte records and appends at most one index row per record.
        // Include unknown/non-key records conservatively; don't trust claimed sample/frame counts.
        if (bytes > limits.indexBytes || bytes / 16 > limits.indexRows) throw StreamMetadataRefusal(audioFormat = true)
        if (streamLength != C.LENGTH_UNSET.toLong() && at + 8 + bytes > streamLength)
            throw StreamMetadataRefusal(true, audioFormat = true)
    }

    /** ListChunk.parseFrom's walk without its objects; stricter than native about crossing a parent. */
    private fun scan(body: ByteArray, size: Int) {
        var chunks = 0
        var streams = 0
        fun list(start: Int, end: Int, depth: Int) {
            var at = start
            while (end - at > 8) {
                val type = le(body, at)
                val declared = le(body, at + 4)
                // Native only checks the whole array, so a child can read across its parent or a
                // negative size can step backwards. Refuse both as malformed.
                if (declared < 0 || declared > end - at - 8) throw StreamMetadataRefusal(true, audioFormat = true)
                if (++chunks > limits.chunks) throw StreamMetadataRefusal(audioFormat = true)
                val inner = at + 8 + declared
                if (type == LIST) {
                    if (declared < 4) throw StreamMetadataRefusal(true, audioFormat = true) // Native reads its list type anyway.
                    if (depth + 1 > limits.depth) throw StreamMetadataRefusal(audioFormat = true)
                    // parseHdrlBody builds a stream (and possibly a ChunkReader) per top-level strl.
                    if (depth == 0 && le(body, at + 8) == STRL && ++streams > limits.streams) throw StreamMetadataRefusal(audioFormat = true)
                    list(at + 12, inner, depth + 1)
                }
                at = inner
            }
        }
        list(0, size, 0)
    }

    private fun le(data: ByteArray, at: Int): Int = (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8) or
        ((data[at + 2].toInt() and 255) shl 16) or ((data[at + 3].toInt() and 255) shl 24)
}
