@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.media3.datasource.cache.CacheSpan
import java.io.Closeable
import java.io.File
import java.util.TreeSet

/** Eviction ordering only. Failure never supplies authority to remove a cached resource. */
internal interface PlayedSpanOrder {
    val count: Long
    fun add(span: CacheSpan)
    fun remove(span: CacheSpan)
    fun oldest(keep: String?, removable: (String) -> Boolean): String?
    fun initialized() {}
    fun forEachKey(emit: (String) -> Boolean)
}

/** Small fixture/legacy constructor seam; production explicitly supplies the disk order. */
internal class MemoryPlayedSpanOrder : PlayedSpanOrder {
    private val spans = TreeSet<CacheSpan> { a, b ->
        if (a.lastTouchTimestamp != b.lastTouchTimestamp) a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp) else a.compareTo(b)
    }
    override val count: Long get() = spans.size.toLong()
    override fun add(span: CacheSpan) { spans.add(span) }
    override fun remove(span: CacheSpan) { spans.remove(span) }
    override fun oldest(keep: String?, removable: (String) -> Boolean): String? =
        spans.firstOrNull { it.key != keep && removable(it.key) }?.key
    override fun forEachKey(emit: (String) -> Boolean) {
        for (key in spans.map { it.key }.distinct().sorted()) if (!emit(key)) break
    }
}

/**
 * Private derived span order, reset from this cache's actual startup callbacks. It retains no
 * CacheSpan/file/key collection; native Cache remains the byte authority. All calls occur under
 * the cache's lock. Native page-cache request is not an exact SQLite/process heap bound.
 */
internal class DiskPlayedSpanOrder(private val file: File) : PlayedSpanOrder, Closeable {
    private var database: SQLiteDatabase? = null
    private var failed = false
    private var complete = false
    private fun db(): SQLiteDatabase? {
        if (failed) return null
        return database ?: try {
            val parent = requireNotNull(file.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "No private played order directory" }
            SQLiteDatabase.openOrCreateDatabase(file, null).also { opened ->
                database = opened // Closeable even if schema setup fails.
                opened.execSQL("PRAGMA cache_size=-256")
                opened.execSQL("CREATE TABLE IF NOT EXISTS spans(key BLOB NOT NULL, position INTEGER NOT NULL, " +
                    "touch INTEGER NOT NULL, PRIMARY KEY(key,position))")
                opened.execSQL("CREATE INDEX IF NOT EXISTS oldest_span ON spans(touch,key,position)")
                opened.delete("spans", null, null) // Never trust a previous process's ordering.
            }
        } catch (_: Exception) { failed = true; null }
    }
    override fun initialized() { complete = db() != null && !failed }
    override val count: Long get() {
        val db = db() ?: return 0
        return try { db.compileStatement("SELECT COUNT(*) FROM spans").use { it.simpleQueryForLong() } }
        catch (_: Exception) { failed = true; 0 }
    }
    override fun add(span: CacheSpan) {
        val db = db() ?: return
        try {
            db.insertWithOnConflict("spans", null, ContentValues().apply {
                put("key", savedCatalogSortKey(span.key)); put("position", span.position); put("touch", span.lastTouchTimestamp)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Played order write failed" } }
        } catch (_: Exception) { failed = true }
    }
    override fun remove(span: CacheSpan) {
        val db = db() ?: return
        try { db.compileStatement("DELETE FROM spans WHERE key=? AND position=?").use {
            it.bindBlob(1, savedCatalogSortKey(span.key)); it.bindLong(2, span.position); it.executeUpdateDelete()
        } } catch (_: Exception) { failed = true }
    }
    override fun oldest(keep: String?, removable: (String) -> Boolean): String? {
        val db = db() ?: return null
        return try {
            // Close the cursor before the caller removes a resource and callbacks mutate this table.
            db.rawQuery("SELECT key FROM spans ORDER BY touch,key,position", null).use { cursor ->
                var answer: String? = null
                while (cursor.moveToNext()) {
                    val key = savedCatalogText(cursor.getBlob(0))
                    if (key != keep && removable(key)) { answer = key; break }
                }
                answer
            }
        } catch (_: Exception) { failed = true; null }
    }
    override fun forEachKey(emit: (String) -> Boolean) {
        // Absence in a partial/failed startup must never publish an incomplete display catalog.
        if (!complete || failed) throw java.io.IOException("Played span census is unavailable")
        val db = db() ?: throw java.io.IOException("Played span census is unavailable")
        db.rawQuery("SELECT DISTINCT key FROM spans ORDER BY key", null).use { cursor ->
            while (cursor.moveToNext()) if (!emit(savedCatalogText(cursor.getBlob(0)))) break
        }
        if (failed) throw java.io.IOException("Played span census changed")
    }

    override fun close() {
        failed = true
        try { database?.close() } finally { database = null }
    }
}
