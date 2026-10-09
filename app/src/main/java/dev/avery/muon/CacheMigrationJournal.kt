package dev.avery.muon

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID

internal enum class MigrationPhase { Copying, Verified, Ready, Uncertain }
internal data class MigrationTicket(val allocation:CachePartitionAllocation, val token:String, val sourceUid:Long)
internal data class MigrationRecord(val ticket:MigrationTicket, val phase:MigrationPhase,
    val targetUid:Long?, val evidence:MigrationCopyEvidence?)

/** Persistent bounded-page recovery and ready-routing record, not deletion authority. Only the
 * coordinator may advance a ticket while holding source/availability/writer/eviction exclusion.
 * Restarted Copying/Verified/Uncertain rows NEVER become Ready without a fresh replacement attempt.
 * A Ready row still needs matching native UID and current volume/ownership before use. */
internal class CacheMigrationJournal(root:File, private val token:()->String={UUID.randomUUID().toString()}) : Closeable {
    private val schema="CREATE TABLE migrations(key BLOB PRIMARY KEY NOT NULL, directory TEXT UNIQUE NOT NULL, token TEXT UNIQUE NOT NULL, source_uid INTEGER NOT NULL, phase INTEGER NOT NULL, target_uid INTEGER, bytes INTEGER, ranges INTEGER)"
    private val database:SQLiteDatabase
    private var verifiedHere:MigrationTicket?=null
    init {
        check(root.isDirectory || root.mkdirs()) { "No private migration journal directory" }
        database=SQLiteDatabase.openOrCreateDatabase(File(root,"migration-journal-v1.db"),null)
        try {
            database.execSQL("PRAGMA cache_size=-256")
            database.execSQL("PRAGMA synchronous=FULL")
            database.beginTransaction()
            try {
                when (database.version) {
                    0 -> {
                        database.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND name!='android_metadata'",null).use {
                            if (!it.moveToFirst() || it.getLong(0)!=0L) throw IOException("Unrecognized migration journal")
                        }
                        database.execSQL(schema); database.version=1
                    }
                    1 -> database.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND name!='android_metadata'",null).use {
                        if (!it.moveToFirst() || it.getString(0)!="table" || it.getString(1)!="migrations" || it.getString(2)!=schema || it.moveToNext())
                            throw IOException("Unrecognized migration journal schema")
                    }
                    else -> throw IOException("Unsupported migration journal version")
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        } catch (failure:Throwable) { database.close(); throw failure }
    }
    private fun identity(key:String) {
        if (key.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Migration key exceeds budget")
    }
    private fun uuid(value:String) {
        val parsed=try { UUID.fromString(value) } catch (_:IllegalArgumentException) { throw IOException("Malformed migration identity") }
        if (parsed.toString()!=value) throw IOException("Noncanonical migration identity")
    }
    private fun decode(c:android.database.Cursor):MigrationRecord {
        for(index in 0..4) {
            val expected=when(index) { 0 -> android.database.Cursor.FIELD_TYPE_BLOB; 1,2 -> android.database.Cursor.FIELD_TYPE_STRING; else -> android.database.Cursor.FIELD_TYPE_INTEGER }
            if(c.getType(index)!=expected) throw IOException("Malformed migration row type")
        }
        for(index in 5..7) if(!c.isNull(index) && c.getType(index)!=android.database.Cursor.FIELD_TYPE_INTEGER)
            throw IOException("Malformed migration receipt type")
        val key=savedCatalogText(c.getBlob(0)); identity(key)
        val directory=c.getString(1); val claim=c.getString(2); uuid(directory); uuid(claim)
        val source=c.getLong(3); if (source<0) throw IOException("Unknown migration source identity")
        val state=c.getLong(4)
        if(state !in 0L until MigrationPhase.entries.size.toLong()) throw IOException("Unknown migration state")
        val phase=MigrationPhase.entries[state.toInt()]
        val target=if(c.isNull(5)) null else c.getLong(5)
        val evidence=if(c.isNull(6) && c.isNull(7)) null else {
            if(c.isNull(6) || c.isNull(7)) throw IOException("Incomplete migration receipt")
            val bytes=c.getLong(6); val ranges=c.getLong(7)
            if(bytes<0 || ranges !in 0L..MIGRATION_RANGES.toLong() || (ranges==0L && bytes!=0L) || (ranges>0 && bytes<ranges))
                throw IOException("Invalid migration receipt")
            MigrationCopyEvidence(bytes,ranges.toInt())
        }
        if(target!=null && target<0) throw IOException("Unknown migration target identity")
        if((target==null)!=(evidence==null) || (phase==MigrationPhase.Copying && target!=null))
            throw IOException("Inconsistent migration receipt")
        if((phase==MigrationPhase.Verified || phase==MigrationPhase.Ready) && (target==null || evidence==null))
            throw IOException("Missing migration verification")
        return MigrationRecord(MigrationTicket(CachePartitionAllocation(key,directory),claim,source),phase,target,evidence)
    }
    private fun lookup(key:String):MigrationRecord?=database.rawQueryWithFactory({_,driver,table,query ->
        query.bindBlob(1,savedCatalogSortKey(key)); SQLiteCursor(driver,table,query)
    },"SELECT key,directory,token,source_uid,phase,target_uid,bytes,ranges FROM migrations WHERE key=?",null,"migrations").use {
        if(it.moveToFirst()) decode(it) else null
    }
    @Synchronized fun find(key:String):MigrationRecord? { identity(key); return lookup(key) }
    @Synchronized fun ready(key:String):MigrationRecord?=find(key)?.takeIf { it.phase==MigrationPhase.Ready }

    /** Compare-and-set a fresh attempt. Never replace another resource's row through SQL REPLACE. */
    @Synchronized fun begin(allocation:CachePartitionAllocation, sourceUid:Long, expectedToken:String?):MigrationTicket {
        identity(allocation.key); uuid(allocation.directory)
        if(sourceUid<0) throw IOException("Migration source identity unavailable")
        val claim=token(); uuid(claim)
        verifiedHere=null
        database.beginTransaction()
        try {
            val old=lookup(allocation.key)
            if(old?.ticket?.token!=expectedToken || old?.phase==MigrationPhase.Ready || old?.ticket?.allocation==allocation)
                throw IOException("Migration attempt no longer owns this resource")
            val ticket=MigrationTicket(allocation,claim,sourceUid)
            val values=android.content.ContentValues().apply {
                put("directory",allocation.directory); put("token",claim); put("source_uid",sourceUid)
                put("phase",MigrationPhase.Copying.ordinal); putNull("target_uid"); putNull("bytes"); putNull("ranges")
            }
            if(old==null) {
                values.put("key",savedCatalogSortKey(allocation.key))
                if(database.insertOrThrow("migrations",null,values)==-1L) throw IOException("Migration journal insertion refused")
            } else database.compileStatement("UPDATE migrations SET directory=?,token=?,source_uid=?,phase=?,target_uid=NULL,bytes=NULL,ranges=NULL WHERE key=?").use {
                it.bindString(1,allocation.directory); it.bindString(2,claim); it.bindLong(3,sourceUid)
                it.bindLong(4,MigrationPhase.Copying.ordinal.toLong()); it.bindBlob(5,savedCatalogSortKey(allocation.key))
                if(it.executeUpdateDelete()!=1) throw IOException("Migration journal replacement refused")
            }
            database.setTransactionSuccessful(); return ticket
        } finally { database.endTransaction() }
    }
    private fun owned(ticket:MigrationTicket,phase:MigrationPhase):MigrationRecord {
        val row=lookup(ticket.allocation.key)
        if(row?.ticket!=ticket || row.phase!=phase) throw IOException("Migration ticket is stale")
        return row
    }
    @Synchronized fun verified(ticket:MigrationTicket,targetUid:Long,evidence:MigrationCopyEvidence) {
        if(targetUid<0 || evidence.bytes<0 || evidence.ranges !in 0..MIGRATION_RANGES ||
            (evidence.ranges==0 && evidence.bytes!=0L) || (evidence.ranges>0 && evidence.bytes<evidence.ranges))
            throw IOException("Invalid migration verification")
        database.beginTransaction()
        try {
            owned(ticket,MigrationPhase.Copying)
            database.compileStatement("UPDATE migrations SET phase=?,target_uid=?,bytes=?,ranges=? WHERE key=?").use {
                it.bindLong(1,MigrationPhase.Verified.ordinal.toLong()); it.bindLong(2,targetUid)
                it.bindLong(3,evidence.bytes); it.bindLong(4,evidence.ranges.toLong()); it.bindBlob(5,savedCatalogSortKey(ticket.allocation.key))
                if(it.executeUpdateDelete()!=1) throw IOException("Migration verification not recorded")
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        verifiedHere=ticket
    }
    /** Caller has just performed full comparison after clean reopen/close under unchanged exclusion. */
    @Synchronized fun publish(ticket:MigrationTicket):MigrationRecord {
        if(verifiedHere!=ticket) throw IOException("Migration needs fresh verification in this process")
        database.beginTransaction()
        try {
            owned(ticket,MigrationPhase.Verified)
            database.compileStatement("UPDATE migrations SET phase=? WHERE key=?").use {
                it.bindLong(1,MigrationPhase.Ready.ordinal.toLong()); it.bindBlob(2,savedCatalogSortKey(ticket.allocation.key))
                if(it.executeUpdateDelete()!=1) throw IOException("Migration publication not recorded")
            }
            val row=owned(ticket,MigrationPhase.Ready)
            database.setTransactionSuccessful(); verifiedHere=null; return row
        } finally { database.endTransaction() }
    }
    @Synchronized fun uncertain(ticket:MigrationTicket) {
        if(verifiedHere==ticket) verifiedHere=null
        database.beginTransaction()
        try {
            val row=lookup(ticket.allocation.key)
            if(row?.ticket==ticket && row.phase!=MigrationPhase.Ready) {
                database.compileStatement("UPDATE migrations SET phase=? WHERE key=?").use {
                    it.bindLong(1,MigrationPhase.Uncertain.ordinal.toLong()); it.bindBlob(2,savedCatalogSortKey(ticket.allocation.key))
                    if(it.executeUpdateDelete()!=1) throw IOException("Migration uncertainty not recorded")
                }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    /** Keyset page of at most16 exact records. Concurrent insertion needs a new scan. */
    @Synchronized fun page(after:String?=null):List<MigrationRecord> {
        after?.let(::identity)
        val where=if(after==null) "" else " WHERE key>?"
        return database.rawQueryWithFactory({_,driver,table,query ->
            after?.let { query.bindBlob(1,savedCatalogSortKey(it)) }; SQLiteCursor(driver,table,query)
        },"SELECT key,directory,token,source_uid,phase,target_uid,bytes,ranges FROM migrations$where ORDER BY key LIMIT $PARTITION_LOCATOR_WINDOW",null,"migrations").use {
            val rows=ArrayList<MigrationRecord>(PARTITION_LOCATOR_WINDOW)
            while(it.moveToNext()) rows+=decode(it)
            rows
        }
    }
    @Synchronized override fun close() { verifiedHere=null; database.close() }
}
