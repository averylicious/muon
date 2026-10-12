package dev.avery.muon

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

internal const val LEGACY_RETIREMENT_PAGE=2
internal enum class LegacyRetirementPhase { Pending, Complete }
internal data class LegacyRetirementRecord(val token:String,val phase:LegacyRetirementPhase,
    val manifest:LegacyRetirementManifest)

/** Durable explicit retirement intent / route tombstone, NOT cleanup or deletion authority.
 * Register this owner BEFORE open(). No constructor I/O, automatic repair/replay or native-index
 * mutation. A future controller must hold actual exclusive source/volume ownership, validate the
 * exact completed download/aliases and manifest, and only finish after byte/index disposition.
 * Unknown DB write/close retains this object and its exact connection; caller must quarantine its
 * storage gate. Complete records still reserve their original name and suppress legacy fallback.
 */
internal class LegacyRetirementJournal(private val root:File,private val create:Boolean,
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
        if(ownedIo!=null) throw IOException("Retirement journal I/O is still owned")
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
    private val schema="CREATE TABLE retirements(key BLOB PRIMARY KEY NOT NULL,token TEXT UNIQUE NOT NULL,phase INTEGER NOT NULL,manifest BLOB NOT NULL)"
    private val projection="CASE WHEN typeof(key)='blob' AND length(key)<=$MIGRATION_KEY_BYTES THEN key ELSE NULL END,"+
        "CASE WHEN typeof(token)='text' AND length(CAST(token AS BLOB))=36 THEN token ELSE NULL END,"+
        "CASE WHEN typeof(phase)='integer' THEN phase ELSE NULL END,"+
        "CASE WHEN typeof(manifest)='blob' AND length(manifest)<=$LEGACY_RETIREMENT_MANIFEST_BYTES THEN manifest ELSE NULL END"
    private fun worker() {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Retirement journal requires a worker"}
    }
    private fun db(offMain:Boolean=true):SQLiteDatabase {
        if(offMain) worker()
        if(phase!=Phase.Open) throw IOException("Retirement journal is not open or its ownership is uncertain")
        return requireNotNull(database)
    }
    private fun endTransaction(db:SQLiteDatabase) {
        if(ownedIo!=null) {phase=Phase.Uncertain; return} // Never roll back over an unknown live statement.
        try {db.endTransaction()} catch(failure:Throwable) {phase=Phase.Uncertain; throw PartitionOwnershipUncertain(this,failure)}
    }
    @Synchronized fun open() {
        worker()
        if(phase==Phase.Open) return
        if(phase!=Phase.New) throw IOException("Retirement journal startup cannot be retried")
        phase=Phase.Opening
        try {
            if(!root.isDirectory || root.absoluteFile!=root.canonicalFile || Files.isSymbolicLink(root.toPath()))
                throw IOException("Retirement journal root differs")
            val file=File(root,"legacy-retirements-v1.db")
            if(Files.isSymbolicLink(file.toPath()) || file.absoluteFile!=file.canonicalFile ||
                (create && file.exists()) || (!create && !file.isFile))
                throw IOException("Retirement journal is missing, changed or not fresh")
            val opened=openDatabase(file,create); database=opened // Actual owner retained before schema I/O.
            opened.execSQL("PRAGMA cache_size=-256"); opened.execSQL("PRAGMA synchronous=FULL")
            beginTransaction(opened)
            try {
                if(create) {
                    if(opened.version!=0) throw IOException("Fresh retirement journal version differs")
                    useOwned({opened.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND name!='android_metadata'",null)}) {
                        if(!it.moveToFirst() || it.getLong(0)!=0L) throw IOException("Fresh retirement journal has foreign objects")
                    }
                    opened.execSQL(schema); opened.version=1
                } else {
                    if(opened.version!=1) throw IOException("Unsupported retirement journal version")
                    useOwned({opened.rawQuery("SELECT CASE WHEN type='table' AND name='retirements' AND length(CAST(sql AS BLOB))<=1024 THEN sql ELSE NULL END "+
                        "FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND NOT(type='table' AND name='android_metadata') LIMIT 2",null)}) {
                        if(!it.moveToFirst() || it.getType(0)!=Cursor.FIELD_TYPE_STRING || it.getString(0)!=schema || it.moveToNext())
                            throw IOException("Unsupported retirement journal schema")
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
        if(value.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Retirement key exceeds budget")
    }
    private fun uuid(value:String) {
        val parsed=try {UUID.fromString(value)} catch(_:IllegalArgumentException) {throw IOException("Retirement token malformed")}
        if(parsed.toString()!=value) throw IOException("Retirement token is not canonical")
    }
    private fun decode(c:Cursor):LegacyRetirementRecord {
        if(c.getType(0)!=Cursor.FIELD_TYPE_BLOB || c.getType(1)!=Cursor.FIELD_TYPE_STRING ||
            c.getType(2)!=Cursor.FIELD_TYPE_INTEGER || c.getType(3)!=Cursor.FIELD_TYPE_BLOB)
            throw IOException("Retirement row exceeds its type/budget")
        val name=savedCatalogText(c.getBlob(0)); key(name)
        val token=c.getString(1); uuid(token); val state=c.getLong(2)
        if(state !in 0L until LegacyRetirementPhase.entries.size.toLong()) throw IOException("Unknown retirement state")
        val manifest=LegacyRetirementManifest.decode(c.getBlob(3))
        if(manifest.key!=name) throw IOException("Retirement manifest belongs to another key")
        return LegacyRetirementRecord(token,LegacyRetirementPhase.entries[state.toInt()],manifest)
    }
    private fun lookup(db:SQLiteDatabase,name:String):LegacyRetirementRecord?=useOwned({db.rawQueryWithFactory({_,driver,table,query ->
        query.bindBlob(1,savedCatalogSortKey(name)); SQLiteCursor(driver,table,query)
    },"SELECT $projection FROM retirements WHERE key=? LIMIT 2",null,"retirements")}) {
        if(!it.moveToFirst()) null else decode(it).also {_ -> if(it.moveToNext()) throw IOException("Retirement key is not unique")}
    }
    @Synchronized fun find(name:String):LegacyRetirementRecord? {key(name); return lookup(db(),name)}
    /** Bounded scalar route/name lookup; caller owns dispatch. It never decodes manifest payloads.
     * Complete tombstones reserve names; malformed row names make the result unknown. */
    @Synchronized fun holdsName(name:String):Boolean {
        key(name)
        return useOwned({db(false).rawQueryWithFactory({_,driver,table,query ->
            query.bindBlob(1,savedCatalogSortKey(name)); SQLiteCursor(driver,table,query)
        },"SELECT CASE WHEN typeof(key)='blob' AND length(key)<=$MIGRATION_KEY_BYTES AND length(key)%2=0 THEN 1 ELSE 0 END FROM retirements "+
            "WHERE key=? OR typeof(key)!='blob' OR length(key)>$MIGRATION_KEY_BYTES OR length(key)%2!=0 LIMIT 1",null,"retirements")}) {
            if(!it.moveToFirst()) false else if(it.getLong(0)==1L) true else throw IOException("Retirement name census malformed")
        }
    }
    /** Two manifests per page, projected before CursorWindow. No whole journal or file history list. */
    @Synchronized fun page(after:String?=null):List<LegacyRetirementRecord> {
        after?.let(::key)
        val query="SELECT $projection FROM retirements"+(if(after==null) "" else " WHERE key>?")+" ORDER BY key LIMIT $LEGACY_RETIREMENT_PAGE"
        return useOwned({db().rawQueryWithFactory({_,driver,table,sql ->
            if(after!=null) sql.bindBlob(1,savedCatalogSortKey(after)); SQLiteCursor(driver,table,sql)
        },query,null,"retirements")}) {cursor -> buildList {while(cursor.moveToNext()) add(decode(cursor))}}
    }
    /** Explicit intent must be persisted BEFORE physical mutation. No replacement/adoption of an
     * existing interrupted or complete intent. A future retry uses the exact current record/owner.
     */
    @Synchronized fun begin(manifest:LegacyRetirementManifest,explicitConsent:Boolean):LegacyRetirementRecord {
        val db=db()
        if(!explicitConsent) throw IOException("Original retirement requires explicit consent")
        key(manifest.key); val bytes=manifest.encode(); val token=token().also(::uuid)
        beginTransaction(db); var writing=false
        try {
            if(lookup(db,manifest.key)!=null) throw IOException("Original retirement already has an intent")
            writing=true
            useOwned({db.compileStatement("INSERT INTO retirements VALUES(?,?,?,?)")}) {
                it.bindBlob(1,savedCatalogSortKey(manifest.key)); it.bindString(2,token)
                it.bindLong(3,LegacyRetirementPhase.Pending.ordinal.toLong()); it.bindBlob(4,bytes)
                if(it.executeInsert()==-1L) throw IOException("Retirement intent was not recorded")
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
        return LegacyRetirementRecord(token,LegacyRetirementPhase.Pending,manifest)
    }
    /** Record the controller's final verified disposition, not a substitute for performing it.
     * Exact pending manifest/token must still match. Caller owes physical/index/route/cover checks.
     * A complete record stays as a no-fallback/no-ID-reuse tombstone across restart.
     */
    @Synchronized fun complete(expected:LegacyRetirementRecord):LegacyRetirementRecord {
        val db=db(); key(expected.manifest.key); uuid(expected.token)
        beginTransaction(db); var writing=false
        try {
            if(expected.phase!=LegacyRetirementPhase.Pending || lookup(db,expected.manifest.key)!=expected)
                throw IOException("Retirement completion is stale")
            writing=true
            useOwned({db.compileStatement("UPDATE retirements SET phase=? WHERE key=? AND token=?")}) {
                it.bindLong(1,LegacyRetirementPhase.Complete.ordinal.toLong()); it.bindBlob(2,savedCatalogSortKey(expected.manifest.key))
                it.bindString(3,expected.token); if(it.executeUpdateDelete()!=1) throw IOException("Retirement completion was not recorded")
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
        return expected.copy(phase=LegacyRetirementPhase.Complete)
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
            else -> throw IOException("Retirement journal close is active or uncertain; owner retained")
        }
    }
}
