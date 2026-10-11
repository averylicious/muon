@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import java.io.Closeable
import java.io.File
import java.util.UUID

/** Complete operation-owned evidence on private scratch disk, never the download database/schema. */
internal class DiskOwnershipCensus private constructor(private val file: File,
    private val db: SQLiteDatabase) : OwnershipCensus, Closeable {
    private val idCount = db.compileStatement("SELECT COUNT(*) FROM (SELECT 1 FROM rows WHERE id=? LIMIT 2)")
    private val keyCount = db.compileStatement("SELECT COUNT(*) FROM (SELECT 1 FROM rows WHERE key=? LIMIT 2)")
    private val first = db.compileStatement("SELECT rowid FROM rows WHERE id=? ORDER BY rowid LIMIT 1")
    private var closed = false
    private fun number(statement: SQLiteStatement, name: String): Long {
        check(!closed)
        statement.bindBlob(1, savedCatalogSortKey(name))
        return try { statement.simpleQueryForLong() } finally { statement.clearBindings() }
    }
    private fun row(id: String): IndexRow? {
        if (number(idCount, id) != 1L) return null
        val position = number(first, id)
        return db.rawQuery("SELECT id,key,own,state FROM rows WHERE rowid=?", arrayOf(position.toString())).use {
            if (it.moveToFirst()) IndexRow(text(it.getBlob(0)), text(it.getBlob(1)), it.getInt(2) != 0, it.getInt(3)) else null
        }
    }
    override fun soleOwner(id: String): Boolean = row(id)?.let {
        it.ownKey && it.id == it.key && !it.id.startsWith(PLAYED_PREFIX) && number(keyCount, it.key) == 1L
    } == true
    override fun names(name: String): Boolean = number(idCount, name) > 0 || number(keyCount, name) > 0
    override fun onlyNaming(name: String): IndexRow? = row(name)?.takeIf { it.key == name && number(keyCount, name) == 1L }
    fun countCompleted(): Int = db.compileStatement("SELECT COUNT(*) FROM rows WHERE state=${Download.STATE_COMPLETED}")
        .use { Math.toIntExact(it.simpleQueryForLong()) }

    /** Cursor is always closed, including an early stop; no whole completed-ID/filter/map list. */
    fun forEachRow(completedOnly: Boolean = false, action: (IndexRow) -> Boolean) {
        check(!closed)
        val where = if (completedOnly) " WHERE state=${Download.STATE_COMPLETED}" else ""
        db.rawQuery("SELECT id,key,own,state FROM rows$where ORDER BY rowid", null).use { cursor ->
            while (cursor.moveToNext()) {
                val row = IndexRow(text(cursor.getBlob(0)), text(cursor.getBlob(1)), cursor.getInt(2) != 0, cursor.getInt(3))
                if (!action(row)) break
            }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        try { idCount.close(); keyCount.close(); first.close() } finally {
            try { db.close() } finally {
                SQLiteDatabase.deleteDatabase(file)
            }
        }
    }
    companion object {
        fun read(context: Context, index: DownloadIndex): DiskOwnershipCensus {
            val directory = File(context.noBackupFilesDir, "ownership-scratch")
            check(directory.isDirectory || directory.mkdirs()) { "No private ownership directory" }
            val file = File(directory, "census-${UUID.randomUUID()}.db")
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                db.execSQL("PRAGMA cache_size=-256")
                db.execSQL("CREATE TABLE rows(id BLOB NOT NULL,key BLOB NOT NULL,own INTEGER NOT NULL,state INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX rows_id ON rows(id)")
                db.execSQL("CREATE INDEX rows_key ON rows(key)")
                db.beginTransaction()
                try {
                    db.compileStatement("INSERT INTO rows(id,key,own,state) VALUES (?,?,?,?)").use { insert ->
                        forEachIndexRow(index) { row ->
                            insert.bindBlob(1, savedCatalogSortKey(row.id)); insert.bindBlob(2, savedCatalogSortKey(row.key))
                            insert.bindLong(3, if (row.ownKey) 1 else 0); insert.bindLong(4, row.state.toLong())
                            try { insert.executeInsert() } finally { insert.clearBindings() }
                        }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                return DiskOwnershipCensus(file, db)
            } catch (failure: Throwable) {
                try { db.close() } finally { SQLiteDatabase.deleteDatabase(file) }
                throw failure
            }
        }
        private fun text(bytes: ByteArray): String {
            require(bytes.size % 2 == 0)
            return CharArray(bytes.size / 2) { i ->
                (((bytes[2 * i].toInt() and 255) shl 8) or (bytes[2 * i + 1].toInt() and 255)).toChar()
            }.concatToString()
        }
    }
}
