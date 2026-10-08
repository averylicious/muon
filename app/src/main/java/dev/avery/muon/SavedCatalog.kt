package dev.avery.muon

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

internal const val SAVED_PAGE_SIZE = 50
internal data class SavedCatalogSnapshot(val generation: Long, val count: Long)
internal class SavedCatalogStale : IOException("Saved copies changed; refresh this page")

/**
 * Derived ordering/locator catalog for #253 paging; original Media3 rows/audio are never changed.
 * Stored in app-private no-backup storage, not Media3's schema. Streams one entry at a time into an
 * atomic replacement, then reads at most one bounded locator page. It stores neither SavedEntry nor
 * raw tags in memory across calls. UI/model hydration and invalidation are a separate integration.
 */
internal class SavedCatalog private constructor(private val database: SQLiteDatabase) : Closeable {
    companion object {
        fun open(context: Context): SavedCatalog {
            requireWorker()
            val directory = context.noBackupFilesDir
            check(directory.isDirectory || directory.mkdirs()) { "No private saved catalog directory" }
            val database = SQLiteDatabase.openOrCreateDatabase(File(directory, "saved-catalog-v1.db"), null)
            try {
                database.beginTransaction()
                try {
                    if (database.version == 0) {
                        database.execSQL("CREATE TABLE copies(handle TEXT PRIMARY KEY NOT NULL, unknown_title INTEGER NOT NULL, " +
                            "sort_title BLOB NOT NULL, sort_handle BLOB NOT NULL)")
                        database.execSQL("CREATE INDEX reading_order ON copies(unknown_title,sort_title,sort_handle)")
                        database.execSQL("CREATE TABLE catalog_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1), " +
                            "generation INTEGER NOT NULL, entry_count INTEGER NOT NULL)")
                        database.execSQL("INSERT INTO catalog_state VALUES(1,0,0)")
                        database.version = 1
                    } else if (database.version != 1) throw IOException("Unsupported derived saved catalog version")
                    database.setTransactionSuccessful()
                } finally { database.endTransaction() }
                return SavedCatalog(database)
            } catch (failure: Throwable) {
                database.close()
                throw failure
            }
        }

        private fun requireWorker() = check(Looper.myLooper() != Looper.getMainLooper()) {
            "Saved catalog IO requires a worker"
        }
    }

    @Synchronized fun snapshot(): SavedCatalogSnapshot {
        requireWorker()
        return state()
    }

    private fun state(): SavedCatalogSnapshot = database.rawQuery(
        "SELECT generation,entry_count FROM catalog_state WHERE singleton=1", null).use { cursor ->
        if (!cursor.moveToFirst()) throw IOException("Missing saved catalog state")
        SavedCatalogSnapshot(cursor.getLong(0), cursor.getLong(1))
    }

    /** Failed/cancelled enumeration leaves the previous complete generation visible, not a prefix. */
    @Synchronized fun rebuild(entries: Iterable<SavedEntry>, cancelled: () -> Boolean = { false }): SavedCatalogSnapshot {
        requireWorker()
        database.beginTransaction()
        try {
            val old = state()
            if (old.generation == Long.MAX_VALUE) throw IOException("Saved catalog generation exhausted")
            database.delete("copies", null, null)
            var count = 0L
            for (entry in entries) {
                if (cancelled()) throw CancellationException("Saved catalog rebuild cancelled")
                val song = entry.displaySong
                val values = ContentValues().apply {
                    put("handle", entry.ref.handle)
                    put("unknown_title", if (song == null) 1 else 0)
                    put("sort_title", savedCatalogSortKey(song?.title?.lowercase().orEmpty()))
                    put("sort_handle", savedCatalogSortKey(entry.ref.handle))
                }
                database.insertOrThrow("copies", null, values) // Duplicate locators fail, never silently vanish.
                if (count == Long.MAX_VALUE) throw IOException("Saved catalog count exhausted")
                count++
            }
            if (cancelled()) throw CancellationException("Saved catalog rebuild cancelled")
            val next = SavedCatalogSnapshot(old.generation + 1, count)
            database.execSQL("UPDATE catalog_state SET generation=?,entry_count=? WHERE singleton=1",
                arrayOf<Any>(next.generation, next.count))
            database.setTransactionSuccessful()
            return next
        } finally { database.endTransaction() }
    }

    /** Locators only; caller hydrates current original rows and rechecks destructive ownership. */
    @Synchronized fun page(expected: SavedCatalogSnapshot, offset: Long, limit: Int = SAVED_PAGE_SIZE): List<SavedRef> {
        requireWorker()
        require(offset >= 0 && limit in 1..SAVED_PAGE_SIZE)
        if (state() != expected) throw SavedCatalogStale()
        return database.rawQuery("SELECT handle FROM copies ORDER BY unknown_title,sort_title,sort_handle LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString())).use { cursor ->
            val refs = ArrayList<SavedRef>(limit)
            while (cursor.moveToNext()) refs += SavedRef.parse(cursor.getString(0))
                ?: throw IOException("Invalid derived saved locator")
            refs
        }
    }

    @Synchronized override fun close() {
        requireWorker()
        database.close()
    }
}

/**
 * SQLite BLOB ordering must match Kotlin String's UTF-16 code-unit comparison, NOT Unicode scalar
 * or UTF-8 TEXT order. Encode code units manually to preserve supplementary and unpaired surrogates.
 * No prefix clipping; lowercase/title selection above exactly follows sortSaved.
 */
internal fun savedCatalogSortKey(text: String): ByteArray {
    val bytes = ByteArray(Math.multiplyExact(text.length, 2))
    for (i in text.indices) {
        val unit = text[i].code
        bytes[2 * i] = (unit ushr 8).toByte()
        bytes[2 * i + 1] = unit.toByte()
    }
    return bytes
}
