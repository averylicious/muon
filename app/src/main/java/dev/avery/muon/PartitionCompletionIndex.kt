@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.database.DatabaseProvider
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadProgress
import java.io.IOException

/** Read-only scalar lookup for the pinned Media3 DefaultDownloadIndex v3 layout. Public getDownload
 * first materializes arbitrary persisted data/strings; this adapter projects bounded fields in SQL
 * BEFORE CursorWindow/Java allocation. Unknown/uninitialized schema/version refuses WITHOUT asking
 * Media3 to initialize/migrate/drop it. Upgrade the pinned-source contract and tests together.
 *
 * One cursor/row at a time, at most768KiB data plus bounded text and a transient supported request.
 * Returned completion keeps only a key, scalar state/length/times and fixed digest. This does not
 * authenticate the DB, repair legacy rows, enumerate the library, or bound other manager/index APIs.
 * Provider is borrowed and must remain live under the production ownership/availability barrier.
 */
internal class PartitionCompletionIndex(private val provider:DatabaseProvider,private val name:String,
    private val ownership:MigrationIoOwnership=MigrationIoOwnership()) {
    init { require(name.length<=64 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it=='_' }) }
    private val table=DatabaseProvider.TABLE_PREFIX+"Downloads"+name
    private val versions=DatabaseProvider.TABLE_PREFIX+"Versions"
    // UTF-8 needs at most3 bytes per UTF-16 code unit. Validate the actual UTF-16 budget again below.
    private val textBytes=MIGRATION_KEY_BYTES.toLong()/2*3
    private fun text(column:String,nullable:Boolean=false)=
        "(typeof($column)='text' AND length(CAST($column AS BLOB))<=$textBytes)"+
            if(nullable) " OR $column IS NULL" else ""
    private fun blob(column:String,max:Long)="typeof($column)='blob' AND length($column)<=$max"
    private val fields=listOf("id","mime_type","uri","stream_keys","custom_cache_key","data","state",
        "start_time_ms","update_time_ms","content_length","stop_reason","failure_reason","percent_downloaded",
        "bytes_downloaded","key_set_id")
    private val types=listOf("TEXT","TEXT","TEXT","TEXT","TEXT","BLOB","INTEGER","INTEGER","INTEGER",
        "INTEGER","INTEGER","INTEGER","REAL","INTEGER","BLOB")
    private val predicates=listOf(text("id"),text("mime_type",true),text("uri"),
        "typeof(stream_keys)='text' AND length(CAST(stream_keys AS BLOB))=0",text("custom_cache_key"),
        blob("data",MOVE_COMMAND_BYTES),"typeof(state)='integer'", "typeof(start_time_ms)='integer'",
        "typeof(update_time_ms)='integer'","typeof(content_length)='integer'", "typeof(stop_reason)='integer'",
        "typeof(failure_reason)='integer'", "typeof(key_set_id)='blob' AND length(key_set_id)=0")
    private val projected=listOf("id","mime_type","uri","stream_keys","custom_cache_key","data","state",
        "start_time_ms","update_time_ms","content_length","stop_reason","failure_reason","key_set_id")
    private val projection="CASE WHEN "+predicates.joinToString(" AND ") { "($it)" }+" THEN 1 ELSE 0 END,"+
        projected.indices.joinToString(",") { i -> "CASE WHEN (${predicates[i]}) THEN ${projected[i]} ELSE NULL END" }
    private fun schema(db:SQLiteDatabase) {
        ownership.useOwned({db.rawQuery("SELECT CASE WHEN typeof(version)='integer' THEN version ELSE -1 END FROM $versions WHERE feature=0 AND instance_uid=? LIMIT 2",
            arrayOf(name))}) {
            if(!it.moveToFirst() || it.getLong(0)!=3L || it.moveToNext()) throw IOException("Unsupported completed-save index version")
        }
        // Don't fetch arbitrary schema SQL/default text or an unbounded PRAGMA list into Java.
        ownership.useOwned({db.rawQuery("SELECT cid,CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=32 THEN name ELSE NULL END,"+
            "CASE WHEN typeof(type)='text' AND length(CAST(type AS BLOB))<=8 THEN type ELSE NULL END,\"notnull\",pk "+
            "FROM pragma_table_info(?) ORDER BY cid LIMIT 16",arrayOf(table))}) { c ->
            var i=0
            while(c.moveToNext()) {
                if(i>=fields.size || c.getLong(0)!=i.toLong() || c.getType(1)!=Cursor.FIELD_TYPE_STRING ||
                    c.getString(1)!=fields[i] || c.getType(2)!=Cursor.FIELD_TYPE_STRING || c.getString(2)!=types[i] ||
                    c.getLong(3)!=(if(i==1 || i==4) 0L else 1L) || c.getLong(4)!=(if(i==0) 1L else 0L))
                    throw IOException("Unsupported completed-save index layout")
                i++
            }
            if(i!=fields.size) throw IOException("Completed-save index layout is missing")
        }
    }
    /** Pre-manager fresh-ID refusal must not hydrate a possibly enormous retained request. Any
     * state counts, with no initialization/migration and no data/string projection except input ID.
     */
    @Synchronized fun holdsId(key:String):Boolean {
        if(!key.startsWith(NEW_SAVE_PREFIX)) throw IOException("Fresh save ID is unsupported")
        return containsId(key)
    }
    /** An absent optional shelf's index can have never existed. Only a genuinely absent table
     * AND absent version receipt mean no claim; a view, orphaned version or malformed layout is
     * uncertainty. No DefaultDownloadIndex initialization/migration or payload projection occurs.
     */
    private fun present(db:SQLiteDatabase):Boolean {
        fun tablePresent(name:String):Boolean=ownership.useOwned({db.rawQuery(
            "SELECT CASE WHEN type='table' THEN 1 ELSE 0 END FROM sqlite_master WHERE name=? LIMIT 2",arrayOf(name))}) {
            if(!it.moveToFirst()) false else {
                if(it.getInt(0)!=1 || it.moveToNext()) throw IOException("Optional save index identity is unsupported")
                true
            }
        }
        val indexPresent=tablePresent(table)
        val versionPresent=tablePresent(versions)
        if(indexPresent) {
            if(!versionPresent) throw IOException("Saved index table has no version table")
            return true
        }
        if(versionPresent) ownership.useOwned({db.rawQuery("SELECT 1 FROM $versions WHERE feature=0 AND instance_uid=? LIMIT 1",arrayOf(name))}) {
            if(it.moveToFirst()) throw IOException("Optional save index version exists without its table")
        }
        return false
    }
    /** Before DefaultDownloadIndex can lazily initialize: the pinned implementation drops an
     * unknown-version table (and hydrates every v2 row). Only a genuinely new index may initialize;
     * existing indices require the supported v3 layout. No repair, conversion or record deletion.
     */
    @Synchronized fun requireSupportedOrAbsent() {
        try { val db=provider.readableDatabase; if(present(db)) schema(db) }
        catch(failure:SQLiteException) { throw IOException("Saved index startup schema cannot be established",failure) }
    }
    @Synchronized fun holdsNameIfPresent(key:String):Boolean {
        if(key.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Save name exceeds supported budget")
        try { return if(present(provider.readableDatabase)) holdsName(key) else false }
        catch(failure:SQLiteException) { throw IOException("Optional save index cannot be established",failure) }
    }
    /** Initial opt-in must not silently drop an unavailable card's records. Any state counts;
     * observe a single scalar without hydrating request data or initializing an absent index. */
    @Synchronized fun hasRowsIfPresent():Boolean {
        try {
            val db=provider.readableDatabase
            if(!present(db)) return false
            schema(db)
            return ownership.useOwned({db.rawQuery("SELECT 1 FROM $table LIMIT 1",null)}) {it.moveToFirst()}
        } catch(failure:SQLiteException) {throw IOException("Saved index row census unavailable",failure)}
    }
    /** Existing legacy row IDs are observed without projecting their raw payloads/URI/metadata. */
    @Synchronized fun containsId(key:String,completedOnly:Boolean=false):Boolean {
        if(key.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Save ID exceeds supported budget")
        try {
            val db=provider.readableDatabase; schema(db)
            return ownership.useOwned({db.rawQuery("SELECT 1 FROM $table WHERE id=?"+
                (if(completedOnly) " AND typeof(state)='integer' AND state=${Download.STATE_COMPLETED}" else "")+" LIMIT 2",arrayOf(key))}) { cursor ->
                val found=cursor.moveToFirst()
                if(found && cursor.moveToNext()) throw IOException("Save ID is not unique")
                found
            }
        } catch(failure:SQLiteException) { throw IOException("Save ID census unavailable",failure) }
    }
    /** Candidate-name ownership, including custom keys and URI fallback, in EVERY row state.
     * Played keys are not download IDs. Observe only a scalar before CursorWindow allocation;
     * malformed name types make the census unavailable rather than granting fresh ownership.
     */
    @Synchronized fun holdsName(key:String):Boolean {
        if(key.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Storage name exceeds supported budget")
        val invalid="typeof(id)!='text' OR typeof(custom_cache_key) NOT IN ('text','null') OR " +
            "(custom_cache_key IS NULL AND typeof(uri)!='text')"
        try {
            val db=provider.readableDatabase; schema(db)
            return ownership.useOwned({db.rawQuery("SELECT CASE WHEN ($invalid) THEN 0 ELSE 1 END FROM $table WHERE "+
                "id=? OR custom_cache_key=? OR (custom_cache_key IS NULL AND uri=?) OR ($invalid) LIMIT 1",
                arrayOf(key,key,key))}) { cursor ->
                if(!cursor.moveToFirst()) false
                else if(cursor.getLong(0)==1L) true
                else throw IOException("Storage name census has unsupported row types")
            }
        } catch(failure:SQLiteException) { throw IOException("Storage name census unavailable",failure) }
    }
    /** Before removing an exact completed new-save row, exclude every OTHER row's alias without
     * hydrating its payload. Malformed aliases refuse; excluding the expected ID is safe only
     * together with find(key)'s exact progressive request/state identity, under exclusive admission.
     */
    @Synchronized fun holdsOtherName(key:String):Boolean=otherName(key,true)
    @Synchronized fun holdsOtherOriginalName(key:String):Boolean=otherName(key,false)
    private fun otherName(key:String,requireFresh:Boolean):Boolean {
        if(key.isEmpty() || key.startsWith(PLAYED_PREFIX) || key.length.toLong()*2>MIGRATION_KEY_BYTES || (requireFresh && !key.startsWith(NEW_SAVE_PREFIX)))
            throw IOException("Completed removal name exceeds its budget")
        val invalid="typeof(id)!='text' OR typeof(custom_cache_key) NOT IN ('text','null') OR " +
            "(custom_cache_key IS NULL AND typeof(uri)!='text')"
        try {
            val db=provider.readableDatabase; schema(db)
            return ownership.useOwned({db.rawQuery("SELECT 1 FROM $table WHERE ($invalid) OR (id!=? AND "+
                "(custom_cache_key=? OR (custom_cache_key IS NULL AND uri=?))) LIMIT 1",
                arrayOf(key,key,key))}) { it.moveToFirst() }
        } catch(failure:SQLiteException) { throw IOException("Completed removal alias census unavailable",failure) }
    }
    /** Stable retained originals only. Scope this observation under the real quiet-manager and
     * storage gate; a scalar identity is NOT permission to delete, resume or move partial audio. */
    @Synchronized fun findRetainedOriginal(key:String):PartitionRetainedOriginal? {
        if(key.isEmpty() || key.startsWith(PLAYED_PREFIX) || key.length.toLong()*2>MIGRATION_KEY_BYTES)
            throw IOException("Retained original lookup key exceeds its supported budget")
        val progressProjection="CASE WHEN typeof(percent_downloaded) IN ('real','integer') THEN percent_downloaded ELSE NULL END,"+
            "CASE WHEN typeof(bytes_downloaded)='integer' AND bytes_downloaded>=0 THEN bytes_downloaded ELSE NULL END"
        try {
            val db=provider.readableDatabase; schema(db)
            return ownership.useOwned({db.rawQuery("SELECT $projection,$progressProjection FROM $table WHERE id=? AND "+
                "state IN (${Download.STATE_STOPPED},${Download.STATE_FAILED}) LIMIT 2",arrayOf(key))}) { c ->
                if(!c.moveToFirst()) return@useOwned null
                if(c.getLong(0)!=1L || c.isNull(14) || c.isNull(15))
                    throw IOException("Retained original row exceeds its supported types or budgets")
                val id=c.getString(1)
                if(id!=key) throw IOException("Retained original lookup identity differs")
                for(column in listOf(11,12)) if(c.getLong(column) !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                    throw IOException("Retained original stop/failure state overflows")
                val state=c.getInt(7); val stop=c.getInt(11); val failure=c.getInt(12)
                if(state==Download.STATE_STOPPED && failure!=Download.FAILURE_REASON_NONE ||
                    state==Download.STATE_FAILED && failure==Download.FAILURE_REASON_NONE)
                    throw IOException("Retained original failure state differs")
                val percent=c.getDouble(14)
                if(!percent.isFinite() || percent !in -1.0..100.0)
                    throw IOException("Retained original progress differs")
                val uri=Uri.parse(c.getString(3)); val mime=c.getString(2); val customKey=c.getString(5)
                if(customKey!=key || Util.inferContentTypeForUriAndMimeType(uri,mime)!=C.CONTENT_TYPE_OTHER)
                    throw IOException("Retained original is not the exact full progressive request")
                val request=DownloadRequest.Builder(id,uri).setMimeType(mime)
                    .setCustomCacheKey(customKey).setData(c.getBlob(6)).build()
                val progress=DownloadProgress().apply {bytesDownloaded=c.getLong(15); percentDownloaded=percent.toFloat()}
                val download=Download(request,state,c.getLong(8),c.getLong(9),c.getLong(10),stop,failure,progress)
                val result=PartitionRetainedOriginal.from(download)
                    ?: throw IOException("Retained original is not stopped or failed")
                if(c.moveToNext()) throw IOException("Retained original identity is not unique")
                result
            }
        } catch(failure:SQLiteException) {throw IOException("Retained original index lookup unavailable",failure)}
    }
    @Synchronized fun find(key:String):PartitionSaveCompletion?=completed(key,true) {PartitionSaveCompletion.from(it)}
    @Synchronized fun findOriginal(key:String):PartitionSaveCompletion?=completed(key,false) {PartitionSaveCompletion.fromOriginal(it)}
    /** One bounded full request, scoped to a local-copy publication. Caller must hold the actual
     * source/destination exclusive gate; returned payload must not enter a batch/history/queue. */
    @Synchronized fun completedRequest(key:String,expected:PartitionSaveCompletion):DownloadRequest?=completed(key,false) {
        if(PartitionSaveCompletion.fromOriginal(it)!=expected) throw IOException("Completed request changed since preparation")
        it.request
    }
    private fun <T> completed(key:String,requireFresh:Boolean,project:(Download)->T):T? {
        if(key.isEmpty() || key.startsWith(PLAYED_PREFIX) || key.length.toLong()*2>MIGRATION_KEY_BYTES || (requireFresh && !key.startsWith(NEW_SAVE_PREFIX)))
            throw IOException("Completed-save lookup key exceeds its supported budget")
        try {
            val db=provider.readableDatabase; schema(db)
            return ownership.useOwned({db.rawQuery("SELECT $projection FROM $table WHERE id=? AND state=${Download.STATE_COMPLETED} LIMIT 2",
                arrayOf(key))}) { c ->
                if(!c.moveToFirst()) return@useOwned null
                if(c.getLong(0)!=1L) throw IOException("Completed-save row exceeds its supported types or budgets")
                val id=c.getString(1)
                if(id!=key) throw IOException("Completed-save lookup identity differs")
                if(c.getLong(11)!=0L || c.getLong(12)!=0L)
                    throw IOException("Completed-save row has invalid stop/failure state")
                val uri=Uri.parse(c.getString(3)); val mime=c.getString(2); val customKey=c.getString(5)
                // Reject before DownloadRequest's constructor can throw on adaptive custom keys.
                if(customKey!=key || Util.inferContentTypeForUriAndMimeType(uri,mime)!=C.CONTENT_TYPE_OTHER)
                    throw IOException("Completed-save row is not the exact full progressive request")
                val request=DownloadRequest.Builder(id,uri).setMimeType(mime)
                    .setCustomCacheKey(customKey).setData(c.getBlob(6)).build()
                val download=Download(request,c.getInt(7),c.getLong(8),c.getLong(9),c.getLong(10),c.getInt(11),c.getInt(12))
                if((if(requireFresh) PartitionSaveCompletion.from(download) else PartitionSaveCompletion.fromOriginal(download))==null)
                    throw IOException("Completed-save identity is not complete")
                if(c.moveToNext()) throw IOException("Completed-save identity is not unique")
                project(download)
            }
        } catch(failure:SQLiteException) { throw IOException("Completed-save index lookup unavailable",failure) }
    }
}
