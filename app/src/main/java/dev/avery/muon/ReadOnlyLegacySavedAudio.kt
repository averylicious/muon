@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException

/** Prepared transition reader, not production-selected. The app owner must close its old native
 * cache, keep this READ-ONLY database alive, and route EVERY writer/removal/migration through this
 * same barrier. Closed-folder observation alone grants no exclusion. No upstream/sink/native cache,
 * mutation, completion or source-deletion authority. Projection is bounded per resource; scanning
 * files is still potentially slow and must run off-main with the caller's cancellation/deadline.
 */
internal class ReadOnlyLegacySavedAudio(private val directory:File,private val uid:Long,
    private val index:SQLiteDatabase,barrier:SavedStorageBarrier,private val checkpoint:()->Unit,
    private val files:DataSource.Factory?=null):SavedAudio {
    private val table=DatabaseProvider.TABLE_PREFIX+"CacheIndex"+java.lang.Long.toHexString(uid)
    private fun checked()=LegacyResourceProjection.validate(directory,uid,index,checkpoint)
    private fun validateKey(value:String) {
        if(value.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Legacy key exceeds read budget")
    }
    private val bounded=BarrierSavedAudio(object:SavedAudio {
        override val source=DataSource.Factory { Reader() }
        override fun inspect(key:String):SavedAudioState =
            savedAudioState(LegacyResourceProjection.read(directory,uid,key,index,checkpoint),key)
        override fun contains(key:String):Boolean {
            validateKey(key); val live=checked()
            return index.rawQuery("SELECT 1 FROM $table WHERE key=? LIMIT 2",arrayOf(key)).use { c ->
                live(); val found=c.moveToFirst()
                if(found && c.moveToNext()) throw IOException("Legacy key is not unique")
                live(); found
            }
        }
        override fun forEachKey(visit:(String)->Boolean) {
            val live=checked(); var after=-1L
            index.rawQuery("SELECT 1 FROM $table WHERE id<0 OR id>2147483647 LIMIT 1",null).use {
                if(it.moveToFirst()) throw IOException("Unsupported legacy inventory ID")
            }
            // CursorWindow holds only <=32 bounded scalars/keys, never metadata blobs or all rows.
            while(true) {
                var rows=0
                index.rawQuery("SELECT CASE WHEN typeof(id)='integer' THEN id ELSE NULL END,"+
                    "CASE WHEN typeof(key)='text' AND length(CAST(key AS BLOB))<=${MIGRATION_KEY_BYTES*2} THEN key ELSE NULL END "+
                    "FROM $table WHERE id>? ORDER BY id LIMIT 32",arrayOf(after.toString())).use { c ->
                    while(c.moveToNext()) {
                        live()
                        if(c.getType(0)!=Cursor.FIELD_TYPE_INTEGER || c.getLong(0) !in 0L..Int.MAX_VALUE.toLong() ||
                            c.getLong(0)<=after || c.getType(1)!=Cursor.FIELD_TYPE_STRING)
                            throw IOException("Legacy inventory identity exceeds read budget")
                        val value=c.getString(1); validateKey(value); after=c.getLong(0); rows++
                        if(!visit(value)) { live(); return }
                        live()
                    }
                }
                if(rows<32) return
            }
        }
    },barrier)
    override val source:DataSource.Factory get()=bounded.source
    override fun contains(key:String)=bounded.contains(key)
    override fun inspect(key:String)=bounded.inspect(key)
    override fun forEachKey(visit:(String)->Boolean)=bounded.forEachKey(visit)

    /** BarrierSavedAudio serializes operations, caps listeners and retains this exact child if close
     * is unknown. EOF still owns its permit. Even projection failure retains unknown directory handles.
     */
    private inner class Reader:DataSource {
        private var child:DataSource?=null
        private var uncertainty:MigrationIoUncertain?=null
        private val listeners=arrayOfNulls<TransferListener>(4)
        override fun addTransferListener(listener:TransferListener) {
            if(listeners.any { it===listener }) return
            val slot=listeners.indexOfFirst { it==null }; check(slot>=0) { "Legacy listener budget full" }
            child?.addTransferListener(listener); listeners[slot]=listener
        }
        override fun open(spec:DataSpec):Long {
            check(child==null && uncertainty==null) { "Legacy reader already active or uncertain" }
            val name=spec.key ?: throw IOException("Saved legacy read requires exact key")
            val projection=try { LegacyResourceProjection.read(directory,uid,name,index,checkpoint) }
                catch(failure:MigrationIoUncertain) { uncertainty=failure; throw failure }
            val opened=savedCacheReader(projection,files); child=opened
            for(listener in listeners) if(listener!=null) opened.addTransferListener(listener)
            checkpoint(); return opened.open(spec).also { checkpoint() }
        }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            checkpoint(); return requireNotNull(child).read(buffer,offset,length).also { checkpoint() }
        }
        override fun getUri():Uri?=child?.uri
        override fun getResponseHeaders():Map<String,List<String>> = child?.responseHeaders.orEmpty()
        override fun close() {
            uncertainty?.let { throw it }
            child?.close(); child=null
        }
    }
}
