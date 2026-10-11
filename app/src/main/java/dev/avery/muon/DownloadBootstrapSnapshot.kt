@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.media3.exoplayer.offline.DownloadIndex
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID

/** Complete derived status spool plus exact live-event tombstones; never native row/audio authority. */
internal class DownloadBootstrapSnapshot(context: Context) : Closeable {
    private val prefix = File(context.noBackupFilesDir,"download-bootstrap-${UUID.randomUUID()}")
    private val recordsFile = File("${prefix.path}-records.db")
    private val changedFile = File("${prefix.path}-changed.db")
    private var records: SQLiteDatabase? = null // Worker writes, then main reads after ready publication.
    private var changes: SQLiteDatabase? = null // Main looper only; separate DB from worker transaction/IO.
    @Volatile private var valid = true
    @Volatile private var ready = false

    /** One raw index row at a time. Even a cursor-close failure refuses the entire snapshot. */
    fun read(index: DownloadIndex) {
        try {
            val db = open(recordsFile).also { records = it }
            db.execSQL("CREATE TABLE statuses(ordinal INTEGER PRIMARY KEY, id BLOB NOT NULL, state INTEGER NOT NULL, bytes INTEGER NOT NULL)")
            var ordinal = 0L
            db.compileStatement("INSERT INTO statuses VALUES(?,?,?,?)").use { insert ->
                index.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        if (!valid) throw IOException("Download bootstrap invalidated")
                        val status = DownloadStatus.of(cursor.download)
                        insert.bindLong(1,ordinal); insert.bindBlob(2,savedCatalogSortKey(status.id))
                        insert.bindLong(3,status.state.toLong()); insert.bindLong(4,status.bytesDownloaded)
                        insert.executeInsert(); insert.clearBindings()
                        if (ordinal == Long.MAX_VALUE) throw IOException("Download bootstrap count exhausted")
                        ordinal++
                    }
                }
            }
            ready = valid
        } catch (_: Exception) { valid = false; ready = false }
    }

    /** Main-only. No full ID set; an unreadable tombstone invalidates every pending snapshot row. */
    fun changed(id: String) {
        if (!valid) return
        try {
            val db = changes ?: open(changedFile).also {
                changes = it; it.execSQL("CREATE TABLE changed(id BLOB PRIMARY KEY NOT NULL)")
            }
            db.compileStatement("INSERT OR IGNORE INTO changed VALUES(?)").use {
                it.bindBlob(1,savedCatalogSortKey(id)); it.executeInsert()
            }
        } catch (_: Exception) { valid = false }
    }

    /** Main-only. At most sixteen statuses await publication, never a library-sized Runnable payload. */
    fun page(offset: Long): List<DownloadStatus> {
        require(offset >= 0)
        if (!valid || !ready) throw IOException("Download bootstrap unavailable")
        val db = records ?: throw IOException("Download bootstrap unavailable")
        return db.rawQuery("SELECT id,state,bytes FROM statuses WHERE ordinal>=? ORDER BY ordinal LIMIT 16",
            arrayOf(offset.toString())).use { cursor ->
            val batch = ArrayList<DownloadStatus>(16)
            while (cursor.moveToNext()) batch += DownloadStatus(savedCatalogText(cursor.getBlob(0)),cursor.getInt(1),cursor.getLong(2))
            batch
        }
    }

    fun unchanged(id: String): Boolean {
        if (!valid || !ready) return false
        val db = changes ?: return true
        return try { db.compileStatement("SELECT EXISTS(SELECT 1 FROM changed WHERE id=?)").use {
            it.bindBlob(1,savedCatalogSortKey(id)); it.simpleQueryForLong() == 0L
        } } catch (_: Exception) { valid = false; false }
    }

    override fun close() {
        valid = false; ready = false
        runCatching { records?.close() }; records = null
        runCatching { changes?.close() }; changes = null
        for (file in listOf(recordsFile,changedFile)) {
            runCatching { SQLiteDatabase.deleteDatabase(file) }
        }
    }

    private fun open(file: File): SQLiteDatabase {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "No private bootstrap directory" }
        return SQLiteDatabase.openOrCreateDatabase(file,null).also { it.execSQL("PRAGMA cache_size=-256") }
    }
}
