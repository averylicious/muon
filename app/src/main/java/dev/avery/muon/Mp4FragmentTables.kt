@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.util.Util

/**
 * Counts the pinned fragmented reader's retained arrays before their creation (#720).
 * Native fragment arrays keep their high-water capacities across fragments AND seek(); its
 * ChunkIndexMerger retains distinct starting timestamps and creates merged copies. Keep bounded
 * scalar censuses, not a new copy of native samples or a declaration-sized allocation.
 * Weighted admission is a resource policy, not every downstream codec/decoder/heap allocation.
 */
internal class Mp4FragmentTables(private val limits: Mp4Limits) {
    private data class Peak(val samples: Long, val runs: Long, val encryption: Long) {
        val rows get() = samples * 4 + runs * 2 // Four headroom sample arrays, two run arrays.
    }
    private val tracks = HashMap<Int, Peak>()
    private val indexes = HashMap<Long, Long>()
    private var fragmentRows = 0L
    private var encryptionBytes = 0L
    private var movieRows = 0L
    private var tailRows = 0L
    private var indexEntries = 0L

    fun movie(rows: Long) {
        admit(rows, fragmentRows, tailRows, indexEntries)
        movieRows = rows
    }
    fun tail(rows: Long) {
        val peak = maxOf(tailRows, rows)
        admit(movieRows, fragmentRows, peak, indexEntries)
        tailRows = peak
    }
    private fun admit(movie: Long = movieRows, fragments: Long = fragmentRows,
        tail: Long = tailRows, indexes: Long = indexEntries, temporary: Long = 0) {
        // Four native index arrays plus four merged arrays. Exact heap bytes/GC overlap differ.
        if (movie + fragments + tail + indexes * 8 + temporary > limits.tableRows) throw StreamMetadataRefusal()
    }

    /** Fields actually present in trun must fit before initTables, not only its later sample loop. */
    fun checkRun(data: ByteArray, size: Int) {
        val box = Fields(data, size)
        val flags = box.u32(8).toInt()
        val count = box.count(12)
        val start = 16 + (if (flags and 1 != 0) 4 else 0) + (if (flags and 4 != 0) 4 else 0)
        val fields = Integer.bitCount(flags and 0xf00)
        box.need(start, count * fields * 4)
        if (count > limits.tableRows) throw StreamMetadataRefusal()
        if (flags and 0x200 != 0) {
            val sizeOffset = start + if (flags and 0x100 != 0) 4 else 0
            for (i in 0 until count) {
                val sample = box.count(sizeOffset + (i * fields * 4).toInt())
                if (sample > limits.sampleBytes) throw StreamMetadataRefusal()
            }
        }
    }

    fun fragment(tfhd: ByteArray, tfhdSize: Int, runs: List<Pair<ByteArray, Int>>,
        saiz: ByteArray?, saizSize: Int, encryptionHint: Long) {
        val track = Fields(tfhd, tfhdSize).u32(12).toInt()
        var samples = 0L
        var runCount = 0L
        for ((data, size) in runs) {
            val count = Fields(data, size).count(12)
            if (count > 0) { samples += count; runCount++ }
            // Do not let native signed addition or the later125% multiply overflow.
            if (samples > limits.tableRows) throw StreamMetadataRefusal()
        }
        val old = tracks[track] ?: Peak(0, 0, 0)
        val capacity = if (samples > old.samples) samples * 125 / 100 else old.samples
        val bytes = maxOf(old.encryption, encryptionHint,
            if (saiz == null) 0 else encryption(Fields(saiz, saizSize), samples))
        val peak = Peak(capacity, maxOf(old.runs, runCount), bytes)
        val rows = fragmentRows - old.rows + peak.rows
        val crypto = encryptionBytes - old.encryption + bytes
        if (crypto > limits.retainedBytes || (track !in tracks && tracks.size >= limits.boxes)) throw StreamMetadataRefusal()
        admit(fragments = rows)
        tracks[track] = peak
        fragmentRows = rows; encryptionBytes = crypto
    }

    private fun encryption(box: Fields, samples: Long): Long {
        val flags = box.u32(8)
        val at = 12 + if (flags and 1 != 0L) 8 else 0
        val fixed = box.u8(at)
        val count = box.count(at + 1)
        if (count > samples) throw StreamMetadataRefusal(true) // Native refuses before allocation.
        if (fixed != 0) return count * fixed
        box.need(at + 5, count)
        var total = 0L
        for (i in 0 until count) total += box.u8(at + 5 + i.toInt())
        return total
    }

    /** Native deduplicates first timestamps, not file offsets, and does NOT clear them on seek. */
    fun index(data: ByteArray, size: Int) {
        val box = Fields(data, size)
        val wide = box.u32(8) ushr 24 != 0L
        val start = if (wide) 40 else 32
        val count = box.u16(start - 2).toLong()
        box.need(start, count * 12)
        val time = if (wide) box.nonnegative64(20) else box.u32(20)
        val timescale = box.u32(16)
        if (timescale == 0L) return // Native scaling fails before allocating its index arrays.
        val timestamp = Util.scaleLargeTimestamp(time, 1_000_000L, timescale)
        val retained = count > 0 && timestamp !in indexes
        val entries = indexEntries + if (retained) count else 0
        if (retained && indexes.size >= limits.boxes) throw StreamMetadataRefusal()
        // A duplicate still allocates a temporary index before ChunkIndexMerger ignores it.
        admit(indexes = entries, temporary = if (retained) 0 else count * 4)
        if (retained) { indexes[timestamp] = count; indexEntries = entries }
    }

    private class Fields(private val data: ByteArray, private val size: Int) {
        fun need(at: Int, bytes: Long) {
            if (at < 0 || bytes < 0 || at > size || bytes > size.toLong() - at) throw StreamMetadataRefusal(true)
        }
        fun u8(at: Int): Int { need(at, 1); return data[at].toInt() and 255 }
        fun u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
        fun u32(at: Int): Long { need(at, 4); return ((u8(at).toLong() shl 24) or
            (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()) }
        fun count(at: Int) = u32(at).also { if (it > Int.MAX_VALUE) throw StreamMetadataRefusal(true) }
        fun nonnegative64(at: Int) = ((u32(at) shl 32) or u32(at + 4)).also {
            if (it < 0) throw StreamMetadataRefusal(true)
        }
    }
}
