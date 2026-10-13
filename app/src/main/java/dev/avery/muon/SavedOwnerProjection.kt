@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteStatement
import android.os.Looper
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import java.io.Closeable
import java.io.File

/**
 * Operation-scoped, disk-backed sole-owner census for saved projection (#253). Every index row,
 * including hidden/oversized/removing/failed rows, contributes its exact ID/key. Only one raw row
 * and its encoded names are projected at a time: no whole-index Java name/count maps. This is NOT
 * a deletion capability; removal still performs its existing fresh admission checks.
 *
 * The private no-backup database is separate from Media3 and the saved catalog. An uncommitted
 * transaction serializes concurrent users and is rolled back on close, so the next operation never
 * reuses old ownership. SQLite page cache is requested at256KiB; native row windows and one enormous
 * legacy name are not bounded by that setting. Disk/read/cancellation failures propagate before any
 * saved entry is emitted. No original request, metadata or audio is written.
 */
internal class SavedOwnerProjection private constructor(private val database: SQLiteDatabase,
    private val query: SQLiteStatement) : Closeable {
    private var closed = false

    fun soleOwner(download: Download): Boolean {
        requireWorker()
        check(!closed) { "Saved owner projection is closed" }
        val row = IndexRow.of(download)
        if (!row.ownKey || row.key != row.id || row.id.startsWith(PLAYED_PREFIX)) return false
        query.clearBindings()
        query.bindBlob(1, savedCatalogSortKey(row.id))
        return try { query.simpleQueryForLong() == 1L } catch (_: SQLiteDoneException) { false }
    }

    override fun close() {
        requireWorker()
        if (closed) return
        closed = true
        try { query.close() } finally {
            try { database.endTransaction() } finally { database.close() }
        }
    }

    companion object {
        fun open(context: Context, index: DownloadIndex, checkpoint: () -> Unit = {}): SavedOwnerProjection =
            openFrom(context, { emit -> forEachIndexRow(index, emit) }, checkpoint)

        /** Testable streaming boundary; callers must supply all states, not a displayed page. */
        internal fun openFrom(context: Context, read: ((IndexRow) -> Unit) -> Unit,
            checkpoint: () -> Unit = {}): SavedOwnerProjection {
            requireWorker()
            checkpoint()
            val directory = context.noBackupFilesDir
            check(directory.isDirectory || directory.mkdirs()) { "No private saved owner directory" }
            val database = SQLiteDatabase.openOrCreateDatabase(File(directory, "saved-projection-owners-v1.db"), null)
            try {
                database.execSQL("PRAGMA cache_size=-256")
                database.execSQL("CREATE TABLE IF NOT EXISTS names (name BLOB PRIMARY KEY NOT NULL, ids INTEGER NOT NULL, keys INTEGER NOT NULL)")
                database.beginTransaction()
                // Do not commit this scratch census: close/failure rolls it back to the empty table.
                database.delete("names", null, null)
                database.compileStatement("INSERT OR IGNORE INTO names(name,ids,keys) VALUES (?,0,0)").use { insert ->
                    database.compileStatement("UPDATE names SET ids=MIN(2,ids+?),keys=MIN(2,keys+?) WHERE name=?").use { update ->
                        fun add(name: String, id: Boolean) {
                            val encoded = savedCatalogSortKey(name)
                            insert.clearBindings(); insert.bindBlob(1, encoded); insert.executeInsert()
                            update.clearBindings(); update.bindLong(1, if (id) 1L else 0L)
                            update.bindLong(2, if (id) 0L else 1L); update.bindBlob(3, encoded)
                            update.executeUpdateDelete()
                        }
                        read { row -> checkpoint(); add(row.id, true); add(row.key, false) }
                    }
                }
                checkpoint()
                val query = database.compileStatement("SELECT CASE WHEN ids=1 AND keys=1 THEN 1 ELSE 0 END FROM names WHERE name=?")
                return SavedOwnerProjection(database, query)
            } catch (failure: Throwable) {
                try { if (database.inTransaction()) database.endTransaction() } finally { database.close() }
                throw failure
            }
        }

        private fun requireWorker() = check(Looper.myLooper() != Looper.getMainLooper()) {
            "Saved owner projection IO requires a worker"
        }
    }
}
