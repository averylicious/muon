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
internal class PartitionCompletionIndex(private val provider:DatabaseProvider,private val name:String) {
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
        db.rawQuery("SELECT CASE WHEN typeof(version)='integer' THEN version ELSE -1 END FROM $versions WHERE feature=0 AND instance_uid=? LIMIT 2",
            arrayOf(name)).use {
            if(!it.moveToFirst() || it.getLong(0)!=3L || it.moveToNext()) throw IOException("Unsupported completed-save index version")
        }
        // Don't fetch arbitrary schema SQL/default text or an unbounded PRAGMA list into Java.
        db.rawQuery("SELECT cid,CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=32 THEN name ELSE NULL END,"+
            "CASE WHEN typeof(type)='text' AND length(CAST(type AS BLOB))<=8 THEN type ELSE NULL END,\"notnull\",pk "+
            "FROM pragma_table_info(?) ORDER BY cid LIMIT 16",arrayOf(table)).use { c ->
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
    /** Existing legacy row IDs are observed without projecting their raw payloads/URI/metadata. */
    @Synchronized fun containsId(key:String,completedOnly:Boolean=false):Boolean {
        if(key.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Save ID exceeds supported budget")
        try {
            val db=provider.readableDatabase; schema(db)
            return db.rawQuery("SELECT 1 FROM $table WHERE id=?"+
                (if(completedOnly) " AND typeof(state)='integer' AND state=${Download.STATE_COMPLETED}" else "")+" LIMIT 2",arrayOf(key)).use { cursor ->
                val found=cursor.moveToFirst()
                if(found && cursor.moveToNext()) throw IOException("Save ID is not unique")
                found
            }
        } catch(failure:SQLiteException) { throw IOException("Save ID census unavailable",failure) }
    }
    @Synchronized fun find(key:String):PartitionSaveCompletion? {
        if(key.length.toLong()*2>MIGRATION_KEY_BYTES || !key.startsWith(NEW_SAVE_PREFIX))
            throw IOException("Completed-save lookup key exceeds its supported budget")
        try {
            val db=provider.readableDatabase; schema(db)
            return db.rawQuery("SELECT $projection FROM $table WHERE id=? AND state=${Download.STATE_COMPLETED} LIMIT 2",
                arrayOf(key)).use { c ->
                if(!c.moveToFirst()) return@use null
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
                val complete=PartitionSaveCompletion.from(Download(request,c.getInt(7),c.getLong(8),c.getLong(9),
                    c.getLong(10),c.getInt(11),c.getInt(12)))
                if(c.moveToNext()) throw IOException("Completed-save identity is not unique")
                complete
            }
        } catch(failure:SQLiteException) { throw IOException("Completed-save index lookup unavailable",failure) }
    }
}
