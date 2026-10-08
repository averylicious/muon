@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import java.io.IOException
import java.io.Closeable
import java.io.RandomAccessFile

/** Fixed preparation budgets, not a claimed limit on an already-open legacy SimpleCache. */
internal const val MIGRATION_KEY_BYTES = 16 * 1024
internal const val MIGRATION_METADATA_BYTES = 4 * 1024 * 1024
internal const val MIGRATION_METADATA_FIELDS = 256
internal const val MIGRATION_RANGES = 256
internal data class MigrationRange(val position: Long, val length: Long)
internal data class MigrationCopyEvidence(val bytes: Long, val ranges: Int)
private data class MigrationField(val name: String, val bytes: ByteArray)
private data class MigrationSnapshot(val ranges: List<MigrationRange>, val fields: List<MigrationField>) {
    fun same(other: MigrationSnapshot): Boolean = ranges == other.ranges && fields.size == other.fields.size &&
        fields.indices.all { fields[it].name == other.fields[it].name && fields[it].bytes.contentEquals(other.fields[it].bytes) }
}

/**
 * Opt-in migration preparation only; no production caller or source-deletion authority. Caller must
 * exclusively own a fresh destination and hold the source's mutation/availability barrier throughout.
 * [checkpoint] must throw when that barrier, cancellation or deadline changes. Every retained range,
 * including partial bytes beyond a declared length, and every metadata field is preserved verbatim.
 * Failure leaves the source alone and may retain uncertain/partial destination files for recovery.
 */
internal object CacheMigrationPreparation {
    /** Supported scalar API; never copies a whole native span/key set. Rejects non-progress/overflow. */
    fun ranges(cache: Cache, key: String, checkpoint: () -> Unit = {}): List<MigrationRange> {
        if (key.length.toLong() * 2 > MIGRATION_KEY_BYTES) throw IOException("Legacy key exceeds migration budget")
        val ranges = ArrayList<MigrationRange>()
        var position = 0L
        while (position < Long.MAX_VALUE) {
            checkpoint()
            val remaining = Long.MAX_VALUE - position
            val block = cache.getCachedLength(key,position,remaining)
            if (block == 0L || block == Long.MIN_VALUE) throw IOException("Cache extent cannot be established")
            val length = if (block < 0) -block else block
            if (length > remaining) throw IOException("Cache extent overflows")
            if (block > 0) {
                if (ranges.size >= MIGRATION_RANGES) throw IOException("Legacy fragmentation exceeds migration budget")
                ranges += MigrationRange(position,length)
            }
            position += length
        }
        return ranges
    }

    private fun snapshot(cache: Cache, key: String, checkpoint: () -> Unit): MigrationSnapshot {
        checkpoint()
        val metadata = cache.getContentMetadata(key) as? DefaultContentMetadata
            ?: throw IOException("Unknown cache metadata implementation")
        var bytes = 0L
        val fields = ArrayList<MigrationField>()
        for ((name,value) in metadata.entrySet()) {
            checkpoint()
            if (fields.size >= MIGRATION_METADATA_FIELDS) throw IOException("Legacy metadata has too many fields")
            bytes += name.length.toLong() * 2 + value.size.toLong()
            if (bytes > MIGRATION_METADATA_BYTES) throw IOException("Legacy metadata exceeds migration budget")
            fields += MigrationField(name,value.copyOf())
        }
        fields.sortBy { it.name }
        return MigrationSnapshot(ranges(cache,key,checkpoint),fields)
    }

    fun copy(source: Cache, target: Cache, key: String, checkpoint: () -> Unit,
        outputs: MoveFileOutputs = MoveFileOutputs.Real): MigrationCopyEvidence {
        if (source === target) throw IOException("Migration needs a separate destination")
        val before = snapshot(source,key,checkpoint)
        val empty = snapshot(target,key,checkpoint)
        if (empty.ranges.isNotEmpty() || empty.fields.isNotEmpty()) throw IOException("Migration destination is not fresh")
        var total = 0L
        for (range in before.ranges) {
            checkpoint()
            val hole = target.startReadWriteNonBlocking(key,range.position,range.length)
                ?: throw IOException("Migration destination is busy")
            if (hole.isCached) throw IOException("Migration destination changed")
            try {
                val sink = StrictMoveSink(target,outputs)
                val spec = spec(key,range)
                val reader = RangeReader(source,key,range)
                try {
                    sink.open(spec)
                    val buffer = ByteArray(64 * 1024)
                    var left = range.length
                    while (left > 0) {
                        checkpoint()
                        val count = reader.read(buffer,0,minOf(left,buffer.size.toLong()).toInt())
                        if (count <= 0) throw IOException("Migration source bytes became unavailable")
                        sink.write(buffer,0,count)
                        left -= count
                    }
                } finally {
                    // A failed read/cancellation can leave a committed partial replacement. It never
                    // grants source removal, and retry requires a separately validated fresh target.
                    try { reader.close() } finally { sink.close() }
                }
                if (!sink.clean) throw IOException("Migration replacement was not committed cleanly")
            } finally { target.releaseHoleSpan(hole) }
            total = Math.addExact(total,range.length)
        }
        checkpoint()
        if (!before.same(snapshot(source,key,checkpoint))) throw IOException("Migration source changed")
        val mutations = ContentMetadataMutations()
        before.fields.forEach { mutations.set(it.name,it.bytes) }
        target.applyContentMetadataMutations(key,mutations)
        if (!before.same(snapshot(target,key,checkpoint))) throw IOException("Migration replacement metadata or extent differs")
        for (range in before.ranges) compare(source,target,key,range,checkpoint)
        checkpoint()
        if (!before.same(snapshot(source,key,checkpoint)) || !before.same(snapshot(target,key,checkpoint)))
            throw IOException("Migration resources changed during verification")
        // Evidence of this preparation, NOT a durable receipt or permission to delete either copy.
        return MigrationCopyEvidence(total,before.ranges.size)
    }

    private fun spec(key: String, range: MigrationRange) = DataSpec.Builder()
        .setUri(Uri.parse("muon-migration://local/resource")).setKey(key)
        .setPosition(range.position).setLength(range.length).build()
    private fun compare(source: Cache, target: Cache, key: String, range: MigrationRange, checkpoint: () -> Unit) {
        val left = RangeReader(source,key,range)
        val right = RangeReader(target,key,range)
        try {
            val a = ByteArray(64 * 1024)
            val b = ByteArray(64 * 1024)
            var remaining = range.length
            while (remaining > 0) {
                checkpoint()
                val wanted = minOf(remaining,a.size.toLong()).toInt()
                readExactly(left,a,wanted); readExactly(right,b,wanted)
                for (i in 0 until wanted) if (a[i] != b[i]) throw IOException("Migration replacement bytes differ")
                remaining -= wanted
            }
        } finally { try { left.close() } finally { right.close() } }
    }
    /** Read retained span files through the supported cached-span API. CacheDataSource obeys declared
     * length, which would skip original partial bytes beyond inconsistent legacy length metadata. */
    private class RangeReader(private val cache: Cache, private val key: String, range: MigrationRange) : Closeable {
        private var position = range.position
        private var remaining = range.length
        private var file: RandomAccessFile? = null
        private var spanRemaining = 0L
        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            if (file == null) {
                val span = cache.startReadWriteNonBlocking(key,position,remaining)
                    ?: throw IOException("Migration source is busy")
                if (!span.isCached) {
                    cache.releaseHoleSpan(span)
                    throw IOException("Migration retained bytes disappeared")
                }
                if (span.position < 0 || span.position > position || span.length <= position-span.position)
                    throw IOException("Migration span extent invalid")
                val within = position-span.position
                val opened = RandomAccessFile(span.file ?: throw IOException("Migration span file missing"),"r")
                try {
                    if (opened.length() < span.length) throw IOException("Migration span file truncated")
                    opened.seek(within)
                } catch (failure: Throwable) { opened.close(); throw failure }
                file = opened
                spanRemaining = minOf(remaining,span.length-within)
            }
            val wanted = minOf(length.toLong(),remaining,spanRemaining).toInt()
            val read = requireNotNull(file).read(buffer,offset,wanted)
            if (read <= 0) throw IOException("Migration span bytes unreadable")
            remaining -= read; position += read; spanRemaining -= read
            if (spanRemaining == 0L) { val old=file; file=null; old?.close() }
            return read
        }
        override fun close() { val old=file; file=null; old?.close() }
    }

    private fun readExactly(reader: RangeReader, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = reader.read(buffer,offset,length-offset)
            if (read <= 0) throw IOException("Migration comparison bytes unavailable")
            offset += read
        }
    }
}
