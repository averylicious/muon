package dev.avery.muon

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.nio.file.Files
import android.database.Cursor

internal const val PARTITION_LOCATOR_WINDOW=16
internal data class CachePartitionAllocation(val key:String, val directory:String)

/** Persistent exact reservations for opt-in migration; no readiness/publish/delete authority. */
internal class CachePartitionCatalog(private val root:File, private val create:Boolean=true,
    private val io:MigrationIoOwnership=MigrationIoOwnership(),private val autoOpen:Boolean=true,
    private val openDatabase:(File,Boolean)->SQLiteDatabase={file,fresh ->
        if(fresh) SQLiteDatabase.openOrCreateDatabase(file,null)
        else SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)},
    private val closeDatabase:(SQLiteDatabase)->Unit={it.close()},
    private val finishTransaction:(SQLiteDatabase)->Unit={it.endTransaction()},
    private val newDirectory:()->String={UUID.randomUUID().toString()}) : Closeable {
    private val schema="CREATE TABLE partitions(key BLOB PRIMARY KEY NOT NULL, directory TEXT UNIQUE NOT NULL)"
    private val resources=File(root,"resources")
    /** Disposable ordering observations, outside native byte folders and authoritative journals. */
    internal val playedOrderFile:File get()=File(root,"played-resize-order-v1.db")
    // Bound payload BEFORE CursorWindow projection. TEXT length alone stops at embedded NUL.
    private val boundedDirectory="CASE WHEN typeof(directory)='text' AND length(CAST(directory AS BLOB))=36 THEN directory ELSE NULL END"
    private val boundedKey="CASE WHEN typeof(key)='blob' AND length(key)<=$MIGRATION_KEY_BYTES THEN key ELSE NULL END"
    private enum class Phase { New,Opening,Open,Unsupported,Closing,Uncertain,Closed }
    private var phase=Phase.New
    private var database:SQLiteDatabase?=null
    @get:Synchronized val ownershipUncertain:Boolean get()=phase==Phase.Uncertain
    private fun unknown(failure:Throwable):Nothing {
        phase=Phase.Uncertain
        throw PartitionOwnershipUncertain(this,failure)
    }
    private fun db():SQLiteDatabase {
        if(phase!=Phase.Open) throw IOException("Partition locator journal is not open or its ownership is uncertain")
        if(!io.quiescent) unknown(MigrationIoUncertain(io,null))
        return requireNotNull(database)
    }
    private fun <R:Closeable,T> useOwned(create:()->R,read:(R)->T):T {
        try {
            return io.useOwned(create,read).also {
                if(phase==Phase.Uncertain) unknown(IOException("Partition locator journal became uncertain during I/O"))
            }
        } catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || phase==Phase.Uncertain) unknown(failure)
            throw failure
        }
    }
    private fun begin(db:SQLiteDatabase) {
        try {db.beginTransaction()} catch(failure:Throwable) {unknown(failure)}
    }
    private fun successful(db:SQLiteDatabase) {
        try {db.setTransactionSuccessful()} catch(failure:Throwable) {unknown(failure)}
    }
    private fun end(db:SQLiteDatabase) {
        // Never roll back/release a database over an actual statement whose close is unknown.
        if(phase==Phase.Uncertain || !io.quiescent) unknown(IOException("Partition locator transaction still owns uncertain I/O"))
        try {finishTransaction(db)} catch(failure:Throwable) {unknown(failure)}
    }
    init {if(autoOpen) open()}
    /** Production registers this harmless owner before SQLite opens. */
    @Synchronized fun open() {
        if(phase==Phase.Open) return
        if(phase!=Phase.New) throw IOException("Partition locator startup cannot be retried")
        phase=Phase.Opening
        try {
            if(create && !root.exists() && !root.mkdirs()) throw IOException("No private partition directory")
            if(!root.isDirectory || root.absoluteFile!=root.canonicalFile || Files.isSymbolicLink(root.toPath()))
                throw IOException("Partition journal directory differs")
            val file=File(root,"partition-locators-v1.db")
            if(file.exists() && (!file.isFile || file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath())))
                throw IOException("Partition journal file differs")
            if(!create && !file.isFile) throw IOException("Partition journal missing; resume cannot initialize it")
            val opened=try {openDatabase(file,create)} catch(failure:Throwable) {unknown(failure)}
            database=opened
            opened.execSQL("PRAGMA cache_size=-256");opened.execSQL("PRAGMA synchronous=FULL")
            begin(opened)
            try {
                when(opened.version) {
                    0 -> {
                        if(!create) throw IOException("Partition journal is uninitialized; resume cannot replace it")
                        val existing=useOwned({opened.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND name!='android_metadata'",null)}) {
                            if(!it.moveToFirst()) throw IOException("Partition schema unreadable")
                            it.getLong(0)
                        }
                        if(existing!=0L) throw IOException("Unrecognized partition schema")
                        opened.execSQL(schema);opened.version=1
                    }
                    1 -> useOwned({opened.rawQuery("SELECT CASE WHEN type='table' AND name='partitions' AND length(CAST(sql AS BLOB))<=1024 THEN sql ELSE NULL END FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND NOT (type='table' AND name='android_metadata') LIMIT 2",null)}) {
                        if(!it.moveToFirst() || it.getType(0)!=Cursor.FIELD_TYPE_STRING || it.getString(0)!=schema || it.moveToNext())
                            throw IOException("Unrecognized partition journal schema")
                    }
                    else -> throw IOException("Unsupported partition schema")
                }
                successful(opened)
            } finally {end(opened)}
            phase=Phase.Open
        } catch(failure:Throwable) {
            if(phase==Phase.Uncertain || !io.quiescent) unknown(failure)
            database?.let {actual ->try {closeDatabase(actual);database=null} catch(close:Throwable) {unknown(close)}}
            phase=Phase.Unsupported;throw failure
        }
    }
    private fun requireKey(key:String) {
        if (key.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Partition key exceeds migration budget")
    }
    private fun allocation(key:String,directory:String):CachePartitionAllocation {
        requireKey(key)
        val parsed=try { UUID.fromString(directory) } catch (_:IllegalArgumentException) { throw IOException("Partition locator malformed") }
        if (parsed.toString()!=directory) throw IOException("Partition locator not canonical")
        return CachePartitionAllocation(key,directory)
    }
    private fun lookup(key:String):CachePartitionAllocation? {
        val db=db()
        return useOwned({db.rawQueryWithFactory({_,driver,table,query ->
            query.bindBlob(1,savedCatalogSortKey(key));SQLiteCursor(driver,table,query)
        },"SELECT $boundedDirectory FROM partitions WHERE key=?",null,"partitions")}) {cursor ->
            if(!cursor.moveToFirst()) null else {
                if(cursor.getType(0)!=Cursor.FIELD_TYPE_STRING) throw IOException("Partition locator invalid")
                allocation(key,cursor.getString(0))
            }
        }
    }
    @Synchronized fun find(key:String):CachePartitionAllocation? { db();requireKey(key); return lookup(key) }

    /** Persist before a caller creates its fresh native folder. Existing reservations survive restart. */
    @Synchronized fun reserve(key:String):CachePartitionAllocation {
        val db=db()
        requireKey(key)
        begin(db)
        try {
            val existing=lookup(key)
            if (existing!=null) return existing.also { successful(db) }
            val candidate=allocation(key,newDirectory())
            // An existing unknown folder is not ours merely because its UUID matches. Refuse rather
            // than truncate/adopt/delete it; SQL uniqueness also refuses another resource's directory.
            if (directory(candidate).exists()) throw IOException("Partition directory already exists")
            useOwned({db.compileStatement("INSERT INTO partitions VALUES(?,?)")}) {
                it.bindBlob(1,savedCatalogSortKey(key)); it.bindString(2,candidate.directory)
                check(it.executeInsert()!=-1L) { "Partition reservation failed" }
            }
            successful(db)
            return candidate
        } finally { end(db) }
    }
    /** New replacement reservation. Old/uncertain directories remain untouched; ready routing must
     * use the migration journal, never a reservation alone. Concurrent attempts need journal CAS. */
    @Synchronized fun reserveFresh(key:String):CachePartitionAllocation {
        val db=db()
        requireKey(key)
        begin(db)
        try {
            val existing=lookup(key)
            val candidate=allocation(key,newDirectory())
            if (candidate==existing || directory(candidate).exists()) throw IOException("Replacement directory is not fresh")
            if (existing==null) {
                useOwned({db.compileStatement("INSERT INTO partitions VALUES(?,?)")}) {
                    it.bindBlob(1,savedCatalogSortKey(key)); it.bindString(2,candidate.directory)
                    check(it.executeInsert()!=-1L) { "Replacement reservation failed" }
                }
            } else {
                useOwned({db.compileStatement("UPDATE partitions SET directory=? WHERE key=?")}) {
                    it.bindString(1,candidate.directory); it.bindBlob(2,savedCatalogSortKey(key))
                    check(it.executeUpdateDelete()==1) { "Replacement reservation changed" }
                }
            }
            successful(db)
            return candidate
        } finally { end(db) }
    }
    /** Path validation alone is not ownership or permission to modify a directory. */
    fun directory(candidate:CachePartitionAllocation):File {
        allocation(candidate.key,candidate.directory)
        val parent=resources.canonicalFile
        val target=File(resources,candidate.directory).canonicalFile
        if (parent.parentFile!=root.canonicalFile || parent.name!="resources" ||
            target.parentFile!=parent || target.name!=candidate.directory)
            throw IOException("Partition directory leaves its private identity/root")
        return target
    }
    @Synchronized fun count():Long {
        val db=db()
        return useOwned({db.rawQuery("SELECT COUNT(*) FROM partitions",null)}) {
            if(!it.moveToFirst()) throw IOException("Partition count unavailable")
            it.getLong(0)
        }
    }
    /** Bounded exact UTF-16 keyset page; reservations inserted later need a fresh scan, not a snapshot claim. */
    @Synchronized fun page(after:String?=null):List<CachePartitionAllocation> {
        val db=db()
        after?.let(::requireKey)
        val where=if (after==null) "" else " WHERE key>?"
        return useOwned({db.rawQueryWithFactory({ _,driver,table,query ->
            after?.let { query.bindBlob(1,savedCatalogSortKey(it)) }; SQLiteCursor(driver,table,query)
        },"SELECT $boundedKey,$boundedDirectory FROM partitions$where ORDER BY key LIMIT $PARTITION_LOCATOR_WINDOW",null,"partitions")}) { cursor ->
            val rows=ArrayList<CachePartitionAllocation>(PARTITION_LOCATOR_WINDOW)
            while (cursor.moveToNext()) {
                if(cursor.getType(0)!=android.database.Cursor.FIELD_TYPE_BLOB || cursor.getType(1)!=android.database.Cursor.FIELD_TYPE_STRING)
                    throw IOException("Partition page identity invalid")
                rows+=allocation(savedCatalogText(cursor.getBlob(0)),cursor.getString(1))
            }
            rows
        }
    }
    @Synchronized override fun close() {
        if(phase==Phase.Closed || phase==Phase.Unsupported) return
        if(phase==Phase.New) {phase=Phase.Closed;return}
        val actual=db();phase=Phase.Closing
        try {closeDatabase(actual);database=null;phase=Phase.Closed}
        catch(failure:Throwable) {unknown(failure)}
    }
}
