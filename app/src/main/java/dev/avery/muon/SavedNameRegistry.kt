package dev.avery.muon

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.Looper
import java.io.Closeable
import java.io.File

/**
 * Exact operation-scoped name reservations for a new save, on private disk instead of a full Java
 * HashSet (#253). IDs/keys from all states/shelves must be added before any request can be sent.
 * UTF-16 BLOBs preserve NUL/unpaired surrogates and match String equality; never clipped/normalized.
 * Close rolls the census/reservations back, so no stale generation becomes naming authority.
 * The requested256KiB SQLite page cache is not a total native/process heap bound. One original
 * name/encoding and Media3's getKeys snapshot still exist; the native cache lifetime is separate.
 */
internal class SavedNameRegistry private constructor(private val database: SQLiteDatabase,
    private val insert: SQLiteStatement, private val exists: SQLiteStatement) : Closeable {
    private var closed = false

    fun add(name: String) {
        requireWorker(); check(!closed)
        insert.clearBindings()
        insert.bindBlob(1, savedCatalogSortKey(name))
        insert.executeInsert()
        insert.clearBindings() // Do not retain the last oversized original name between probes.
    }

    operator fun contains(name: String): Boolean {
        requireWorker(); check(!closed)
        exists.clearBindings()
        exists.bindBlob(1, savedCatalogSortKey(name))
        return try { exists.simpleQueryForLong() != 0L } finally { exists.clearBindings() }
    }

    override fun close() {
        requireWorker()
        if (closed) return
        closed = true
        try { insert.close() } finally {
            try { exists.close() } finally {
                try { database.endTransaction() } finally { database.close() }
            }
        }
    }

    companion object {
        fun open(context: Context): SavedNameRegistry {
            requireWorker()
            val directory = context.noBackupFilesDir
            check(directory.isDirectory || directory.mkdirs()) { "No private saved naming directory" }
            val database = SQLiteDatabase.openOrCreateDatabase(File(directory, "saved-save-names-v1.db"), null)
            var insert: SQLiteStatement? = null
            var exists: SQLiteStatement? = null
            try {
                database.execSQL("PRAGMA cache_size=-256")
                database.execSQL("CREATE TABLE IF NOT EXISTS names (name BLOB PRIMARY KEY NOT NULL)")
                database.beginTransaction()
                database.delete("names", null, null)
                insert = database.compileStatement("INSERT OR IGNORE INTO names(name) VALUES (?)")
                exists = database.compileStatement("SELECT EXISTS(SELECT 1 FROM names WHERE name=?)")
                return SavedNameRegistry(database, insert, exists)
            } catch (failure: Throwable) {
                try { insert?.close() } finally {
                    try { exists?.close() } finally {
                        try { if (database.inTransaction()) database.endTransaction() } finally { database.close() }
                    }
                }
                throw failure
            }
        }

        private fun requireWorker() = check(Looper.myLooper() != Looper.getMainLooper()) {
            "Saved naming IO requires a worker"
        }
    }
}
