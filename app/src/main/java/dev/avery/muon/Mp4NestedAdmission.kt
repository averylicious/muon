@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.MimeTypes

/**
 * Checks allocating declarations inside the actual retained MP4 leaf, before BoxParser sees it.
 * This covers standard sample-entry child framing, esds/codec payload lengths and iTunes/mdta data.
 * It is not a codec bitstream validator: SPS/AV1/decoder internals and other extractors stay separate.
 * No count-sized array or copy of the leaf is made here. Return the entries charged to this movie.
 */
internal fun admitMp4NestedMetadata(type: Int, data: ByteArray, size: Int, available: Int, quickTime: Boolean, maximumDepth: Int): Int =
    Mp4NestedAdmission(data, size, available, quickTime, maximumDepth).admit(type)

private fun atomType(name: String) = name.fold(0) { value, c -> (value shl 8) or c.code }
private val AUDIO_ENTRIES = listOf("mp4a", "enca", "ac-3", "ec-3", "ac-4", "mlpa", "dtsc", "dtse", "dtsh", "dtsl", "dtsx",
    "samr", "sawb", "lpcm", "sowt", "twos", ".mp2", ".mp3", "mha1", "mhm1", "alac", "alaw", "ulaw", "Opus", "fLaC",
    "iamf", "ipcm", "fpcm").map(::atomType).toSet()
private val VIDEO_ENTRIES = listOf("avc1", "avc3", "encv", "m1v ", "mp4v", "hvc1", "hev1", "vvc1", "vvi1", "s263", "H263",
    "h263", "vp08", "vp09", "av01", "dvav", "dva1", "dvhe", "dvh1", "apv1", "dav1").map(::atomType).toSet()
private val ITUNES_FIELDS = listOf("covr", "gnre", "disk", "trkn", "tmpo", "cpil", "aART", "sonm", "soal", "soar", "soaa",
    "soco", "rtng", "pgap", "sosn", "tvsh", "----").map(::atomType).toSet()
private val COPYRIGHT_FIELDS = listOf("nam", "trk", "cmt", "day", "ART", "too", "alb", "com", "wrt", "lyr", "gen", "grp", "mvn", "mvi")
    .map { atomType("\u0000" + it) }.toSet()
private val NESTED_BOXES = listOf("wave", "sinf", "schi").map(::atomType).toSet()

private class Mp4NestedAdmission(private val data: ByteArray, private val size: Int,
    private val available: Int, private val quickTime: Boolean, private val maximumDepth: Int) {
    private var used = 0
    private fun malformed(): Nothing = throw StreamMetadataRefusal(true)
    private fun need(at: Int, bytes: Long, end: Int = size) {
        if (at < 0 || bytes < 0 || at > end || bytes > end.toLong() - at) malformed()
    }
    private fun u8(at: Int, end: Int = size): Int { need(at, 1, end); return data[at].toInt() and 255 }
    private fun u16(at: Int, end: Int = size) = (u8(at, end) shl 8) or u8(at + 1, end)
    private fun u32(at: Int, end: Int = size): Long {
        need(at, 4, end)
        return ((data[at].toLong() and 255) shl 24) or ((data[at + 1].toLong() and 255) shl 16) or
            ((data[at + 2].toLong() and 255) shl 8) or (data[at + 3].toLong() and 255)
    }
    private fun charge(count: Long = 1) {
        if (count > available - used) throw StreamMetadataRefusal()
        used += count.toInt()
    }
    private fun end(at: Int, limit: Int): Int {
        val bytes = u32(at, limit)
        if (bytes < 8 || bytes > limit - at) malformed()
        return at + bytes.toInt()
    }
    private inline fun boxes(start: Int, limit: Int, depth: Int, tail: Int = 0,
        terminatingZero: Boolean = false, visit: (Int, Int, Int) -> Unit) {
        if (depth > maximumDepth) throw StreamMetadataRefusal()
        var at = start
        while (at < limit) {
            if (limit - at <= tail || (terminatingZero && limit - at == 4 && u32(at, limit) == 0L)) break
            val end = end(at, limit)
            val type = u32(at + 4, end).toInt()
            charge()
            visit(at, end, type)
            at = end
        }
    }

    fun admit(type: Int): Int {
        when (type) {
            atomType("keys") -> keys()
            atomType("stsd") -> descriptions()
            atomType("udta") -> userdata(8, size, 0)
            atomType("ilst") -> items(8, size, 0, indexed = true)
        }
        return used
    }

    private fun keys() {
        val count = u32(12)
        // Each key needs a size and namespace before its name; admit before String[count].
        need(16, count * 8)
        charge(count)
        var at = 16
        repeat(count.toInt()) { at = end(at, size) }
    }

    private fun descriptions() {
        val count = u32(12)
        need(16, count * 8)
        charge(count) // Before TrackEncryptionBox[count].
        var at = 16
        repeat(count.toInt()) {
            val stop = end(at, size)
            val type = u32(at + 4, stop).toInt()
            val child = when (type) {
                in AUDIO_ENTRIES -> {
                    need(at, 24, stop)
                    val version = if (quickTime) u16(at + 16, stop) else 0
                    when (version) { 0 -> at + 36; 1 -> at + 52; 2 -> at + 72; else -> stop }
                }
                in VIDEO_ENTRIES -> at + 86
                else -> stop // Unrecognized entries are skipped; text-entry copies already fit.
            }
            need(child, 0, stop)
            sampleChildren(child, stop, 0, terminatingZero = type in VIDEO_ENTRIES)
            at = stop
        }
    }

    private fun sampleChildren(start: Int, limit: Int, depth: Int, terminatingZero: Boolean = false) {
        boxes(start, limit, depth, terminatingZero = terminatingZero) { at, stop, type ->
            when (type) {
                in NESTED_BOXES -> sampleChildren(at + 8, stop, depth + 1)
                atomType("esds") -> esds(at + 12, stop)
                atomType("dfLa"), atomType("alac"), atomType("apvC") -> need(at, 12, stop)
                atomType("mhaC") -> {
                    need(at, 13, stop)
                    need(at + 13, u16(at + 11, stop).toLong(), stop)
                }
                atomType("mhaP") -> need(at + 9, u8(at + 8, stop).toLong(), stop)
                atomType("iacb") -> {
                    // The IAMF child uses unsigned LEB128, independently of esds' big-endian7bit size.
                    var next = at + 9; var shift = 0; var length = 0L
                    while (true) {
                        val value = u8(next++, stop)
                        length = length or ((value and 127).toLong() shl shift)
                        if (value and 128 == 0 || shift == 56) break
                        shift += 7
                    }
                    if (length > Int.MAX_VALUE) malformed()
                    need(next, length, stop)
                }
            }
        }
    }

    /** Mirror exactly the fields parseEsdsFromParent reads before allocating decoder-specific data. */
    private fun esds(start: Int, limit: Int) {
        var at = start
        fun byte(): Int = u8(at++, limit)
        fun skip(count: Int) { need(at, count.toLong(), limit); at += count }
        fun length(): Int {
            var value = 0L; var bytes = 0
            while (true) {
                val next = byte()
                value = (value shl 7) or (next and 127).toLong()
                if (++bytes > 4 || value > Int.MAX_VALUE) malformed()
                if (next and 128 == 0) break
            }
            need(at, value, limit)
            return value.toInt()
        }
        byte(); length(); skip(2) // ES_Descriptor tag/size, ES_ID.
        val flags = byte()
        if (flags and 128 != 0) skip(2)
        if (flags and 64 != 0) skip(byte())
        if (flags and 32 != 0) skip(2)
        byte(); length() // DecoderConfigDescriptor tag/size.
        val mime = MimeTypes.getMimeTypeFromMp4ObjectType(byte())
        if (mime == MimeTypes.AUDIO_MPEG || mime == MimeTypes.AUDIO_DTS || mime == MimeTypes.AUDIO_DTS_HD) return
        skip(12) // stream/buffer fields plus maximum and average bitrate.
        byte(); length() // DecoderSpecificInfo; its declared bytes are now known to fit.
    }

    private fun userdata(start: Int, limit: Int, depth: Int) {
        // parseUdta intentionally ignores a final incomplete8-byte header.
        boxes(start, limit, depth, tail = 7) { at, stop, type ->
            when (type) {
                atomType("meta") -> userMeta(at + 8, stop, depth + 1)
                atomType("smta") -> smta(at + 12, stop, depth + 1)
                atomType("chpl") -> {
                    // Pinned parseChpl starts after size/type, skips5 then reads a SIGNED count.
                    need(at, 17, stop)
                    val count = u32(at + 13, stop).toInt()
                    if (count > 0) {
                        need(at + 17, count * 9L, stop)
                        charge(count.toLong())
                        var chapter = at + 17
                        repeat(count) {
                            need(chapter, 9, stop)
                            val title = u8(chapter + 8, stop)
                            need(chapter + 9, title.toLong(), stop)
                            chapter += 9 + title
                        }
                    }
                }
            }
        }
    }

    private fun userMeta(start: Int, limit: Int, depth: Int) {
        need(start, 8, limit)
        var at = if (u32(start + 4, limit).toInt() == atomType("hdlr")) start else start + 4
        while (at < limit) {
            val stop = end(at, limit)
            charge()
            if (u32(at + 4, stop).toInt() == atomType("ilst")) {
                items(at + 8, stop, depth + 1, indexed = false)
                return // Pinned parseUdtaMeta ignores anything after its first ilst.
            }
            at = stop
        }
    }

    private fun smta(start: Int, limit: Int, depth: Int) {
        if (depth > maximumDepth) throw StreamMetadataRefusal()
        need(start, 0, limit)
        var at = start
        while (at < limit) {
            val stop = end(at, limit)
            charge()
            // The helper returns after saut (its fields/srfr have no unbounded declaration).
            if (u32(at + 4, stop).toInt() == atomType("saut")) return
            at = stop
        }
    }

    private fun items(start: Int, limit: Int, depth: Int, indexed: Boolean) {
        if (depth > maximumDepth) throw StreamMetadataRefusal()
        var at = start
        // Mdta stops with <=8bytes left; the iTunes helper can consume a short size word but cannot
        // allocate from a tail this short. Leave those bytes to the pinned parser, not a refusal.
        while (limit - at > 8) {
            val bytes = u32(at, limit)
            charge()
            if (!indexed && bytes < 8) { at += 4; continue } // parseIlstElement consumes only size.
            val stop = end(at, limit)
            val type = u32(at + 4, stop).toInt()
            val top = type ushr 24
            val copyright = (top == 0xa9 || top == 0xfd) && (type and 0x00ffffff) in COPYRIGHT_FIELDS
            when {
                indexed && type > 0 -> indexedField(at + 8, stop, depth + 1)
                !indexed && (type in ITUNES_FIELDS || copyright) && stop - at >= 16 -> {
                    if (type == atomType("----")) {
                        boxes(at + 8, stop, depth + 1) { child, childEnd, childType ->
                            when (childType) {
                                atomType("data") -> need(child, 16, childEnd)
                                atomType("mean"), atomType("name") -> need(child, 12, childEnd)
                            }
                        }
                    } else {
                        // Ordinary iTunes readers inspect only the first child and ignore any tail.
                        val child = at + 8
                        val childEnd = end(child, stop)
                        charge()
                        if (u32(child + 4, childEnd).toInt() == atomType("data")) need(child, 16, childEnd)
                    }
                }
            }
            at = stop
        }
    }

    private fun indexedField(start: Int, limit: Int, depth: Int) {
        if (depth > maximumDepth) throw StreamMetadataRefusal()
        var at = start
        while (at < limit) {
            val stop = end(at, limit)
            charge()
            if (u32(at + 4, stop).toInt() == atomType("data")) {
                need(at, 16, stop)
                return // Media3 returns immediately after the first data field.
            }
            at = stop
        }
    }
}
