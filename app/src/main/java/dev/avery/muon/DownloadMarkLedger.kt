package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** Derived display state only. Exact IDs stay on private disk, never in a whole-library UI map. */
internal class DownloadMarkLedger(private val file: File, private val onUnknown: () -> Unit = {}) : Closeable {
    @Volatile var known = true
        private set
    @Volatile var done = 0L
        private set
    private var database: SQLiteDatabase? = null
    private fun db(): SQLiteDatabase? {
        if (!known) return null
        return database ?: try {
            val parent = requireNotNull(file.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "No private status directory" }
            SQLiteDatabase.openOrCreateDatabase(file,null).also { opened ->
                database = opened
                opened.execSQL("PRAGMA cache_size=-256")
                opened.execSQL("CREATE TABLE IF NOT EXISTS marks(id BLOB PRIMARY KEY NOT NULL, mark INTEGER NOT NULL)")
                opened.delete("marks",null,null) // Previous process state cannot establish current badges/counts.
            }
        } catch (_: Exception) { unavailable(); null }
    }
    private fun lookup(db: SQLiteDatabase, key: ByteArray): DownloadMark? =
        db.compileStatement("SELECT COALESCE((SELECT mark FROM marks WHERE id=?),-1)").use {
            it.bindBlob(1,key)
            val value = it.simpleQueryForLong()
            if (value == -1L) null else DownloadMark.entries.first { mark -> mark.ordinal.toLong() == value }
        }

    /** Called off main only when hydrating the currently loaded saved page. */
    @Synchronized fun get(id: String): DownloadMark? {
        val db = db() ?: return null
        return try { lookup(db,savedCatalogSortKey(id)) }
        catch (_: Exception) { unavailable(); null }
    }

    /** Application-looper state events, one exact ID at a time; no byte-progress events. */
    @Synchronized fun put(id: String, mark: DownloadMark?) {
        val db = db() ?: return
        try {
            var prior: DownloadMark? = null
            db.beginTransaction()
            try {
                val key = savedCatalogSortKey(id)
                prior = lookup(db,key)
                val sql = if (mark == null) "DELETE FROM marks WHERE id=?" else "INSERT OR REPLACE INTO marks VALUES(?,?)"
                db.compileStatement(sql).use {
                    it.bindBlob(1,key)
                    if (mark == null) it.executeUpdateDelete()
                    else { it.bindLong(2,mark.ordinal.toLong()); check(it.executeInsert() != -1L) { "Status write failed" } }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            done += (if (mark == DownloadMark.Done) 1L else 0L) - (if (prior == DownloadMark.Done) 1L else 0L)
        } catch (_: Exception) { unavailable() }
    }
    private fun unavailable() {
        if (!known) return
        known = false
        // At most one UI notification for a poisoned instance, including worker-only read failures.
        // Notification failure cannot turn an unreadable ledger into trusted state or fail playback.
        runCatching(onUnknown)
    }
    @Synchronized override fun close() {
        unavailable()
        try { database?.close() } finally { database = null }
    }
}
