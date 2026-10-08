package dev.avery.muon

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID

internal const val PARTITION_LOCATOR_WINDOW=16
internal data class CachePartitionAllocation(val key:String, val directory:String)

/** Persistent exact reservations for opt-in migration; no readiness/publish/delete authority. */
internal class CachePartitionCatalog(private val root:File, private val newDirectory:()->String={ UUID.randomUUID().toString() }) : Closeable {
    private val resources=File(root,"resources")
    private val database:SQLiteDatabase
    init {
        check(root.isDirectory || root.mkdirs()) { "No private partition directory" }
        database=SQLiteDatabase.openOrCreateDatabase(File(root,"partition-locators-v1.db"),null)
        try {
            database.execSQL("PRAGMA cache_size=-256")
            database.beginTransaction()
            try {
                when (database.version) {
                    0 -> {
                        val existing=database.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata'",null).use {
                            if (!it.moveToFirst()) throw IOException("Partition schema unreadable")
                            it.getLong(0)
                        }
                        if (existing!=0L) throw IOException("Unrecognized partition schema")
                        database.execSQL("CREATE TABLE partitions(key BLOB PRIMARY KEY NOT NULL, directory TEXT UNIQUE NOT NULL)")
                        database.version=1
                    }
                    1 -> Unit
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
        val parsed=try { UUID.fromString(directory) } catch (_:IllegalArgumentException) { throw IOException("Partition locator malformed") }
        if (parsed.toString()!=directory) throw IOException("Partition locator not canonical")
        return CachePartitionAllocation(key,directory)
    }
    private fun lookup(key:String):CachePartitionAllocation? = database.rawQueryWithFactory({ _,driver,table,query ->
        query.bindBlob(1,savedCatalogSortKey(key)); SQLiteCursor(driver,table,query)
    },"SELECT directory FROM partitions WHERE key=?",null,null).use { cursor ->
        if (!cursor.moveToFirst()) null else allocation(key,cursor.getString(0))
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
    /** Path validation alone is not ownership or permission to modify a directory. */
    fun directory(candidate:CachePartitionAllocation):File {
        allocation(candidate.key,candidate.directory)
        val parent=resources.canonicalFile
        val target=File(resources,candidate.directory).canonicalFile
        if (target.parentFile!=parent) throw IOException("Partition directory leaves its private root")
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
        },"SELECT key,directory FROM partitions$where ORDER BY key LIMIT $PARTITION_LOCATOR_WINDOW",null,null).use { cursor ->
            val rows=ArrayList<CachePartitionAllocation>(PARTITION_LOCATOR_WINDOW)
            while (cursor.moveToNext()) rows+=allocation(savedCatalogText(cursor.getBlob(0)),cursor.getString(1))
            rows
        }
    }
    @Synchronized override fun close()=database.close()
}
