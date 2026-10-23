package dev.avery.muon

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteStatement
import android.os.Looper
import java.io.Closeable
import java.io.File

/** Operation-scoped disk locator/seen set, following SavedOwnerProjection's rollback-only pattern.
 * Seed catalog pages BEFORE entering a native owner projection: querying the catalog while holding
 * that owner could invert reload's catalog→owner lock order. Only one50-ref seed page and the
 * separately budgeted queue survive in Java. No metadata/audio or deletion authority is stored.
 * SQLite cache request256KiB is not a whole-process/CursorWindow/one-name heap guarantee. */
internal class SavedPlaybackProjection private constructor(private val database:SQLiteDatabase,
    private val find:SQLiteStatement,private val mark:SQLiteStatement,private val missing:SQLiteStatement,
    private val count:Long):Closeable {
    private val thread=Thread.currentThread()
    private var closed=false
    private fun live() {
        check(Thread.currentThread()===thread && Looper.myLooper()!=Looper.getMainLooper()) {"Saved playback projection requires its worker"}
        check(!closed) {"Saved playback projection is closed"}
    }
    fun includes(ref:SavedRef):Boolean {
        live();find.clearBindings();find.bindBlob(1,savedCatalogSortKey(ref.handle))
        return try {find.simpleQueryForLong();true} catch(_:SQLiteDoneException) {false}
    }
    fun observed(ref:SavedRef):Long {
        live();val key=savedCatalogSortKey(ref.handle)
        mark.clearBindings();mark.bindBlob(1,key)
        if(mark.executeUpdateDelete()!=1) throw SavedCatalogStale() // Unknown or duplicate emission.
        find.clearBindings();find.bindBlob(1,key)
        val order=try {find.simpleQueryForLong()} catch(_:SQLiteDoneException) {throw SavedCatalogStale()}
        if(order<0 || order>=count) throw SavedCatalogStale()
        return order
    }
    fun requireComplete() {live();if(missing.simpleQueryForLong()!=0L) throw SavedCatalogStale()}
    override fun close() {
        live();closed=true
        finish(database,listOf(find,mark,missing))
    }
    companion object {
        fun open(context:Context,catalog:SavedCatalog,expected:SavedCatalogSnapshot,checkpoint:()->Unit):SavedPlaybackProjection {
            check(Looper.myLooper()!=Looper.getMainLooper()) {"Saved playback projection IO requires a worker"}
            checkpoint()
            val dir=context.noBackupFilesDir
            check(dir.isDirectory || dir.mkdirs()) {"No private saved playback directory"}
            val database=SQLiteDatabase.openOrCreateDatabase(File(dir,"saved-playback-projection-v1.db"),null)
            val statements=ArrayList<SQLiteStatement>(3)
            try {
                database.execSQL("PRAGMA cache_size=-256")
                database.execSQL("CREATE TABLE IF NOT EXISTS refs(handle BLOB PRIMARY KEY NOT NULL,ordinal INTEGER NOT NULL,seen INTEGER NOT NULL)")
                database.beginTransaction()
                database.delete("refs",null,null)
                database.compileStatement("INSERT INTO refs(handle,ordinal,seen) VALUES (?,?,0)").use {insert ->
                    var offset=0L
                    while(offset<expected.count) {
                        checkpoint();val refs=catalog.page(expected,offset)
                        if(refs.isEmpty() || refs.size.toLong()>expected.count-offset) throw SavedCatalogStale()
                        for((index,ref) in refs.withIndex()) {
                            checkpoint();insert.clearBindings();insert.bindBlob(1,savedCatalogSortKey(ref.handle))
                            insert.bindLong(2,offset+index);insert.executeInsert()
                        }
                        offset+=refs.size
                    }
                }
                checkpoint();if(catalog.snapshot()!=expected) throw SavedCatalogStale()
                fun statement(sql:String)=database.compileStatement(sql).also {statements+=it}
                val find=statement("SELECT ordinal FROM refs WHERE handle=?")
                val mark=statement("UPDATE refs SET seen=1 WHERE handle=? AND seen=0")
                val missing=statement("SELECT COUNT(*) FROM refs WHERE seen=0")
                return SavedPlaybackProjection(database,find,mark,missing,expected.count)
            } catch(failure:Throwable) {
                try {finish(database,statements)} catch(close:Throwable) {if(close!==failure) failure.addSuppressed(close)}
                throw failure
            }
        }
        private fun finish(database:SQLiteDatabase,statements:List<SQLiteStatement>) {
            var failure:Throwable?=null
            fun close(action:()->Unit) {try {action()} catch(error:Throwable) {
                if(failure==null) failure=error else if(failure!==error) failure!!.addSuppressed(error)
            }}
            statements.forEach {statement ->close {statement.close()}}
            close {if(database.inTransaction()) database.endTransaction()} // Always roll back, never keep old seen rows.
            close {database.close()}
            failure?.let {throw it}
        }
    }
}
