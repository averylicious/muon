package dev.avery.muon

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

internal const val PARTITION_SAVED_MOVE_PAGE=2
internal const val PARTITION_SAVED_MOVE_BYTES=256*1024
internal enum class PartitionSavedMovePhase { Pending, Complete }
internal data class PartitionSavedMoveRecord(val token:String,val phase:PartitionSavedMovePhase,
    val binding:PartitionSavedMoveBinding)

/** Durable explicit saved Move intent / route tombstone, NOT cleanup or deletion authority.
 * Register this owner BEFORE open(). No constructor I/O, automatic repair/replay or native-index
 * mutation. A future controller must hold actual exclusive source/volume ownership, validate the
 * exact completed download/aliases and binding, and only finish after byte/index disposition.
 * Unknown DB write/close retains this object and its exact connection; caller must quarantine its
 * storage gate. Complete records still reserve their original name and suppress legacy fallback.
 */
internal class PartitionSavedMoveJournal(private val root:File,private val create:Boolean,
    private val token:()->String={UUID.randomUUID().toString()},
    private val openDatabase:(File,Boolean)->SQLiteDatabase={file,fresh ->
        if(fresh) SQLiteDatabase.openOrCreateDatabase(file,null)
        else SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE)},
    private val closeDatabase:(SQLiteDatabase)->Unit={it.close()},
    private val closeIo:(Closeable)->Unit={it.close()}) : Closeable {
    private enum class Phase { New,Opening,Open,Unsupported,Uncertain,Closed }
    private var phase=Phase.New
    private var database:SQLiteDatabase?=null
    @get:Synchronized val ownershipUncertain:Boolean get()=phase==Phase.Uncertain
    private var ownedIo:Closeable?=null
    private fun <T,R:Closeable> useOwned(open:()->R,read:(R)->T):T {
        if(ownedIo!=null) throw IOException("Saved Move journal I/O is still owned")
        val actual=open(); ownedIo=actual
        var failure:Throwable?=null
        try {return read(actual)} catch(error:Throwable) {failure=error; throw error}
        finally {
            try {closeIo(actual); ownedIo=null}
            catch(close:Throwable) {
                phase=Phase.Uncertain
                if(failure!=null && failure!==close) close.addSuppressed(failure)
                throw PartitionOwnershipUncertain(this,close)
            }
        }
    }
    private fun beginTransaction(db:SQLiteDatabase) {
        try {db.beginTransaction()} catch(failure:Throwable) {phase=Phase.Uncertain; throw PartitionOwnershipUncertain(this,failure)}
    }
    private val schema="CREATE TABLE saved_moves(key BLOB PRIMARY KEY NOT NULL,token TEXT UNIQUE NOT NULL,phase INTEGER NOT NULL,binding BLOB NOT NULL)"
    private val projection="CASE WHEN typeof(key)='blob' AND length(key)<=$MIGRATION_KEY_BYTES THEN key ELSE NULL END,"+
        "CASE WHEN typeof(token)='text' AND length(CAST(token AS BLOB))=36 THEN token ELSE NULL END,"+
        "CASE WHEN typeof(phase)='integer' THEN phase ELSE NULL END,"+
        "CASE WHEN typeof(binding)='blob' AND length(binding)<=$PARTITION_SAVED_MOVE_BYTES THEN binding ELSE NULL END"
    private fun worker() {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Saved Move journal requires a worker"}
    }
    private fun db(offMain:Boolean=true):SQLiteDatabase {
        if(offMain) worker()
        if(phase!=Phase.Open) throw IOException("Saved Move journal is not open or its ownership is uncertain")
        return requireNotNull(database)
    }
    private fun endTransaction(db:SQLiteDatabase) {
        if(ownedIo!=null) {phase=Phase.Uncertain; return} // Never roll back over an unknown live statement.
        try {db.endTransaction()} catch(failure:Throwable) {phase=Phase.Uncertain; throw PartitionOwnershipUncertain(this,failure)}
    }
    @Synchronized fun open() {
        worker()
        if(phase==Phase.Open) return
        if(phase!=Phase.New) throw IOException("Saved Move journal startup cannot be retried")
        phase=Phase.Opening
        try {
            if(!root.isDirectory || root.absoluteFile!=root.canonicalFile || Files.isSymbolicLink(root.toPath()))
                throw IOException("Saved Move journal root differs")
            val file=File(root,"partition-moves-v1.db")
            if(Files.isSymbolicLink(file.toPath()) || file.absoluteFile!=file.canonicalFile ||
                (create && file.exists()) || (!create && !file.isFile))
                throw IOException("Saved Move journal is missing, changed or not fresh")
            val opened=openDatabase(file,create); database=opened // Actual owner retained before schema I/O.
            opened.execSQL("PRAGMA cache_size=-256"); opened.execSQL("PRAGMA synchronous=FULL")
            beginTransaction(opened)
            try {
                if(create) {
                    if(opened.version!=0) throw IOException("Fresh saved Move journal version differs")
                    useOwned({opened.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND name!='android_metadata'",null)}) {
                        if(!it.moveToFirst() || it.getLong(0)!=0L) throw IOException("Fresh saved Move journal has foreign objects")
                    }
                    opened.execSQL(schema); opened.version=1
                } else {
                    if(opened.version!=1) throw IOException("Unsupported saved Move journal version")
                    useOwned({opened.rawQuery("SELECT CASE WHEN type='table' AND name='saved_moves' AND length(CAST(sql AS BLOB))<=1024 THEN sql ELSE NULL END "+
                        "FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND NOT(type='table' AND name='android_metadata') LIMIT 2",null)}) {
                        if(!it.moveToFirst() || it.getType(0)!=Cursor.FIELD_TYPE_STRING || it.getString(0)!=schema || it.moveToNext())
                            throw IOException("Unsupported saved Move journal schema")
                    }
                }
                opened.setTransactionSuccessful()
            } finally {endTransaction(opened)}
            phase=Phase.Open
        } catch(failure:Throwable) {
            if(ownedIo!=null || phase==Phase.Uncertain) {
                phase=Phase.Uncertain
                throw if(failure is PartitionOwnershipUncertain) failure else PartitionOwnershipUncertain(this,failure)
            }
            try {database?.let(closeDatabase); database=null; phase=Phase.Unsupported}
            catch(close:Throwable) {
                phase=Phase.Uncertain
                if(close!==failure) close.addSuppressed(failure)
                throw PartitionOwnershipUncertain(this,close)
            }
            throw failure
        }
    }
    private fun key(value:String) {
        if(value.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Saved Move key exceeds budget")
    }
    private fun uuid(value:String) {
        val parsed=try {UUID.fromString(value)} catch(_:IllegalArgumentException) {throw IOException("Saved Move token malformed")}
        if(parsed.toString()!=value) throw IOException("Saved Move token is not canonical")
    }
    private fun decode(c:Cursor):PartitionSavedMoveRecord {
        if(c.getType(0)!=Cursor.FIELD_TYPE_BLOB || c.getType(1)!=Cursor.FIELD_TYPE_STRING ||
            c.getType(2)!=Cursor.FIELD_TYPE_INTEGER || c.getType(3)!=Cursor.FIELD_TYPE_BLOB)
            throw IOException("Saved Move row exceeds its type/budget")
        val name=savedCatalogText(c.getBlob(0)); key(name)
        val token=c.getString(1); uuid(token); val state=c.getLong(2)
        if(state !in 0L until PartitionSavedMovePhase.entries.size.toLong()) throw IOException("Unknown saved Move state")
        val binding=PartitionSavedMoveBinding.decode(c.getBlob(3))
        if(binding.key!=name) throw IOException("Saved Move binding belongs to another key")
        return PartitionSavedMoveRecord(token,PartitionSavedMovePhase.entries[state.toInt()],binding)
    }
    private fun lookup(db:SQLiteDatabase,name:String):PartitionSavedMoveRecord?=useOwned({db.rawQueryWithFactory({_,driver,table,query ->
        query.bindBlob(1,savedCatalogSortKey(name)); SQLiteCursor(driver,table,query)
    },"SELECT $projection FROM saved_moves WHERE key=? LIMIT 2",null,"saved_moves")}) {
        if(!it.moveToFirst()) null else decode(it).also {_ -> if(it.moveToNext()) throw IOException("Saved Move key is not unique")}
    }
    @Synchronized fun find(name:String):PartitionSavedMoveRecord? {key(name); return lookup(db(),name)}
    /** Bounded scalar route/name lookup; caller owns dispatch. It never decodes binding payloads.
     * Complete tombstones reserve names; malformed row names make the result unknown. */
    @Synchronized fun holdsName(name:String):Boolean {
        key(name)
        return useOwned({db(false).rawQueryWithFactory({_,driver,table,query ->
            query.bindBlob(1,savedCatalogSortKey(name)); SQLiteCursor(driver,table,query)
        },"SELECT CASE WHEN typeof(key)='blob' AND length(key)<=$MIGRATION_KEY_BYTES AND length(key)%2=0 THEN 1 ELSE 0 END FROM saved_moves "+
            "WHERE key=? OR typeof(key)!='blob' OR length(key)>$MIGRATION_KEY_BYTES OR length(key)%2!=0 LIMIT 1",null,"saved_moves")}) {
            if(!it.moveToFirst()) false else if(it.getLong(0)==1L) true else throw IOException("Saved Move name census malformed")
        }
    }
    /** Two bindings per page, projected before CursorWindow. No whole journal or file history list. */
    @Synchronized fun page(after:String?=null):List<PartitionSavedMoveRecord> = pageChecked(after,false)
    /** Completed tombstones are not recovery work. Malformed phases still reach decode/refuse. */
    @Synchronized fun pendingPage(after:String?=null):List<PartitionSavedMoveRecord> = pageChecked(after,true)
    private fun pageChecked(after:String?,pendingOnly:Boolean):List<PartitionSavedMoveRecord> {
        after?.let(::key)
        val predicates=buildList {
            if(pendingOnly) add("(phase IS NOT 1 OR typeof(phase)!='integer')")
            if(after!=null) add("key>?")
        }
        val query="SELECT $projection FROM saved_moves"+
            (if(predicates.isEmpty()) "" else " WHERE "+predicates.joinToString(" AND "))+" ORDER BY key LIMIT $PARTITION_SAVED_MOVE_PAGE"
        return useOwned({db().rawQueryWithFactory({_,driver,table,sql ->
            if(after!=null) sql.bindBlob(1,savedCatalogSortKey(after)); SQLiteCursor(driver,table,sql)
        },query,null,"saved_moves")}) {cursor -> buildList {while(cursor.moveToNext()) add(decode(cursor))}}
    }
    /** Explicit intent must be persisted BEFORE physical mutation. No replacement/adoption of an
     * existing interrupted or complete intent. A future retry uses the exact current record/owner.
     */
    @Synchronized fun begin(binding:PartitionSavedMoveBinding,explicitConsent:Boolean):PartitionSavedMoveRecord {
        val db=db()
        if(!explicitConsent) throw IOException("Saved Move requires explicit consent")
        key(binding.key); val bytes=binding.encode(); val token=token().also(::uuid)
        beginTransaction(db); var writing=false
        try {
            if(lookup(db,binding.key)!=null) throw IOException("Saved Move already has an intent")
            writing=true
            useOwned({db.compileStatement("INSERT INTO saved_moves VALUES(?,?,?,?)")}) {
                it.bindBlob(1,savedCatalogSortKey(binding.key)); it.bindString(2,token)
                it.bindLong(3,PartitionSavedMovePhase.Pending.ordinal.toLong()); it.bindBlob(4,bytes)
                if(it.executeInsert()==-1L) throw IOException("Saved Move intent was not recorded")
            }
            db.setTransactionSuccessful()
        } catch(failure:Throwable) {
            if(writing || phase==Phase.Uncertain) {
                phase=Phase.Uncertain
                throw if(failure is PartitionOwnershipUncertain) failure else PartitionOwnershipUncertain(this,failure)
            }
            throw failure
        }
        finally {endTransaction(db)}
        return PartitionSavedMoveRecord(token,PartitionSavedMovePhase.Pending,binding)
    }
    /** Record the controller's final verified disposition, not a substitute for performing it.
     * Exact pending binding/token must still match. Caller owes physical/index/route/cover checks.
     * A complete record stays as a no-fallback/no-ID-reuse tombstone across restart.
     */
    @Synchronized fun complete(expected:PartitionSavedMoveRecord):PartitionSavedMoveRecord {
        val db=db(); key(expected.binding.key); uuid(expected.token)
        beginTransaction(db); var writing=false
        try {
            if(expected.phase!=PartitionSavedMovePhase.Pending || lookup(db,expected.binding.key)!=expected)
                throw IOException("Saved Move completion is stale")
            writing=true
            useOwned({db.compileStatement("UPDATE saved_moves SET phase=? WHERE key=? AND token=?")}) {
                it.bindLong(1,PartitionSavedMovePhase.Complete.ordinal.toLong()); it.bindBlob(2,savedCatalogSortKey(expected.binding.key))
                it.bindString(3,expected.token); if(it.executeUpdateDelete()!=1) throw IOException("Saved Move completion was not recorded")
            }
            db.setTransactionSuccessful()
        } catch(failure:Throwable) {
            if(writing || phase==Phase.Uncertain) {
                phase=Phase.Uncertain
                throw if(failure is PartitionOwnershipUncertain) failure else PartitionOwnershipUncertain(this,failure)
            }
            throw failure
        }
        finally {endTransaction(db)}
        return expected.copy(phase=PartitionSavedMovePhase.Complete)
    }
    @Synchronized override fun close() {
        worker()
        when(phase) {
            Phase.New,Phase.Unsupported,Phase.Closed -> {phase=Phase.Closed; return}
            Phase.Open -> {
                phase=Phase.Uncertain // Record before actual close; no retry if it throws.
                try {requireNotNull(database).let(closeDatabase); database=null; phase=Phase.Closed}
                catch(failure:Throwable) {throw PartitionOwnershipUncertain(this,failure)}
            }
            else -> throw IOException("Saved Move journal close is active or uncertain; owner retained")
        }
    }
}
