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
internal class CachePartitionCatalog(private val root:File, create:Boolean=true, private val newDirectory:()->String={ UUID.randomUUID().toString() }) : Closeable {
    private val schema="CREATE TABLE partitions(key BLOB PRIMARY KEY NOT NULL, directory TEXT UNIQUE NOT NULL)"
    private val resources=File(root,"resources")
    // Bound payload BEFORE CursorWindow projection. TEXT length alone stops at embedded NUL.
    private val boundedDirectory="CASE WHEN typeof(directory)='text' AND length(CAST(directory AS BLOB))=36 THEN directory ELSE NULL END"
    private val boundedKey="CASE WHEN typeof(key)='blob' AND length(key)<=$MIGRATION_KEY_BYTES THEN key ELSE NULL END"
    private val database:SQLiteDatabase
    init {
        if(create && !root.exists() && !root.mkdirs()) throw IOException("No private partition directory")
        if(!root.isDirectory || root.absoluteFile!=root.canonicalFile || Files.isSymbolicLink(root.toPath()))
            throw IOException("Partition journal directory differs")
        val file=File(root,"partition-locators-v1.db")
        if(file.exists() && (!file.isFile || file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath())))
            throw IOException("Partition journal file differs")
        if(!create && !file.isFile) throw IOException("Partition journal missing; resume cannot initialize it")
        database=if(create) SQLiteDatabase.openOrCreateDatabase(file,null)
            else SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            database.execSQL("PRAGMA cache_size=-256")
            database.beginTransaction()
            try {
                when (database.version) {
                    0 -> {
                        if(!create) throw IOException("Partition journal is uninitialized; resume cannot replace it")
                        val existing=database.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND name!='android_metadata'",null).use {
                            if (!it.moveToFirst()) throw IOException("Partition schema unreadable")
                            it.getLong(0)
                        }
                        if (existing!=0L) throw IOException("Unrecognized partition schema")
                        database.execSQL(schema)
                        database.version=1
                    }
                    1 -> database.rawQuery("SELECT CASE WHEN type='table' AND name='partitions' AND length(CAST(sql AS BLOB))<=1024 THEN sql ELSE NULL END FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND NOT (type='table' AND name='android_metadata') LIMIT 2",null).use {
                        if(!it.moveToFirst() || it.getType(0)!=Cursor.FIELD_TYPE_STRING || it.getString(0)!=schema || it.moveToNext())
                            throw IOException("Unrecognized partition journal schema")
                    }
                    else -> throw IOException("Unsupported partition schema")
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        } catch (failure:Throwable) { database.close(); throw failure }
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
    private fun lookup(key:String):CachePartitionAllocation? = database.rawQueryWithFactory({ _,driver,table,query ->
        query.bindBlob(1,savedCatalogSortKey(key)); SQLiteCursor(driver,table,query)
    },"SELECT $boundedDirectory FROM partitions WHERE key=?",null,"partitions").use { cursor ->
        if (!cursor.moveToFirst()) null else {
            if(cursor.getType(0)!=android.database.Cursor.FIELD_TYPE_STRING) throw IOException("Partition locator invalid")
            allocation(key,cursor.getString(0))
        }
    }
    @Synchronized fun find(key:String):CachePartitionAllocation? { requireKey(key); return lookup(key) }

    /** Persist before a caller creates its fresh native folder. Existing reservations survive restart. */
    @Synchronized fun reserve(key:String):CachePartitionAllocation {
        requireKey(key)
        database.beginTransaction()
        try {
            val existing=lookup(key)
            if (existing!=null) return existing.also { database.setTransactionSuccessful() }
            val candidate=allocation(key,newDirectory())
            // An existing unknown folder is not ours merely because its UUID matches. Refuse rather
            // than truncate/adopt/delete it; SQL uniqueness also refuses another resource's directory.
            if (directory(candidate).exists()) throw IOException("Partition directory already exists")
            database.compileStatement("INSERT INTO partitions VALUES(?,?)").use {
                it.bindBlob(1,savedCatalogSortKey(key)); it.bindString(2,candidate.directory)
                check(it.executeInsert()!=-1L) { "Partition reservation failed" }
            }
            database.setTransactionSuccessful()
            return candidate
        } finally { database.endTransaction() }
    }
    /** New replacement reservation. Old/uncertain directories remain untouched; ready routing must
     * use the migration journal, never a reservation alone. Concurrent attempts need journal CAS. */
    @Synchronized fun reserveFresh(key:String):CachePartitionAllocation {
        requireKey(key)
        database.beginTransaction()
        try {
            val existing=lookup(key)
            val candidate=allocation(key,newDirectory())
            if (candidate==existing || directory(candidate).exists()) throw IOException("Replacement directory is not fresh")
            if (existing==null) {
                database.compileStatement("INSERT INTO partitions VALUES(?,?)").use {
                    it.bindBlob(1,savedCatalogSortKey(key)); it.bindString(2,candidate.directory)
                    check(it.executeInsert()!=-1L) { "Replacement reservation failed" }
                }
            } else {
                database.compileStatement("UPDATE partitions SET directory=? WHERE key=?").use {
                    it.bindString(1,candidate.directory); it.bindBlob(2,savedCatalogSortKey(key))
                    check(it.executeUpdateDelete()==1) { "Replacement reservation changed" }
                }
            }
            database.setTransactionSuccessful()
            return candidate
        } finally { database.endTransaction() }
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
    @Synchronized fun count():Long = database.rawQuery("SELECT COUNT(*) FROM partitions",null).use {
        if (!it.moveToFirst()) throw IOException("Partition count unavailable")
        it.getLong(0)
    }
    /** Bounded exact UTF-16 keyset page; reservations inserted later need a fresh scan, not a snapshot claim. */
    @Synchronized fun page(after:String?=null):List<CachePartitionAllocation> {
        after?.let(::requireKey)
        val where=if (after==null) "" else " WHERE key>?"
        return database.rawQueryWithFactory({ _,driver,table,query ->
            after?.let { query.bindBlob(1,savedCatalogSortKey(it)) }; SQLiteCursor(driver,table,query)
        },"SELECT $boundedKey,$boundedDirectory FROM partitions$where ORDER BY key LIMIT $PARTITION_LOCATOR_WINDOW",null,"partitions").use { cursor ->
            val rows=ArrayList<CachePartitionAllocation>(PARTITION_LOCATOR_WINDOW)
            while (cursor.moveToNext()) {
                if(cursor.getType(0)!=android.database.Cursor.FIELD_TYPE_BLOB || cursor.getType(1)!=android.database.Cursor.FIELD_TYPE_STRING)
                    throw IOException("Partition page identity invalid")
                rows+=allocation(savedCatalogText(cursor.getBlob(0)),cursor.getString(1))
            }
            rows
        }
    }
    @Synchronized override fun close()=database.close()
}
