package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** UI accounting only, rebuilt from native status events. Never audio/index mutation authority. */
internal class DownloadByteLedger(private val file: File) : Closeable {
    var known = true
        private set
    var total = 0L
        private set
    private var database: SQLiteDatabase? = null
    private fun db(): SQLiteDatabase? {
        if (!known) return null
        return database ?: try {
            val parent = requireNotNull(file.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "No private download tally directory" }
            SQLiteDatabase.openOrCreateDatabase(file,null).also { opened ->
                database = opened
                opened.execSQL("PRAGMA cache_size=-256")
                opened.execSQL("CREATE TABLE IF NOT EXISTS sizes(id BLOB PRIMARY KEY NOT NULL, bytes INTEGER NOT NULL)")
                opened.delete("sizes",null,null) // Previous process entries are not current accounting.
            }
        } catch (_: Exception) { known = false; null }
    }

    fun put(id: String, bytes: Long) = update(id,bytes)
    fun remove(id: String) = update(id,null)
    private fun update(id: String, bytes: Long?) {
        val db = db() ?: return
        try {
            var old = 0L
            db.beginTransaction()
            try {
                val key = savedCatalogSortKey(id)
                old = db.compileStatement("SELECT COALESCE((SELECT bytes FROM sizes WHERE id=?),0)").use {
                    it.bindBlob(1,key); it.simpleQueryForLong()
                }
                val sql = if (bytes == null) "DELETE FROM sizes WHERE id=?" else "INSERT OR REPLACE INTO sizes(id,bytes) VALUES(?,?)"
                db.compileStatement(sql).use {
                    it.bindBlob(1,key)
                    if (bytes == null) it.executeUpdateDelete() else { it.bindLong(2,bytes); it.executeInsert() }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            // Kotlin wrapping arithmetic matches the old JVM map sum; SQLite SUM can throw on overflow.
            total += (bytes ?: 0L) - old
        } catch (_: Exception) { known = false } // A failed transaction can never advertise an exact tally.
    }
    override fun close() {
        known = false
        try { database?.close() } finally { database = null }
    }
}
