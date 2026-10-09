@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.media3.datasource.cache.DefaultContentMetadata
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val METADATA_CHUNK_BYTES=64*1024
private const val METADATA_PAYLOAD_BYTES=MIGRATION_METADATA_BYTES+4+MIGRATION_METADATA_FIELDS*8
private const val METADATA_MAX_CHUNKS=(METADATA_PAYLOAD_BYTES+METADATA_CHUNK_BYTES-1)/METADATA_CHUNK_BYTES

/** Exact bounded metadata for ONE known private partition, outside SimpleCache's scanned byte folder.
 * Own SQLite schema, never Media3's schema. UID/key mismatch or unreadable/corrupt payload refuses;
 * absent metadata is not invented after failure. Transactions preserve the previous complete payload.
 * Returned metadata is a fresh bounded projection, never a lifetime map of the library. */
internal class PartitionContentMetadata(root:File,private val uid:Long,private val key:String,create:Boolean=false,
    private val afterChunk:()->Unit={}) : Closeable {
    private val ownerSchema="CREATE TABLE owner(singleton INTEGER PRIMARY KEY CHECK(singleton=1), uid INTEGER NOT NULL, key BLOB NOT NULL)"
    private val chunkSchema="CREATE TABLE chunks(ordinal INTEGER PRIMARY KEY NOT NULL, value BLOB NOT NULL)"
    private val database:SQLiteDatabase
    init {
        if(uid<0 || key.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Partition metadata identity unavailable")
        val file=File(root,"content-v1.db")
        if(create) {
            // A new allocation is explicit. Never recreate a vanished store as empty on reopen.
            if(root.exists() || !root.mkdirs()) throw IOException("Fresh partition metadata directory unavailable")
            database=SQLiteDatabase.openOrCreateDatabase(file,null)
        } else {
            if(!root.isDirectory || !file.isFile) throw IOException("Partition metadata store missing")
            database=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE)
        }
        try {
            database.execSQL("PRAGMA cache_size=-256"); database.execSQL("PRAGMA synchronous=FULL")
            database.beginTransaction()
            try {
                val schemas=database.rawQuery("SELECT name,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND name!='android_metadata' ORDER BY name",null).use { rows ->
                    val result=ArrayList<Pair<String,String>>(2)
                    while(rows.moveToNext()) { if(result.size==2) throw IOException("Unknown partition metadata schema"); result+=rows.getString(0) to rows.getString(1) }
                    result
                }
                when(database.version) {
                    0 -> {
                        if(!create || schemas.isNotEmpty()) throw IOException("Unknown partition metadata schema")
                        database.execSQL(ownerSchema); database.execSQL(chunkSchema)
                        database.compileStatement("INSERT INTO owner VALUES(1,?,?)").use {
                            it.bindLong(1,uid); it.bindBlob(2,savedCatalogSortKey(key))
                            if(it.executeInsert()!=1L) throw IOException("Partition metadata ownership not recorded")
                        }
                        // Even an empty projection has a complete payload. Missing chunks are corruption.
                        database.execSQL("INSERT INTO chunks VALUES(0,?)",arrayOf(ByteArray(4)))
                        database.version=1
                    }
                    1 -> if(schemas!=listOf("chunks" to chunkSchema,"owner" to ownerSchema)) throw IOException("Unknown partition metadata schema")
                    else -> throw IOException("Unsupported partition metadata version")
                }
                checkOwner(uid,key); database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        } catch(failure:Throwable) { database.close(); throw failure }
    }
    @Synchronized fun checkOwner(nativeUid:Long,name:String) {
        if(nativeUid!=uid || name!=key) throw IOException("Partition metadata belongs to another cache")
        database.rawQuery("SELECT singleton,uid,CASE WHEN typeof(key)='blob' AND length(key)<=$MIGRATION_KEY_BYTES THEN key ELSE NULL END FROM owner LIMIT 2",null).use {
            if(!it.moveToFirst() || it.getType(0)!=Cursor.FIELD_TYPE_INTEGER || it.getLong(0)!=1L ||
                it.getType(1)!=Cursor.FIELD_TYPE_INTEGER || it.getLong(1)!=uid || it.getType(2)!=Cursor.FIELD_TYPE_BLOB ||
                savedCatalogText(it.getBlob(2))!=key || it.moveToNext()) throw IOException("Partition metadata ownership changed")
        }
    }
    @Synchronized fun read():DefaultContentMetadata {
        database.beginTransaction()
        try {
            checkOwner(uid,key)
            // Inspect bounded scalar lengths before projecting any payload into a CursorWindow.
            val size=database.rawQuery("SELECT COUNT(*),COALESCE(SUM(bytes),0) FROM (SELECT length(value) AS bytes FROM chunks ORDER BY ordinal LIMIT ${METADATA_MAX_CHUNKS+1})",null).use {
                if(!it.moveToFirst()) throw IOException("Partition metadata size unavailable")
                val count=it.getLong(0); val bytes=it.getLong(1)
                if(count<=0 || count>METADATA_MAX_CHUNKS || bytes<0 || bytes>METADATA_PAYLOAD_BYTES) throw IOException("Partition metadata exceeds its budget")
                count
            }
            val result=database.rawQuery("SELECT ordinal,CASE WHEN typeof(value)='blob' AND length(value) BETWEEN 1 AND $METADATA_CHUNK_BYTES THEN value ELSE NULL END FROM chunks ORDER BY ordinal LIMIT ${METADATA_MAX_CHUNKS+1}",null).use { rows ->
                val input=DataInputStream(Chunks(rows,size))
                val count=input.readInt()
                if(count !in 0..MIGRATION_METADATA_FIELDS) throw IOException("Partition metadata field budget exceeded")
                val fields=LinkedHashMap<String,ByteArray>(count)
                var remaining=MIGRATION_METADATA_BYTES
                repeat(count) {
                    val nameBytes=input.readInt()
                    if(nameBytes<0 || nameBytes%2!=0 || nameBytes>remaining) throw IOException("Partition metadata name invalid")
                    val encoded=ByteArray(nameBytes); input.readFully(encoded); remaining-=nameBytes
                    val name=savedCatalogText(encoded)
                    val valueBytes=input.readInt()
                    if(valueBytes<0 || valueBytes>remaining || fields.containsKey(name)) throw IOException("Partition metadata value invalid")
                    val value=ByteArray(valueBytes); input.readFully(value); remaining-=valueBytes; fields[name]=value
                }
                if(input.read()!=-1) throw IOException("Partition metadata has trailing bytes")
                DefaultContentMetadata(fields)
            }
            database.setTransactionSuccessful(); return result
        } finally { database.endTransaction() }
    }
    @Synchronized fun write(metadata:DefaultContentMetadata) {
        // DefaultContentMetadata exposes entry values and can wrap a caller-owned map. Take one
        // bounded snapshot before the first SQL mutation, not two traversals of mutable input.
        val fields=LinkedHashMap<String,ByteArray>()
        var bytes=0L
        for((name,value) in metadata.entrySet()) {
            if(fields.size>=MIGRATION_METADATA_FIELDS) throw IOException("Partition metadata fields exceed budget")
            bytes+=name.length.toLong()*2+value.size
            if(bytes>MIGRATION_METADATA_BYTES) throw IOException("Partition metadata bytes exceed budget")
            fields[name]=value.copyOf()
        }
        database.beginTransaction()
        try {
            checkOwner(uid,key)
            database.execSQL("DELETE FROM chunks") // Own derived payload only, atomically replaced; never native audio/index.
            database.compileStatement("INSERT INTO chunks VALUES(?,?)").use { insert ->
                val output=object:OutputStream() {
                    private val chunk=ByteArray(METADATA_CHUNK_BYTES)
                    private var used=0
                    private var ordinal=0
                    override fun write(value:Int) { chunk[used++]=value.toByte(); if(used==chunk.size) flush() }
                    override fun write(value:ByteArray,offset:Int,length:Int) {
                        var at=offset; var left=length
                        while(left>0) { val n=minOf(left,chunk.size-used); value.copyInto(chunk,used,at,at+n); used+=n; at+=n; left-=n; if(used==chunk.size) flush() }
                    }
                    override fun flush() {
                        if(used==0) return
                        insert.bindLong(1,ordinal.toLong()); insert.bindBlob(2,chunk.copyOf(used))
                        if(insert.executeInsert()==-1L) throw IOException("Partition metadata chunk not recorded")
                        ordinal++; used=0; afterChunk()
                    }
                }
                val stream=DataOutputStream(output); stream.writeInt(fields.size)
                for((name,value) in fields) {
                    val encoded=savedCatalogSortKey(name)
                    stream.writeInt(encoded.size); stream.write(encoded); stream.writeInt(value.size); stream.write(value)
                }
                stream.flush()
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    private class Chunks(private val rows:Cursor,private val count:Long):InputStream() {
        private var ordinal=0L
        private var chunk=ByteArray(0)
        private var at=0
        override fun read():Int { if(!next()) return -1; return chunk[at++].toInt() and 255 }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            if(length==0) return 0
            if(!next()) return -1
            val n=minOf(length,chunk.size-at); chunk.copyInto(buffer,offset,at,at+n); at+=n; return n
        }
        private fun next():Boolean {
            if(at<chunk.size) return true
            if(!rows.moveToNext()) { if(ordinal!=count) throw IOException("Partition metadata chunks incomplete"); return false }
            if(rows.getType(0)!=Cursor.FIELD_TYPE_INTEGER || rows.getLong(0)!=ordinal || rows.getType(1)!=Cursor.FIELD_TYPE_BLOB)
                throw IOException("Partition metadata chunk identity invalid")
            chunk=rows.getBlob(1); at=0; ordinal++
            if(chunk.isEmpty() || chunk.size>METADATA_CHUNK_BYTES || (ordinal<count && chunk.size!=METADATA_CHUNK_BYTES))
                throw IOException("Partition metadata chunk length invalid")
            return true
        }
    }
    @Synchronized override fun close()=database.close()
}
