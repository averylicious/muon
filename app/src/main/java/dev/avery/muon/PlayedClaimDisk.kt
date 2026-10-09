@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import androidx.media3.exoplayer.offline.DownloadIndex
import java.io.Closeable
import java.io.File

/** Derived exact protection names, never audio/index authority across a restart or failed rebuild. */
internal class PlayedClaimDisk(private val file: File) : Closeable {
    @Volatile var known = false
        private set
    private var database: SQLiteDatabase? = null
    private var lookup: android.database.sqlite.SQLiteStatement? = null

    /** One projected native row and one bound name at a time; all states and hidden owners participate. */
    @Synchronized fun read(index: DownloadIndex) {
        known = false
        try {
            val db = database ?: SQLiteDatabase.openOrCreateDatabase(file, null).also {
                database = it
                it.execSQL("PRAGMA cache_size=-256")
                it.execSQL("CREATE TABLE IF NOT EXISTS claims(name BLOB PRIMARY KEY NOT NULL)")
                lookup = it.compileStatement("SELECT EXISTS(SELECT 1 FROM claims WHERE name=?)")
            }
            db.beginTransaction()
            try {
                db.delete("claims", null, null)
                db.compileStatement("INSERT OR IGNORE INTO claims(name) VALUES (?)").use { insert ->
                    forEachIndexRow(index) { row ->
                        if (row.key.startsWith(PLAYED_PREFIX)) {
                            insert.bindBlob(1, savedCatalogSortKey(row.key))
                            try { insert.executeInsert() } finally { insert.clearBindings() }
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            known = true
        } catch (_: Exception) { known = false }
    }

    /** Eviction never proceeds when the census or this exact lookup cannot be trusted. */
    @Synchronized fun removable(key: String): Boolean {
        if (!known) return false
        val statement = lookup ?: return false
        return try {
            statement.bindBlob(1, savedCatalogSortKey(key))
            statement.simpleQueryForLong() == 0L
        } catch (_: Exception) { known = false; false }
        finally { runCatching { statement.clearBindings() }.onFailure { known = false } }
    }

    @Synchronized override fun close() {
        known = false
        try { lookup?.close() } finally { lookup = null; database?.close(); database = null }
    }
}
