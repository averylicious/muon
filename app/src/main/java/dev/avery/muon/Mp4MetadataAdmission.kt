@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorInput

/**
 * Ordinary MP4 metadata admission (#720), engineering proposals pending acceptance, not measured
 * safe memory or a decoder/heap guarantee. Retained leaf bytes: parsed leaves Mp4Extractor keeps
 * until their top-level container ends (and Sniffer's peek window over the same boxes). Table rows:
 * sample-table entries BoxParser/Mp4Extractor build across tracks, counting edit copies and the
 * accumulated-size array. Sample bytes: a declared sample-size admission, not a bound on every downstream codec allocation.
 */
internal const val MP4_RETAINED_BYTES = 32L * 1024 * 1024
internal const val MP4_TABLE_ROWS = 1_048_576L
internal const val MP4_SAMPLE_BYTES = 16L * 1024 * 1024
internal const val MP4_BOX_DEPTH = 16
/** A real ftyp holds a few brands; Sniffer allocates its declared size before reading it. */
private const val MP4_FTYP_BYTES = 64 * 1024
/** Pinned Sniffer.SEARCH_LENGTH and FixedSampleSizeRechunker.MAX_SAMPLE_SIZE. */
private const val SNIFF_SEARCH_BYTES = 4 * 1024
private const val RECHUNK_SAMPLE_BYTES = 8 * 1024L
private const val UNSET = C.LENGTH_UNSET.toLong()

internal class Mp4Limits(
    val retainedBytes: Long = MP4_RETAINED_BYTES,
    val tableRows: Long = MP4_TABLE_ROWS,
    val sampleBytes: Long = MP4_SAMPLE_BYTES,
    val boxes: Int = CONTAINER_METADATA_ENTRIES,
    val depth: Int = MP4_BOX_DEPTH,
    val metadataEntries: Int = CONTAINER_METADATA_ENTRIES,
)

private fun box(name: String) = name.fold(0) { value, c -> (value shl 8) or c.code }
private val MOOV = box("moov"); private val TRAK = box("trak"); private val MDIA = box("mdia")
private val MINF = box("minf"); private val STBL = box("stbl"); private val EDTS = box("edts")
private val FREE = box("free"); private val FTYP = box("ftyp"); private val UUID = box("uuid")
private val MOOF = box("moof"); private val MVEX = box("mvex"); private val PSSH = box("pssh")
private val STSD = box("stsd"); private val STSZ = box("stsz"); private val STZ2 = box("stz2")
private val STCO = box("stco"); private val CO64 = box("co64"); private val STSC = box("stsc")
private val STTS = box("stts"); private val CTTS = box("ctts"); private val STSS = box("stss")
private val ELST = box("elst"); private val MLPA = box("mlpa")
private val UDTA = box("udta"); private val MDAT = box("mdat"); private val QUICKTIME = box("qt  ")
/** Pinned Mp4Extractor.shouldParseContainerAtom / shouldParseLeafAtom. */
private val CONTAINERS = listOf("moov", "trak", "mdia", "minf", "stbl", "edts", "meta", "axte", "tref").map(::box).toSet()
private val LEAVES = listOf("mdhd", "mvhd", "hdlr", "stsd", "stts", "stss", "ctts", "elst", "stsc", "stsz", "stz2",
    "stco", "co64", "tkhd", "ftyp", "udta", "keys", "ilst", "chap").map(::box).toSet()
// Pinned FragmentedMp4Extractor's initial moov has a different whitelist and always parses
// sample descriptions with isQuickTime=false. This mode stops after the first moov, as below.
private val FRAGMENT_CONTAINERS = listOf("moov", "trak", "mdia", "minf", "stbl", "moof", "traf", "mvex", "edts", "meta").map(::box).toSet()
private val FRAGMENT_LEAVES = listOf("hdlr", "mdhd", "mvhd", "sidx", "stsd", "stts", "ctts", "stsc", "stsz", "stz2", "stco", "co64", "stss",
    "tfdt", "tfhd", "tkhd", "trex", "trun", "pssh", "saiz", "saio", "senc", "uuid", "sbgp", "sgpd", "elst", "mehd", "emsg", "udta", "keys", "ilst").map(::box).toSet()
/** Without child overrides, these sample entries always produce a rechunkable audio MIME. */
private val SIMPLE_RAW_ENTRIES = listOf("sowt", "twos", "lpcm", "alaw", "ulaw").map(::box).toSet()
// Pinned parseAudioSampleEntry ignores chan/free; btrt updates only bitrates, not format/frame size.
private val RAW_SAFE_CHILDREN = listOf("chan", "free", "btrt").map(::box).toSet()

private fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 255) shl 8) or (this[at + 1].toInt() and 255)
private fun ByteArray.u32(at: Int): Long = ((this[at].toLong() and 255) shl 24) or ((this[at + 1].toLong() and 255) shl 16) or
    ((this[at + 2].toLong() and 255) shl 8) or (this[at + 3].toLong() and 255)
private fun ByteArray.s32(at: Int): Int = u32(at).toInt()
private fun ByteArray.s64(at: Int): Long = (u32(at) shl 32) or u32(at + 4)

/** A parsed leaf, read in the delegate's own array: [size] bytes from index 0, header included. */
private class Leaf(val data: ByteArray, val size: Int) {
    fun fits(at: Long, bytes: Long) = at >= 0 && at + bytes <= size
    fun u32(at: Int): Long = if (fits(at.toLong(), 4)) data.u32(at) else throw StreamMetadataRefusal(true)
    /** Media3's readUnsignedIntToInt refuses a value with its top bit set. */
    fun count(at: Int): Long = u32(at).also { if (it > Int.MAX_VALUE) throw StreamMetadataRefusal(true) }
}

private class Frame(val type: Int, val end: Long) {
    val leaves = HashMap<Int, Leaf>()      // First of each type, as Mp4Box.getLeafBoxOfType.
    val containers = HashMap<Int, Frame>() // First completed of each type, as getContainerBoxOfType.
    val traks = ArrayList<Frame>()         // Every trak, as BoxParser.parseTraks.
}

/**
 * Follows Mp4Extractor's own atom reads (#720). Its 8-byte header read is
 * `readFully(atomHeader, 0, 8, true)` into a 16-byte array, then a parsed leaf is allocated at its
 * declared size before the body is read: refuse that declaration in the header read itself. The
 * body arrives by `readFully(data, 8, size - 8)` before any parser sees it; table counts are checked
 * there. Container ends mirror processAtomEnded, including an unparsed atom large enough to be
 * skipped by a seek, which ends its ancestors without another read. When moov ends, its tables are
 * admitted before processMoovAtom runs; afterwards samples pass through untouched until a seek to 0.
 */
internal class Mp4MetadataAdmission(private val limits: Mp4Limits = Mp4Limits(), private val fragmented: Boolean = false) {
    private var done = false
    private var seenFtyp = false
    private var quickTime = false
    private val stack = ArrayList<Frame>()
    private var retained = 0L
    private var boxes = 0
    private var metadataEntries = 0
    private var largeAt = -1L
    private var largeType = 0
    private var leafAt = -1L
    private var leafType = 0
    private var leafSize = 0

    /** Mp4Extractor rereads its atoms only from the start. */
    fun seek(position: Long) {
        if (position != 0L) return
        done = false; seenFtyp = false; quickTime = false
        stack.clear(); retained = 0; boxes = 0; metadataEntries = 0; largeAt = -1; leafAt = -1
    }

    fun input(actual: ExtractorInput): ExtractorInput = if (done) actual else Observed(actual)

    private inner class Observed(input: ExtractorInput) : ForwardingExtractorInput(input) {
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val at = position
            if (!super.readFully(target, offset, length, allowEndOfInput)) return false
            if (!done && leafAt < 0 && largeAt < 0 && offset == 0 && length == 8 && target.size == 16)
                header(at, target, this.length)
            return true
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            val at = position
            super.readFully(target, offset, length)
            if (done || offset != 8) return
            if (largeAt >= 0 && at == largeAt + 8 && length == 8 && target.size == 16) extended(target, this.length)
            else if (leafAt >= 0 && at == leafAt + 8 && target.size == leafSize && length == leafSize - 8) body(target)
        }
    }

    private fun header(at: Long, data: ByteArray, streamLength: Long) {
        val declared = data.u32(0)
        if (declared == 1L) { largeAt = at; largeType = data.s32(4); return } // A 64-bit size follows.
        resolve(at, data.s32(4), declared, 8, streamLength)
    }

    private fun extended(data: ByteArray, streamLength: Long) {
        val at = largeAt
        largeAt = -1
        val declared = data.s64(8)
        if (declared < 0) throw StreamMetadataRefusal(true) // readUnsignedLongToLong refuses it too.
        resolve(at, largeType, declared, 16, streamLength)
    }

    private fun resolve(at: Long, type: Int, declared: Long, header: Int, streamLength: Long) {
        var size = declared
        if (header == 8 && size == 0L) { // Extends to the end of the stream, or else of its container.
            val end = if (streamLength != UNSET) streamLength else stack.lastOrNull()?.end ?: UNSET
            if (end != UNSET) size = end - at
        }
        if (size < header) {
            if (type == FREE && header == 8) size = 8 else return // Mp4Extractor refuses it itself.
        }
        if (size > Long.MAX_VALUE - at) throw StreamMetadataRefusal(true)
        val end = at + size
        if (!fragmented && type == MDAT && !seenFtyp) quickTime = true // Same fallback as readAtomPayload.
        val parent = stack.lastOrNull()
        if (parent != null && end > parent.end) throw StreamMetadataRefusal(true)
        val container = type in (if (fragmented) FRAGMENT_CONTAINERS else CONTAINERS)
        val leaf = !container && type in (if (fragmented) FRAGMENT_LEAVES else LEAVES)
        // Pinned fragmented container-end arithmetic uses HEADER_SIZE even for extended headers.
        // Do not certify that inconsistent boundary or let first-moov admission end prematurely.
        if (fragmented && container && header != 8) throw StreamMetadataRefusal(true)
        if ((container || leaf) && streamLength != UNSET && end > streamLength) throw StreamMetadataRefusal(true)
        when {
            container -> {
                if (stack.size >= limits.depth || ++boxes > limits.boxes) throw StreamMetadataRefusal()
                stack.add(Frame(type, end))
                if (end == at + header) ended(end)
            }
            leaf -> {
                // Mp4Extractor checkState-refuses these before allocating; refuse them as content instead.
                if (header != 8 || size > Int.MAX_VALUE) throw StreamMetadataRefusal(true)
                if (++boxes > limits.boxes || retained + size > limits.retainedBytes) throw StreamMetadataRefusal()
                retained += size
                leafAt = at; leafType = type; leafSize = size.toInt()
            }
            // Skipped or seeked over: processAtomEnded may run without any further input call.
            else -> ended(end)
        }
    }

    private fun body(data: ByteArray) {
        val leaf = Leaf(data, leafSize)
        if (leafType == FTYP) {
            seenFtyp = true
            quickTime = leaf.size >= 12 && leaf.u32(8).toInt() == QUICKTIME
            var at = 16
            while (!quickTime && leaf.fits(at.toLong(), 4)) {
                quickTime = leaf.u32(at).toInt() == QUICKTIME
                at += 4
            }
        }
        // Ordinary Mp4Extractor parses only the first movie-level udta. Other copies still cost
        // retained bytes, but their unparsed nested declarations cannot create native objects.
        val parent = stack.lastOrNull()
        val inspect = if (leafType == UDTA && (parent == null || parent.type != MOOV || parent.leaves.containsKey(UDTA))) 0 else leafType
        metadataEntries += admitMp4NestedMetadata(inspect, data, leafSize,
            limits.metadataEntries - metadataEntries, quickTime, limits.depth)
        validate(leafType, leaf)
        if (fragmented && leafType == PSSH && parent?.type == MOOV) admitPssh(leaf)
        val end = leafAt + leafSize
        leafAt = -1
        stack.lastOrNull()?.leaves?.putIfAbsent(leafType, leaf)
        ended(end)
    }

    /** Pinned PsshAtomUtil creates the UUID array before reading its key IDs. */
    private fun admitPssh(leaf: Leaf) {
        if (leaf.size < 32 || leaf.u32(0) != leaf.size.toLong()) return // Native returns null.
        val version = leaf.u32(8) ushr 24
        if (version != 1L) return // Version0 has no count array; unsupported versions return null.
        val count = leaf.count(28)
        if (!leaf.fits(32, count * 16 + 4)) throw StreamMetadataRefusal(true)
        if (count > limits.metadataEntries - metadataEntries) throw StreamMetadataRefusal()
        metadataEntries += count.toInt()
    }

    private fun ended(position: Long) {
        while (stack.isNotEmpty() && stack.last().end == position) {
            val frame = stack.removeAt(stack.lastIndex)
            if (frame.type == MOOV) {
                admitMoov(frame)
                stack.clear(); done = true; retained = 0; boxes = 0; metadataEntries = 0
                return
            }
            val parent = stack.lastOrNull() ?: continue
            parent.containers.putIfAbsent(frame.type, frame)
            if (frame.type == TRAK && parent.type == MOOV) parent.traks += frame
        }
        if (stack.isEmpty()) { retained = 0; boxes = 0; metadataEntries = 0 } // Top-level boxes are processed or dropped.
    }

    /** Counts BoxParser reads before allocating from them must be backed by the leaf's own bytes. */
    private fun validate(type: Int, leaf: Leaf) {
        fun table(width: Int) {
            val count = leaf.count(12)
            if (!leaf.fits(16, count * width)) throw StreamMetadataRefusal(true)
        }
        when (type) {
            STSZ -> {
                val fixed = leaf.count(12)
                val count = leaf.count(16)
                if (fixed == 0L && !leaf.fits(20, count * 4)) throw StreamMetadataRefusal(true)
            }
            STZ2 -> {
                val bits = when ((leaf.u32(12) and 255).toInt()) { 8 -> 8L; 16 -> 16L; else -> 4L }
                val count = leaf.count(16)
                if (!leaf.fits(20, (count * bits + 7) / 8)) throw StreamMetadataRefusal(true)
            }
            STCO, STSS -> table(4)
            CO64, STTS, CTTS -> table(8)
            STSC -> table(12)
            ELST -> {
                if (!leaf.fits(8, 1)) throw StreamMetadataRefusal(true)
                table(if (leaf.data[8].toInt() == 1) 20 else 12)
            }
            STSD -> {
                val count = leaf.u32(12).toInt() // Read signed: it sizes TrackEncryptionBox[count].
                if (count < 0 || !leaf.fits(16, count * 8L)) throw StreamMetadataRefusal(true)
                var at = 16L
                repeat(count) {
                    val entry = leaf.u32(at.toInt()).toInt()
                    if (entry < 8 || !leaf.fits(at, entry.toLong())) throw StreamMetadataRefusal(true)
                    at += entry
                }
            }
        }
    }

    private fun admitMoov(moov: Frame) {
        var rows = 0L
        for (trak in moov.traks) {
            val stbl = trak.containers[MDIA]?.containers[MINF]?.containers[STBL] ?: continue
            rows += trackRows(trak, stbl)
            if (rows > limits.tableRows) throw StreamMetadataRefusal()
        }
    }

    /** One track's tables as BoxParser.parseStbl and Mp4Extractor would build them, or an upper bound. */
    private fun trackRows(trak: Frame, stbl: Frame): Long {
        val stsd = stbl.leaves[STSD] ?: return 0 // parseTrak drops the track.
        val stsz = stbl.leaves[STSZ]
        val sizes = stsz ?: stbl.leaves[STZ2] ?: return 0 // parseStbl refuses it before any table.
        val count = sizes.u32(16)
        if (count == 0L) return 0
        val chunks = stbl.leaves[STCO] ?: stbl.leaves[CO64] ?: return 0
        val stsc = stbl.leaves[STSC] ?: return 0
        val stts = stbl.leaves[STTS] ?: return 0
        val entry = Entry(stsd, quickTime)
        val fixed = stsz?.u32(12) ?: 0L
        val timingAllowsRechunk = fixed != 0L && stts.u32(12) == 1L &&
            (stbl.leaves[CTTS]?.u32(12) ?: 0L) == 0L && (stbl.leaves[STSS]?.u32(12) ?: 0L) == 0L
        val edits = trak.containers[EDTS]?.leaves?.get(ELST)?.let(::mediaEdits)
        fun rows(samples: Long, chunkRows: Long = 0): Long {
            // Count the primary tables, potential edit copies and accumulated sizes. This is a
            // conservative row-admission policy, not byte-exact heap accounting.
            return chunkRows + samples + if (edits == null) samples
                else samples * edits + samples * maxOf(1L, edits)
        }
        val chunkCount = chunks.u32(12)
        if (timingAllowsRechunk && entry.simpleRaw) {
            // Proven single raw entry, only known harmless children. PCM can replace an inconsistent stsz
            // size with the format frame size; every supported encoding uses at most8bytes/channel.
            val frame = maxOf(fixed, 8L * entry.channels)
            return rows(rechunked(stsc, chunkCount, maxOf(1L, RECHUNK_SAMPLE_BYTES / frame)), chunkCount)
        }
        var largest = if (stsz != null && fixed != 0L) fixed else largest(sizes, stsz != null, count)
        if (entry.trueHd) largest *= 16
        if (largest > limits.sampleBytes) throw StreamMetadataRefusal()
        val normalRows = rows(count)
        if (!timingAllowsRechunk) return normalRows
        // A format-changing child, encryption or several descriptions can still select RAW, even
        // when we cannot prove it from the entry header. Its allocation follows chunk samples,
        // which need NOT agree with stsz count. Admit BOTH paths; one buffer per declared chunk
        // sample is an upper bound on every successful rechunk. Never call normal count safer.
        val possibleRawRows = rows(rechunked(stsc, chunkCount, 1), chunkCount)
        return maxOf(normalRows, possibleRawRows)
    }

    /** Mirrors ChunkIterator and FixedSampleSizeRechunker's buffer count, stopping at the budget. */
    private fun rechunked(stsc: Leaf, chunks: Long, perBuffer: Long): Long {
        var changes = stsc.u32(12)
        var at = 20 // The constructor reads first_chunk at 16.
        var next = 0L
        var samples = 0L
        var total = 0L
        for (index in 0 until chunks) {
            if (index == next) {
                if (!stsc.fits(at.toLong(), 8)) return total // Media3 fails here, after its chunk arrays.
                samples = stsc.u32(at); at += 8
                next = if (--changes > 0) {
                    if (!stsc.fits(at.toLong(), 4)) return total
                    stsc.u32(at).also { at += 4 } - 1
                } else -1
            }
            total += (samples + perBuffer - 1) / perBuffer
            if (total > limits.tableRows) return total
        }
        return total
    }

    private fun largest(sizes: Leaf, stsz: Boolean, count: Long): Long {
        if (!stsz) return 65_535 // stz2 sizes are at most 16 bits.
        var largest = 0L
        for (i in 0 until count) largest = maxOf(largest, sizes.u32(20 + 4 * i.toInt()))
        return largest
    }

    private fun mediaEdits(elst: Leaf): Long {
        val wide = elst.data[8].toInt() == 1
        val count = elst.u32(12)
        var media = 0L
        var at = 16
        for (i in 0 until count) {
            val time = if (wide) elst.data.s64(at + 8) else elst.data.s32(at + 4).toLong()
            if (time != -1L) media++
            at += if (wide) 20 else 12
        }
        return media
    }

    /** Only prove the raw fast path when every framed child is known not to change format/frame size. */
    private class Entry(stsd: Leaf, quickTime: Boolean) {
        var simpleRaw = false
        var trueHd = false
        var channels = 0L
        init {
            val count = stsd.u32(12).toInt()
            var at = 16
            repeat(count) {
                val size = stsd.data.s32(at)
                val type = stsd.data.s32(at + 4)
                if (type == MLPA) trueHd = true
                if (count == 1 && type in SIMPLE_RAW_ENTRIES && size >= 36) {
                    val version = if (quickTime) stsd.data.u16(at + 16) else 0
                    val prefix = when (version) { 0 -> 36; 1 -> 52; 2 -> 72; else -> -1 }
                    if (prefix > 0 && size >= prefix && safeChildren(stsd, at + prefix, at + size)) {
                        channels = if (version == 2) stsd.data.u32(at + 48) else stsd.data.u16(at + 24).toLong()
                        simpleRaw = true
                    }
                }
                at += size
            }
        }
        private fun safeChildren(stsd: Leaf, start: Int, end: Int): Boolean {
            var at = start
            while (at < end) {
                if (end - at < 8) return false
                val bytes = stsd.data.u32(at)
                if (bytes < 8 || bytes > end - at || stsd.data.s32(at + 4) !in RAW_SAFE_CHILDREN) return false
                at += bytes.toInt()
            }
            return true
        }
    }

}

/**
 * The pinned Sniffer, shared by Mp4Extractor and FragmentedMp4Extractor, peeks over atoms it skips
 * and allocates a parsed ftyp's declared size before peeking it. Its window grows by each moov/uuid
 * size. Refuse an advance past [peekBytes] before DefaultExtractorInput sizes its peek buffer, and
 * an oversized ftyp in the header peek that reaches it, rather than letting another candidate try.
 */
internal class Mp4SniffInput(input: ExtractorInput, private val peekBytes: Long) : ForwardingExtractorInput(input) {
    private val start = input.peekPosition
    private val streamLength = input.length
    private var window = if (streamLength == UNSET || streamLength > SNIFF_SEARCH_BYTES) SNIFF_SEARCH_BYTES
        else streamLength.toInt()
    private var largeType = 0
    private var large = false

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        admit(length)
        if (!super.peekFully(target, offset, length, allowEndOfInput)) return false
        if (offset == 0 && length == 8 && allowEndOfInput) {
            val size = target.u32(0)
            if (size == 1L) { large = true; largeType = target.s32(4) } else atom(target.s32(4), size, 8)
        }
        return true
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        admit(length)
        super.peekFully(target, offset, length)
        if (large && offset == 8 && length == 8) { large = false; atom(largeType, target.s64(8), 16) }
    }

    override fun advancePeekPosition(length: Int) { admit(length); super.advancePeekPosition(length) }
    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        admit(length)
        return super.advancePeekPosition(length, allowEndOfInput)
    }

    private fun admit(length: Int) {
        if (peekPosition - start + length > peekBytes) throw StreamMetadataRefusal()
    }

    /** Sniffer's own window arithmetic, to know whether it will allocate this ftyp. */
    private fun atom(type: Int, declared: Long, header: Int) {
        var size = declared
        if (header == 8 && size == 0L && streamLength != UNSET) size = streamLength - peekPosition + header
        if (size < header) { if (type == FREE && header == 8) size = header.toLong() else return }
        if (type == MOOV || type == UUID) {
            window += size.toInt()
            if (streamLength != UNSET && window > streamLength) window = streamLength.toInt()
            if (type == MOOV) return
        }
        if (type == TRAK || type == MDIA || type == MINF || type == MOOF || type == MVEX) return
        if (type == STBL && size > 1_000_000) return
        if (peekPosition - start + size - header >= window) return
        if (type == FTYP && size - header > minOf(MP4_FTYP_BYTES.toLong(), peekBytes - (peekPosition - start))) throw StreamMetadataRefusal()
    }
}
