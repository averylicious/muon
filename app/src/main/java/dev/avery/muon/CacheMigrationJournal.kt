package dev.avery.muon

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.nio.file.Files
import android.database.Cursor

internal const val MIGRATION_RECOVERY_PAGE=2
internal data class MigrationRecoveryPage(val entries:List<MigrationRecord>,val after:String?,val hasMore:Boolean)

internal enum class MigrationPhase { Copying, Verified, Ready, Uncertain }
internal data class MigrationTicket(val allocation:CachePartitionAllocation, val token:String, val sourceUid:Long)
internal data class MigrationRecord(val ticket:MigrationTicket, val phase:MigrationPhase,
    val targetUid:Long?, val evidence:MigrationCopyEvidence?)

/** Persistent bounded-page recovery and ready-routing record, not deletion authority. Only the
 * coordinator may advance a ticket while holding source/availability/writer/eviction exclusion.
 * Restarted Copying/Verified/Uncertain rows NEVER become Ready without a fresh replacement attempt.
 * A Ready row still needs matching native UID and current volume/ownership before use. */
internal class CacheMigrationJournal(private val root:File, private val create:Boolean=true, private val token:()->String={UUID.randomUUID().toString()},
    private val recoveryIo:MigrationIoOwnership=MigrationIoOwnership(),
    private val autoOpen:Boolean=true,
    private val openDatabase:(File,Boolean)->SQLiteDatabase={file,fresh ->
        if(fresh) SQLiteDatabase.openOrCreateDatabase(file,null)
        else SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)},
    private val closeDatabase:(SQLiteDatabase)->Unit={it.close()},
    private val finishTransaction:(SQLiteDatabase)->Unit={it.endTransaction()}) : Closeable {
    private val schema="CREATE TABLE migrations(key BLOB PRIMARY KEY NOT NULL, directory TEXT UNIQUE NOT NULL, token TEXT UNIQUE NOT NULL, source_uid INTEGER NOT NULL, phase INTEGER NOT NULL, target_uid INTEGER, bytes INTEGER, ranges INTEGER)"
    // Bounded projections reject corruption BEFORE CursorWindow loads payload. Invalid nullable
    // numeric types use a small text sentinel so they cannot masquerade as legitimate SQL NULL.
    private val projection=(listOf(
        "CASE WHEN typeof(key)='blob' AND length(key)<=$MIGRATION_KEY_BYTES THEN key ELSE NULL END",
        "CASE WHEN typeof(directory)='text' AND length(CAST(directory AS BLOB))=36 THEN directory ELSE NULL END",
        "CASE WHEN typeof(token)='text' AND length(CAST(token AS BLOB))=36 THEN token ELSE NULL END",
        "CASE WHEN typeof(source_uid)='integer' THEN source_uid ELSE NULL END",
        "CASE WHEN typeof(phase)='integer' THEN phase ELSE NULL END")+
        listOf("target_uid","bytes","ranges").map { "CASE WHEN typeof($it) IN ('integer','null') THEN $it ELSE 'invalid' END" }).joinToString(",")
    private enum class Phase { New,Opening,Open,Unsupported,Closing,Uncertain,Closed }
    private var phase=Phase.New
    private var database:SQLiteDatabase?=null
    private var verifiedHere:MigrationTicket?=null
    @get:Synchronized val ownershipUncertain:Boolean get()=phase==Phase.Uncertain
    private fun unknown(failure:Throwable):Nothing {
        phase=Phase.Uncertain;verifiedHere=null
        throw PartitionOwnershipUncertain(this,failure)
    }
    private fun db():SQLiteDatabase {
        if(phase!=Phase.Open) throw IOException("Migration journal is not open or its ownership is uncertain")
        if(!recoveryIo.quiescent) unknown(MigrationIoUncertain(recoveryIo,null))
        return requireNotNull(database)
    }
    private fun <R:Closeable,T> useOwned(create:()->R,read:(R)->T):T {
        try {
            return recoveryIo.useOwned(create,read).also {
                if(phase==Phase.Uncertain) unknown(IOException("Migration journal became uncertain during I/O"))
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
        if(phase==Phase.Uncertain || !recoveryIo.quiescent) unknown(IOException("Migration transaction still owns uncertain I/O"))
        try {finishTransaction(db)} catch(failure:Throwable) {unknown(failure)}
    }
    init {if(autoOpen) open()}
    /** Production registers this harmless owner BEFORE open(); autoOpen remains for existing
     * standalone callers, whose uncertainty exception also retains this exact owner/connection. */
    @Synchronized fun open() {
        if(phase==Phase.Open) return
        if(phase!=Phase.New) throw IOException("Migration journal startup cannot be retried")
        phase=Phase.Opening
        try {
            if(create && !root.exists() && !root.mkdirs()) throw IOException("No private migration directory")
            if(!root.isDirectory || root.absoluteFile!=root.canonicalFile || Files.isSymbolicLink(root.toPath()))
                throw IOException("Migration journal directory differs")
            val file=File(root,"migration-journal-v1.db")
            if(file.exists() && (!file.isFile || file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath())))
                throw IOException("Migration journal file differs")
            if(!create && !file.isFile) throw IOException("Migration journal missing; resume cannot initialize it")
            val opened=try {openDatabase(file,create)} catch(failure:Throwable) {unknown(failure)}
            database=opened // Retained before schema/transaction/cursor work.
            opened.execSQL("PRAGMA cache_size=-256");opened.execSQL("PRAGMA synchronous=FULL")
            begin(opened)
            try {
                when(opened.version) {
                    0 -> {
                        if(!create) throw IOException("Migration journal is uninitialized; resume cannot replace it")
                        useOwned({opened.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND name!='android_metadata'",null)}) {
                            if(!it.moveToFirst() || it.getLong(0)!=0L) throw IOException("Unrecognized migration journal")
                        }
                        opened.execSQL(schema);opened.version=1
                    }
                    1 -> useOwned({opened.rawQuery("SELECT CASE WHEN type='table' AND name='migrations' AND length(CAST(sql AS BLOB))<=1024 THEN sql ELSE NULL END FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND NOT (type='table' AND name='android_metadata') LIMIT 2",null)}) {
                        if(!it.moveToFirst() || it.getType(0)!=Cursor.FIELD_TYPE_STRING || it.getString(0)!=schema || it.moveToNext())
                            throw IOException("Unrecognized migration journal schema")
                    }
                    else -> throw IOException("Unsupported migration journal version")
                }
                successful(opened)
            } finally {end(opened)}
            phase=Phase.Open
        } catch(failure:Throwable) {
            if(phase==Phase.Uncertain || !recoveryIo.quiescent) unknown(failure)
            database?.let {actual ->try {closeDatabase(actual);database=null} catch(close:Throwable) {unknown(close)}}
            phase=Phase.Unsupported;throw failure
        }
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
    private fun lookup(key:String):MigrationRecord? {
        val db=db()
        return useOwned({db.rawQueryWithFactory({_,driver,table,query ->
            query.bindBlob(1,savedCatalogSortKey(key));SQLiteCursor(driver,table,query)
        },"SELECT $projection FROM migrations WHERE key=?",null,"migrations")}) {
            if(it.moveToFirst()) decode(it) else null
        }
    }
    @Synchronized fun find(key:String):MigrationRecord? { db(); identity(key); return lookup(key) }
    @Synchronized fun ready(key:String):MigrationRecord?=find(key)?.takeIf { it.phase==MigrationPhase.Ready }

    /** Compare-and-set a fresh attempt. Never replace another resource's row through SQL REPLACE. */
    @Synchronized fun begin(allocation:CachePartitionAllocation, sourceUid:Long, expectedToken:String?,
        expectation:MigrationExpectation=MigrationExpectation.Unchecked):MigrationTicket {
        val db=db(); identity(allocation.key); uuid(allocation.directory)
        if(sourceUid<0) throw IOException("Migration source identity unavailable")
        val claim=token(); uuid(claim)
        verifiedHere=null
        begin(db)
        try {
            val old=lookup(allocation.key)
            expectation.check(allocation.key,old)
            if(old?.ticket?.token!=expectedToken || old?.phase==MigrationPhase.Ready || old?.ticket?.allocation==allocation)
                throw IOException("Migration attempt no longer owns this resource")
            val ticket=MigrationTicket(allocation,claim,sourceUid)
            if(old==null) useOwned({db.compileStatement("INSERT INTO migrations(key,directory,token,source_uid,phase,target_uid,bytes,ranges) VALUES(?,?,?,?,?,NULL,NULL,NULL)")}) {
                it.bindBlob(1,savedCatalogSortKey(allocation.key));it.bindString(2,allocation.directory);it.bindString(3,claim)
                it.bindLong(4,sourceUid);it.bindLong(5,MigrationPhase.Copying.ordinal.toLong())
                if(it.executeInsert()==-1L) throw IOException("Migration journal insertion refused")
            } else useOwned({db.compileStatement("UPDATE migrations SET directory=?,token=?,source_uid=?,phase=?,target_uid=NULL,bytes=NULL,ranges=NULL WHERE key=?")}) {
                it.bindString(1,allocation.directory);it.bindString(2,claim);it.bindLong(3,sourceUid)
                it.bindLong(4,MigrationPhase.Copying.ordinal.toLong());it.bindBlob(5,savedCatalogSortKey(allocation.key))
                if(it.executeUpdateDelete()!=1) throw IOException("Migration journal replacement refused")
            }
            successful(db); return ticket
        } finally { end(db) }
    }
    private fun owned(ticket:MigrationTicket,phase:MigrationPhase):MigrationRecord {
        val row=lookup(ticket.allocation.key)
        if(row?.ticket!=ticket || row.phase!=phase) throw IOException("Migration ticket is stale")
        return row
    }
    @Synchronized fun verified(ticket:MigrationTicket,targetUid:Long,evidence:MigrationCopyEvidence) {
        val db=db()
        if(targetUid<0 || evidence.bytes<0 || evidence.ranges !in 0..MIGRATION_RANGES ||
            (evidence.ranges==0 && evidence.bytes!=0L) || (evidence.ranges>0 && evidence.bytes<evidence.ranges))
            throw IOException("Invalid migration verification")
        begin(db)
        try {
            owned(ticket,MigrationPhase.Copying)
            useOwned({db.compileStatement("UPDATE migrations SET phase=?,target_uid=?,bytes=?,ranges=? WHERE key=?")}) {
                it.bindLong(1,MigrationPhase.Verified.ordinal.toLong()); it.bindLong(2,targetUid)
                it.bindLong(3,evidence.bytes); it.bindLong(4,evidence.ranges.toLong()); it.bindBlob(5,savedCatalogSortKey(ticket.allocation.key))
                if(it.executeUpdateDelete()!=1) throw IOException("Migration verification not recorded")
            }
            successful(db)
        } finally { end(db) }
        verifiedHere=ticket
    }
    /** Caller has just performed full comparison after clean reopen/close under unchanged exclusion. */
    @Synchronized fun publish(ticket:MigrationTicket):MigrationRecord {
        val db=db()
        if(verifiedHere!=ticket) throw IOException("Migration needs fresh verification in this process")
        begin(db)
        try {
            owned(ticket,MigrationPhase.Verified)
            useOwned({db.compileStatement("UPDATE migrations SET phase=? WHERE key=?")}) {
                it.bindLong(1,MigrationPhase.Ready.ordinal.toLong()); it.bindBlob(2,savedCatalogSortKey(ticket.allocation.key))
                if(it.executeUpdateDelete()!=1) throw IOException("Migration publication not recorded")
            }
            val row=owned(ticket,MigrationPhase.Ready)
            successful(db); verifiedHere=null; return row
        } finally { end(db) }
    }
    @Synchronized fun uncertain(ticket:MigrationTicket) {
        val db=db()
        if(verifiedHere==ticket) verifiedHere=null
        begin(db)
        try {
            val row=lookup(ticket.allocation.key)
            if(row?.ticket==ticket && row.phase!=MigrationPhase.Ready) {
                useOwned({db.compileStatement("UPDATE migrations SET phase=? WHERE key=?")}) {
                    it.bindLong(1,MigrationPhase.Uncertain.ordinal.toLong()); it.bindBlob(2,savedCatalogSortKey(ticket.allocation.key))
                    if(it.executeUpdateDelete()!=1) throw IOException("Migration uncertainty not recorded")
                }
            }
            successful(db)
        } finally { end(db) }
    }
    /** Keyset page of at most16 exact records. Concurrent insertion needs a new scan. */
    @Synchronized fun page(after:String?=null):List<MigrationRecord> {
        val db=db()
        after?.let(::identity)
        val where=if(after==null) "" else " WHERE key>?"
        return useOwned({db.rawQueryWithFactory({_,driver,table,query ->
            after?.let { query.bindBlob(1,savedCatalogSortKey(it)) }; SQLiteCursor(driver,table,query)
        },"SELECT $projection FROM migrations$where ORDER BY key LIMIT $PARTITION_LOCATOR_WINDOW",null,"migrations")}) {
            val rows=ArrayList<MigrationRecord>(PARTITION_LOCATOR_WINDOW)
            while(it.moveToNext()) rows+=decode(it)
            rows
        }
    }
    /** Recovery retains at most two scalar records, filtering Ready before cursor hydration.
     * Malformed phase values are deliberately included so corruption cannot become false absence.
     * Unknown actual cursor close retains its exact connection through this journal/root owner.
     */
    @Synchronized fun pendingPage(after:String?=null):MigrationRecoveryPage {
        val db=db();after?.let(::identity)
        val where="(typeof(phase)!='integer' OR phase!=${MigrationPhase.Ready.ordinal})"+
            if(after==null) "" else " AND key>?"
        val rows=useOwned({db.rawQueryWithFactory({_,driver,table,query ->
            after?.let {query.bindBlob(1,savedCatalogSortKey(it))};SQLiteCursor(driver,table,query)
        },"SELECT $projection FROM migrations WHERE $where ORDER BY key LIMIT $MIGRATION_RECOVERY_PAGE",null,"migrations")}) {
            val entries=ArrayList<MigrationRecord>(MIGRATION_RECOVERY_PAGE)
            while(it.moveToNext()) entries+=decode(it)
            entries
        }
        return MigrationRecoveryPage(rows,rows.lastOrNull()?.ticket?.allocation?.key,rows.size==MIGRATION_RECOVERY_PAGE)
    }
    @Synchronized override fun close() {
        if(phase==Phase.Closed || phase==Phase.Unsupported) return
        if(phase==Phase.New) {phase=Phase.Closed;return}
        val actual=db();phase=Phase.Closing;verifiedHere=null
        try {closeDatabase(actual);database=null;phase=Phase.Closed}
        catch(failure:Throwable) {unknown(failure)}
    }
}
