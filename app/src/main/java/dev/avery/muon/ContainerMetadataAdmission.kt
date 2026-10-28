@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.util.Base64
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.SeekMap

/**
 * Native FLAC allocating metadata payload, and Ogg bytes delivered by one extractor read (#714).
 * Engineering proposals, not accepted release policy: FLAC's own largest single block (24-bit) fits.
 */
internal const val CONTAINER_METADATA_BYTES = 16L * 1024 * 1024
/** Comments plus pictures one song's container metadata may create (#714). */
internal const val CONTAINER_METADATA_ENTRIES = 4096

private const val PICTURE_KEY = "METADATA_BLOCK_PICTURE="
private const val FLAC_SEEK_TABLE = 3
private const val FLAC_COMMENT = 4
private const val FLAC_PICTURE = 6
private val FLAC_MARKER = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
private val OPUS_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)
private val VORBIS_COMMENT = byteArrayOf(3) + "vorbis".toByteArray(Charsets.US_ASCII)

/**
 * Checks an actual Vorbis comment payload, exactly the range Media3's VorbisUtil will parse, before it
 * allocates its comment array or any nested picture. Returns how many comments it holds.
 */
internal fun admitVorbisComments(data: ByteArray, start: Int, end: Int, entriesLeft: Int): Int {
    var at = start
    fun unsigned(): Long {
        if (end - at < 4) throw StreamMetadataRefusal(true)
        val value = (data[at].toLong() and 255) or ((data[at + 1].toLong() and 255) shl 8) or
            ((data[at + 2].toLong() and 255) shl 16) or ((data[at + 3].toLong() and 255) shl 24)
        at += 4
        return value
    }
    val vendor = unsigned()
    if (vendor > end - at) throw StreamMetadataRefusal(true)
    at += vendor.toInt()
    val count = unsigned()
    // Each comment needs at least its length field; empty strings still count against entries.
    if (count > (end - at) / 4) throw StreamMetadataRefusal(true)
    if (count > entriesLeft) throw StreamMetadataRefusal()
    repeat(count.toInt()) {
        val length = unsigned()
        if (length > end - at) throw StreamMetadataRefusal(true)
        admitNestedPicture(data, at, length.toInt())
        at += length.toInt()
    }
    return count.toInt()
}

/** Media3's parseVorbisComments decodes this key's Base64 value into a FLAC picture block. */
private fun admitNestedPicture(data: ByteArray, start: Int, length: Int) {
    if (length < PICTURE_KEY.length) return
    for (i in PICTURE_KEY.indices) if (data[start + i] != PICTURE_KEY[i].code.toByte()) return
    val value = String(data, start + PICTURE_KEY.length, length - PICTURE_KEY.length, Charsets.UTF_8)
    // Undecodable values are logged and dropped by Media3 without a picture allocation.
    val decoded = try { Base64.decode(value, Base64.DEFAULT) } catch (_: IllegalArgumentException) { return }
    admitPicture(decoded, 0, decoded.size)
}

/**
 * Replays PictureFrame.fromPictureBlock's reads over the actual block. Only the declared data length
 * is allocated before its bounds are known; other malformed fields fail in Media3 without that array.
 */
internal fun admitPicture(data: ByteArray, start: Int, end: Int) {
    var at = start
    fun int(): Int? {
        if (end - at < 4) return null
        val value = ((data[at].toInt() and 255) shl 24) or ((data[at + 1].toInt() and 255) shl 16) or
            ((data[at + 2].toInt() and 255) shl 8) or (data[at + 3].toInt() and 255)
        at += 4
        return value
    }
    int() ?: return // Picture type.
    val mime = int() ?: return
    if (mime < 0 || mime > end - at) return
    at += mime
    val description = int() ?: return
    if (description < 0 || description > end - at) return
    at += description
    if (end - at < 16) return // Width, height, depth and colours.
    at += 16
    val bytes = int() ?: return
    if (bytes > end - at) throw StreamMetadataRefusal(true)
}

/**
 * Native FLAC metadata: FlacMetadataReader peeks each 4-byte block header, then allocates the whole
 * block and parses it inside one FlacExtractor.read. Observe the actual marker read and every header
 * peek at the next expected offset, refusing an allocating declaration before its array exists. A
 * completed comment/picture read is checked in the delegate's own array before the parser sees it.
 * Skipped blocks (padding, application, cue sheet) are followed by consumption, never pre-peeked.
 */
internal class FlacMetadataAdmission(private val payloadBudget: Long = CONTAINER_METADATA_BYTES,
    private val entryBudget: Int = CONTAINER_METADATA_ENTRIES) {
    private var phase = MARKER
    private var expected = -1L
    private var pending = -1L
    private var pendingType = -1
    private var pendingPayload = 0
    private var pendingLast = false
    private var payload = 0L
    private var entries = 0

    /** FlacExtractor rereads its metadata only after a seek to the start. */
    fun seek(position: Long) {
        if (position != 0L) return
        phase = MARKER; expected = -1; pending = -1; payload = 0; entries = 0
    }

    fun input(actual: ExtractorInput): ExtractorInput = if (phase == DONE) actual else Observed(actual)

    private inner class Observed(input: ExtractorInput) : ForwardingExtractorInput(input) {
        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            val at = peekPosition; val from = position
            super.peekFully(target, offset, length)
            header(at, from, target, offset, length)
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val at = peekPosition; val from = position
            if (!super.peekFully(target, offset, length, allowEndOfInput)) return false
            header(at, from, target, offset, length)
            return true
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            val from = position
            super.readFully(target, offset, length)
            consumed(from, target, offset, length)
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val from = position
            if (!super.readFully(target, offset, length, allowEndOfInput)) return false
            consumed(from, target, offset, length)
            return true
        }
        override fun skipFully(length: Int) {
            val from = position
            super.skipFully(length)
            consumed(from, null, 0, length)
        }
        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            val from = position
            if (!super.skipFully(length, allowEndOfInput)) return false
            consumed(from, null, 0, length)
            return true
        }

        /** The reader resets its peek position before each block header, so header peeks start at the read position. */
        private fun header(at: Long, from: Long, target: ByteArray, offset: Int, length: Int) {
            if (phase != BLOCKS || length != 4 || at != from) return
            if (from != expected) throw StreamMetadataRefusal(true) // Not where this stream's next block starts.
            val type = target[offset].toInt() and 0x7f
            val declared = ((target[offset + 1].toInt() and 255) shl 16) or
                ((target[offset + 2].toInt() and 255) shl 8) or (target[offset + 3].toInt() and 255)
            if (type == FLAC_SEEK_TABLE || type == FLAC_COMMENT || type == FLAC_PICTURE) {
                // The reader allocates header plus payload; the budget counts declared payload.
                if (payload + declared > payloadBudget) throw StreamMetadataRefusal()
                if (type == FLAC_PICTURE && entries + 1 > entryBudget) throw StreamMetadataRefusal()
            }
            // A retry peeks the same header again; nothing is committed until the block is consumed.
            pending = from; pendingType = type; pendingPayload = declared
            pendingLast = target[offset].toInt() and 0x80 != 0
        }

        private fun consumed(from: Long, target: ByteArray?, offset: Int, length: Int) {
            if (phase == MARKER) {
                if (target != null && length == 4 && (0 until 4).all { target[offset + it] == FLAC_MARKER[it] }) {
                    phase = BLOCKS; expected = position
                }
                return
            }
            if (phase != BLOCKS || from != pending) return
            if (target != null && length == 4 + pendingPayload) {
                // Exactly the bytes the parser will read: the block body, never spare capacity.
                when (pendingType) {
                    FLAC_COMMENT -> entries += admitVorbisComments(target, offset + 4, offset + length, entryBudget - entries)
                    FLAC_PICTURE -> { admitPicture(target, offset + 4, offset + length); entries++ }
                }
                if (pendingType == FLAC_SEEK_TABLE || pendingType == FLAC_COMMENT || pendingType == FLAC_PICTURE)
                    payload += pendingPayload
            }
            // Follow actual consumption: STREAMINFO is read at its fixed size whatever it declares.
            expected = position; pending = -1
            if (pendingLast) phase = DONE
        }
    }

    private companion object { const val MARKER = 0; const val BLOCKS = 1; const val DONE = 2 }
}

/**
 * Ogg: OggPacket grows its packet array page-run by page-run (ensureCapacity, then readFully) until a
 * lace below 255 ends the packet, and StreamReader parses every header packet inside the same read.
 * Every delegate read may deliver at most [packetBudget] bytes; the count survives an interrupted
 * read and resets only on a normal return or seek. The refusal comes after ensureCapacity for the
 * offending run, so one packet array can reach the budget plus one page-run (65,025 bytes).
 *
 * While headers are read, follow OggPageHeader.populate's actual peeks (27-byte header, then its
 * laces) and OggPacket's runs. When a run ends a packet, the delegate's array from 0 to the end of
 * that run is the assembled packet: an Opus or Vorbis comment header is checked there before
 * OpusReader/VorbisReader parse it, including after a reopen at a nonzero offset.
 */
internal class OggPacketAdmission(private val packetBudget: Long = CONTAINER_METADATA_BYTES,
    private val entryBudget: Int = CONTAINER_METADATA_ENTRIES) {
    private var headers = true
    private var seekMapPublished = false
    private var delivered = 0L
    private var pageAt = -1L
    private var pageSegments = -1
    private var bodyStart = -1L
    private var laceCount = -1
    private val laces = IntArray(255)
    private var entries = 0

    /** StreamReader.seek(0) rereads headers only until it has published its seek map. */
    fun output(actual: ExtractorOutput): ExtractorOutput = object : ForwardingExtractorOutput(actual) {
        override fun seekMap(seekMap: SeekMap) {
            super.seekMap(seekMap)
            seekMapPublished = true
        }
    }

    /** A normal return from reading headers means StreamReader has finished them (or ended). */
    fun returned() {
        delivered = 0
        headers = false
    }

    /** Every Ogg seek resets OggPacket and its page header. */
    fun seek(position: Long) {
        delivered = 0; pageAt = -1; laceCount = -1
        if (position == 0L && !seekMapPublished) { headers = true; entries = 0 }
    }

    fun input(actual: ExtractorInput): ExtractorInput = Observed(actual)

    private inner class Observed(input: ExtractorInput) : ForwardingExtractorInput(input) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            admit(length)
            return super.read(buffer, offset, length).also { if (it > 0) delivered += it }
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            admit(length)
            val from = position
            super.readFully(target, offset, length)
            arrived(from, target, offset, length)
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            admit(length)
            val from = position
            if (!super.readFully(target, offset, length, allowEndOfInput)) return false
            arrived(from, target, offset, length)
            return true
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            val at = peekPosition
            super.peekFully(target, offset, length)
            page(at, target, offset, length)
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val at = peekPosition
            if (!super.peekFully(target, offset, length, allowEndOfInput)) return false
            page(at, target, offset, length)
            return true
        }

        private fun admit(length: Int) {
            if (delivered + length > packetBudget) throw StreamMetadataRefusal()
        }

        private fun page(at: Long, target: ByteArray, offset: Int, length: Int) {
            if (!headers) return
            if (length == 27 && target[offset] == 'O'.code.toByte() && target[offset + 1] == 'g'.code.toByte() &&
                target[offset + 2] == 'g'.code.toByte() && target[offset + 3] == 'S'.code.toByte() && target[offset + 4] == 0.toByte()) {
                pageAt = at; pageSegments = target[offset + 26].toInt() and 255
            } else if (pageAt >= 0 && at == pageAt + 27 && length == pageSegments) {
                for (i in 0 until length) laces[i] = target[offset + i].toInt() and 255
                laceCount = length; bodyStart = at + length; pageAt = -1
            }
        }

        private fun arrived(from: Long, target: ByteArray, offset: Int, length: Int) {
            delivered += length
            if (!headers) return
            // Mirror OggPacket.calculatePacketSize from the run's starting segment.
            if (laceCount < 0 || from < bodyStart) throw StreamMetadataRefusal(true)
            val relative = from - bodyStart
            var segment = 0
            var reached = 0L
            while (segment < laceCount && reached < relative) reached += laces[segment++]
            if (reached != relative) throw StreamMetadataRefusal(true)
            while (true) {
                if (segment >= laceCount) throw StreamMetadataRefusal(true)
                var size = 0
                var last = segment
                while (last < laceCount) {
                    size += laces[last++]
                    if (laces[last - 1] != 255) break
                }
                if (size == 0) { segment = last; continue } // OggPacket skips an empty run.
                if (size != length) throw StreamMetadataRefusal(true)
                if (laces[last - 1] != 255) packet(target, offset + length)
                return
            }
        }

        /** OggPacket's array holds this packet from index 0, earlier page-runs included. */
        private fun packet(data: ByteArray, end: Int) {
            val start = when {
                startsWith(data, end, OPUS_TAGS) -> OPUS_TAGS.size
                startsWith(data, end, VORBIS_COMMENT) -> VORBIS_COMMENT.size
                else -> return
            }
            entries += admitVorbisComments(data, start, end, entryBudget - entries)
        }
    }

    private fun startsWith(data: ByteArray, end: Int, prefix: ByteArray) =
        end >= prefix.size && prefix.indices.all { data[it] == prefix[it] }
}
