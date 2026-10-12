package dev.avery.muon

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

internal enum class PartitionSavePhase { Reserved, Opening, Open, Closed, Uncertain }
internal data class PartitionSaveTicket(val allocation:CachePartitionAllocation,val token:String)
internal data class PartitionSaveRecord(val ticket:PartitionSaveTicket,val phase:PartitionSavePhase,val uid:Long?)

/** UNWIRED new-save ownership, separate from migration verification and download completion.
 * Opening/Open left by a crash NEVER authorizes reopen. Only the owned native lifecycle records
 * Closed after durable index/metadata checks; Unknown retains bytes and requires explicit recovery.
 * Exact remote identity, command exclusion and completion still belong to the production owner. */
internal class PartitionSaveJournal(root:File,create:Boolean=false,
    private val token:()->String={UUID.randomUUID().toString()}) : Closeable {
    private val schema="CREATE TABLE saves(key BLOB PRIMARY KEY NOT NULL,directory TEXT UNIQUE NOT NULL,token TEXT UNIQUE NOT NULL,phase INTEGER NOT NULL,uid INTEGER)"
    private val projection=listOf(
        "CASE WHEN typeof(key)='blob' AND length(key)<=$MIGRATION_KEY_BYTES THEN key ELSE NULL END",
        "CASE WHEN typeof(directory)='text' AND length(CAST(directory AS BLOB))=36 THEN directory ELSE NULL END",
        "CASE WHEN typeof(token)='text' AND length(CAST(token AS BLOB))=36 THEN token ELSE NULL END",
        "CASE WHEN typeof(phase)='integer' THEN phase ELSE NULL END",
        "CASE WHEN typeof(uid) IN ('integer','null') THEN uid ELSE 'invalid' END").joinToString(",")
    private val db:SQLiteDatabase
    init {
        if(create && !root.exists() && !root.mkdirs()) throw IOException("Save journal directory unavailable")
        if(!root.isDirectory || root.absoluteFile!=root.canonicalFile || Files.isSymbolicLink(root.toPath()))
            throw IOException("Save journal directory differs")
        val file=File(root,"new-save-journal-v1.db")
        if(create) {
            if(file.exists()) throw IOException("Fresh save journal already exists")
        } else if(!file.isFile || file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath()))
            throw IOException("Save journal is missing or changed")
        db=if(create) SQLiteDatabase.openOrCreateDatabase(file,null)
            else SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE)
        try {
            db.execSQL("PRAGMA cache_size=-256"); db.execSQL("PRAGMA synchronous=FULL")
            transaction {
                if(create) {
                    if(db.version!=0) throw IOException("Fresh save journal version differs")
                    db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND NOT (type='table' AND name='android_metadata')",null).use {
                        if(!it.moveToFirst() || it.getLong(0)!=0L) throw IOException("Fresh save journal has foreign objects")
                    }
                    db.execSQL(schema); db.version=1
                } else {
                    if(db.version!=1) throw IOException("Unsupported save journal version")
                    // Never load arbitrary schema text before its byte budget, and stop after two rows.
                    db.rawQuery("SELECT CASE WHEN type='table' AND name='saves' AND length(CAST(sql AS BLOB))<=1024 THEN sql ELSE NULL END FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND NOT (type='table' AND name='android_metadata') LIMIT 2",null).use {
                        if(!it.moveToFirst() || it.getType(0)!=Cursor.FIELD_TYPE_STRING || it.getString(0)!=schema || it.moveToNext())
                            throw IOException("Save journal schema differs")
                    }
                }
            }
        } catch(failure:Throwable) { db.close(); throw failure }
    }
    private inline fun <T> transaction(block:()->T):T {
        db.beginTransaction()
        try { val result=block(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    private fun requireKey(value:String) {
        if(value.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Save key exceeds budget")
    }
    private fun uuid(value:String) {
        val parsed=try { UUID.fromString(value) } catch(_:IllegalArgumentException) { throw IOException("Save identity malformed") }
        if(parsed.toString()!=value) throw IOException("Save identity is not canonical")
    }
    private fun identity(ticket:PartitionSaveTicket) { requireKey(ticket.allocation.key); uuid(ticket.allocation.directory); uuid(ticket.token) }
    private fun decode(c:Cursor):PartitionSaveRecord {
        if(c.getType(0)!=Cursor.FIELD_TYPE_BLOB || c.getType(1)!=Cursor.FIELD_TYPE_STRING || c.getType(2)!=Cursor.FIELD_TYPE_STRING ||
            c.getType(3)!=Cursor.FIELD_TYPE_INTEGER || (!c.isNull(4) && c.getType(4)!=Cursor.FIELD_TYPE_INTEGER))
            throw IOException("Save journal row type differs")
        val ticket=PartitionSaveTicket(CachePartitionAllocation(savedCatalogText(c.getBlob(0)),c.getString(1)),c.getString(2)); identity(ticket)
        val phase=c.getLong(3)
        if(phase !in 0L until PartitionSavePhase.entries.size.toLong()) throw IOException("Save journal state differs")
        val state=PartitionSavePhase.entries[phase.toInt()]; val uid=if(c.isNull(4)) null else c.getLong(4)
        if(uid!=null && uid<0 || state==PartitionSavePhase.Reserved && uid!=null ||
            state in listOf(PartitionSavePhase.Open,PartitionSavePhase.Closed) && uid==null)
            throw IOException("Save journal UID/state differs")
        return PartitionSaveRecord(ticket,state,uid)
    }
    private fun lookup(key:String):PartitionSaveRecord?=db.rawQueryWithFactory({_,driver,table,query ->
        query.bindBlob(1,savedCatalogSortKey(key)); SQLiteCursor(driver,table,query)
    },"SELECT $projection FROM saves WHERE key=?",null,"saves").use { if(it.moveToFirst()) decode(it) else null }
    @Synchronized fun find(key:String):PartitionSaveRecord? { requireKey(key); return lookup(key) }
    /** Replacement requires an explicitly identified Uncertain row and a fresh allocation; never
     * overwrites a usable, in-flight or another resource's record. Old bytes are not touched. */
    @Synchronized fun begin(allocation:CachePartitionAllocation,expectedToken:String?=null):PartitionSaveTicket {
        requireKey(allocation.key); uuid(allocation.directory); val ticket=PartitionSaveTicket(allocation,token()); identity(ticket)
        transaction {
            val old=lookup(allocation.key)
            if(old?.ticket?.token!=expectedToken || old!=null && (old.phase!=PartitionSavePhase.Uncertain || old.ticket.allocation==allocation))
                throw IOException("Save allocation is already owned or uncertain claim differs")
            val values=ContentValues().apply {
                put("directory",allocation.directory); put("token",ticket.token); put("phase",PartitionSavePhase.Reserved.ordinal); putNull("uid")
            }
            if(old==null) { values.put("key",savedCatalogSortKey(allocation.key)); db.insertOrThrow("saves",null,values) }
            else db.compileStatement("UPDATE saves SET directory=?,token=?,phase=?,uid=NULL WHERE key=? AND token=?").use {
                it.bindString(1,allocation.directory); it.bindString(2,ticket.token); it.bindLong(3,PartitionSavePhase.Reserved.ordinal.toLong())
                it.bindBlob(4,savedCatalogSortKey(allocation.key)); it.bindString(5,expectedToken!!)
                if(it.executeUpdateDelete()!=1) throw IOException("Save replacement lost ownership")
            }
        }
        return ticket
    }
    private fun owned(ticket:PartitionSaveTicket):PartitionSaveRecord {
        identity(ticket); val row=lookup(ticket.allocation.key)
        if(row?.ticket!=ticket) throw IOException("Save ticket is stale")
        return row
    }
    private fun update(ticket:PartitionSaveTicket,phase:PartitionSavePhase,uid:Long?) {
        db.compileStatement("UPDATE saves SET phase=?,uid=? WHERE key=? AND token=?").use {
            it.bindLong(1,phase.ordinal.toLong()); if(uid==null) it.bindNull(2) else it.bindLong(2,uid)
            it.bindBlob(3,savedCatalogSortKey(ticket.allocation.key)); it.bindString(4,ticket.token)
            if(it.executeUpdateDelete()!=1) throw IOException("Save lifecycle lost ownership")
        }
    }
    @Synchronized fun opening(ticket:PartitionSaveTicket):PartitionSaveRecord=transaction {
        val row=owned(ticket)
        if(row.phase!=PartitionSavePhase.Reserved && row.phase!=PartitionSavePhase.Closed) throw IOException("Save does not have clean opening authority")
        update(ticket,PartitionSavePhase.Opening,row.uid); row
    }
    @Synchronized fun opened(ticket:PartitionSaveTicket,uid:Long)=transaction {
        val row=owned(ticket)
        if(uid<0 || row.phase!=PartitionSavePhase.Opening || row.uid!=null && row.uid!=uid) throw IOException("Save opening UID differs")
        update(ticket,PartitionSavePhase.Open,uid)
    }
    @Synchronized fun closed(ticket:PartitionSaveTicket,uid:Long)=transaction {
        val row=owned(ticket)
        if(row.phase!=PartitionSavePhase.Open || row.uid!=uid) throw IOException("Save close identity differs")
        update(ticket,PartitionSavePhase.Closed,uid)
    }
    @Synchronized fun uncertain(ticket:PartitionSaveTicket)=transaction {
        val row=owned(ticket)
        if(row.phase!=PartitionSavePhase.Uncertain) update(ticket,PartitionSavePhase.Uncertain,row.uid)
    }
    @Synchronized fun page(after:String?=null):List<PartitionSaveRecord> {
        after?.let(::requireKey); val where=if(after==null) "" else " WHERE key>?"
        return db.rawQueryWithFactory({_,driver,table,query -> after?.let { query.bindBlob(1,savedCatalogSortKey(it)) }; SQLiteCursor(driver,table,query)
        },"SELECT $projection FROM saves$where ORDER BY key LIMIT $PARTITION_LOCATOR_WINDOW",null,"saves").use { c ->
            val rows=ArrayList<PartitionSaveRecord>(PARTITION_LOCATOR_WINDOW); while(c.moveToNext()) rows+=decode(c); rows
        }
    }
    @Synchronized override fun close()=db.close()
}
