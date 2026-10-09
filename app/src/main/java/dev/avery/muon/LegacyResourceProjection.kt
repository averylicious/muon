@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.VersionTable
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.NavigableSet
import java.util.TreeSet

internal const val LEGACY_PROJECTION_METADATA_BYTES=512*1024

/** Prepared read-only single-resource transition route for pinned Media3 database/v3 spans. It NEVER
 * constructs SimpleCache, initializes/repairs native indexes, writes/touches/deletes originals or
 * enumerates all native records into Java. Caller supplies a READ-ONLY DB connection and holds the
 * closed legacy cache's real writer/availability/lifetime barrier throughout projection AND use.
 * This observer cannot provide that lock or adopt a live/unknown format. File traversal is streamed
 * (possibly expensive for a large cache); only <=256 selected spans and <=512KiB metadata survive.
 */
internal object LegacyResourceProjection {
    fun read(directory:File,uid:Long,key:String,index:SQLiteDatabase,checkpoint:()->Unit):Cache {
        if(uid<0 || key.length.toLong()*2>MIGRATION_KEY_BYTES || !index.isReadOnly)
            throw IOException("Legacy projection needs a bounded identity and read-only database")
        val hex=java.lang.Long.toHexString(uid)
        fun live() {
            checkpoint()
            if(!directory.isDirectory || directory.absoluteFile!=directory.canonicalFile ||
                SimpleCache.isCacheFolderLocked(directory)) throw IOException("Legacy source is not closed and exclusively observed")
            val marker=File(directory,"$hex.uid")
            if(!Files.isRegularFile(marker.toPath(),LinkOption.NOFOLLOW_LINKS) || marker.length()!=0L)
                throw IOException("Legacy cache identity unavailable")
        }
        live()
        val content=DatabaseProvider.TABLE_PREFIX+"CacheIndex"+hex
        val files=DatabaseProvider.TABLE_PREFIX+"CacheFileMetadata"+hex
        val versions=DatabaseProvider.TABLE_PREFIX+"Versions"
        for(feature in listOf(VersionTable.FEATURE_CACHE_CONTENT_METADATA,VersionTable.FEATURE_CACHE_FILE_METADATA)) {
            index.rawQuery("SELECT CASE WHEN typeof(version)='integer' THEN version ELSE NULL END FROM $versions WHERE feature=? AND instance_uid=? LIMIT 2",arrayOf(feature.toString(),hex)).use { c ->
                if(!c.moveToFirst() || c.getType(0)!=Cursor.FIELD_TYPE_INTEGER || c.getLong(0)!=1L || c.moveToNext())
                    throw IOException("Unsupported legacy cache version")
            }
        }
        val contentSql="CREATE TABLE $content (id INTEGER PRIMARY KEY NOT NULL,key TEXT NOT NULL,metadata BLOB NOT NULL)"
        val filesSql="CREATE TABLE $files (name TEXT PRIMARY KEY NOT NULL,length INTEGER NOT NULL,last_touch_timestamp INTEGER NOT NULL)"
        var tables=0; var objects=0
        index.rawQuery("SELECT CASE WHEN length(CAST(type AS BLOB))<=8 THEN type ELSE NULL END,"+
            "CASE WHEN length(CAST(name AS BLOB))<=128 THEN name ELSE NULL END,"+
            "CASE WHEN length(CAST(sql AS BLOB))<=1024 THEN sql ELSE NULL END FROM sqlite_master WHERE tbl_name IN (?,?) LIMIT 5",
            arrayOf(content,files)).use { c ->
            while(c.moveToNext()) {
                live(); if(++objects>3) throw IOException("Unknown legacy cache objects")
                when {
                    c.getString(0)=="table" && c.getString(1)==content && c.getString(2)==contentSql -> tables++
                    c.getString(0)=="table" && c.getString(1)==files && c.getString(2)==filesSql -> tables++
                    c.getString(0)=="index" && c.getString(1)=="sqlite_autoindex_${files}_1" && c.isNull(2) -> Unit
                    else -> throw IOException("Unknown legacy cache schema")
                }
            }
        }
        if(tables!=2) throw IOException("Legacy native tables missing")
        var contentId=-1L; var metadata:DefaultContentMetadata?=null
        index.rawQuery("SELECT id,CASE WHEN typeof(key)='text' AND length(CAST(key AS BLOB))<=${MIGRATION_KEY_BYTES*2} THEN key ELSE NULL END,"+
            "CASE WHEN typeof(metadata)='blob' AND length(metadata)<=$LEGACY_PROJECTION_METADATA_BYTES THEN metadata ELSE NULL END FROM $content WHERE key=? LIMIT 2",
            arrayOf(key)).use { c ->
            if(!c.moveToFirst() || c.getType(0)!=Cursor.FIELD_TYPE_INTEGER || c.getLong(0) !in 0L..Int.MAX_VALUE.toLong() ||
                c.getType(1)!=Cursor.FIELD_TYPE_STRING || c.getString(1)!=key || c.getType(2)!=Cursor.FIELD_TYPE_BLOB)
                throw IOException("Legacy resource absent or exceeds projection budget")
            contentId=c.getLong(0); metadata=decode(c.getBlob(2))
            if(c.moveToNext()) throw IOException("Legacy key is not unique")
        }
        val spans=ArrayList<CacheSpan>(); var children=0; var marker=false
        Files.newDirectoryStream(directory.toPath()).use { entries -> for(path in entries) {
            live(); if(++children>11 || Files.isSymbolicLink(path)) throw IOException("Unknown legacy directory layout")
            val name=path.fileName.toString()
            if(name=="$hex.uid" && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) { marker=true; continue }
            if(name.length!=1 || name[0] !in '0'..'9' || !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))
                throw IOException("Unsupported legacy root entry")
            Files.newDirectoryStream(path).use { leaves -> for(leaf in leaves) {
                live(); val filename=leaf.fileName.toString()
                if(filename.length>128 || !Files.isRegularFile(leaf,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(leaf))
                    throw IOException("Unsupported legacy span layout")
                val fields=filename.split('.')
                if(fields.size!=5 || fields[3]!="v3" || fields[4]!="exo") throw IOException("Unsupported legacy span format")
                val id=fields[0].toLongOrNull(); val position=fields[1].toLongOrNull(); val stamp=fields[2].toLongOrNull()
                if(id==null || id !in 0L..Int.MAX_VALUE.toLong() || position==null || position<0 || stamp==null || stamp<0 ||
                    filename!="$id.$position.$stamp.v3.exo") throw IOException("Malformed legacy span identity")
                if(id!=contentId) continue
                if(spans.size>=MIGRATION_RANGES) throw IOException("Legacy resource fragmentation exceeds projection budget")
                val length=leaf.toFile().length()
                if(length<=0 || length>Long.MAX_VALUE-position) throw IOException("Legacy span extent differs")
                val touch=index.rawQuery("SELECT CASE WHEN typeof(length)='integer' THEN length ELSE NULL END,CASE WHEN typeof(last_touch_timestamp)='integer' THEN last_touch_timestamp ELSE NULL END FROM $files WHERE name=? LIMIT 2",arrayOf(filename)).use { c ->
                    if(!c.moveToFirst() || c.getType(0)!=Cursor.FIELD_TYPE_INTEGER || c.getLong(0)!=length ||
                        c.getType(1)!=Cursor.FIELD_TYPE_INTEGER || c.getLong(1)<0) throw IOException("Legacy span index differs")
                    val value=c.getLong(1); if(c.moveToNext()) throw IOException("Legacy span index is not unique"); value
                }
                spans+=CacheSpan(key,position,length,touch,leaf.toFile())
            } }
        } }
        if(!marker) throw IOException("Legacy UID marker missing")
        spans.sortBy { it.position }; var end=0L; var bytes=0L
        for(span in spans) {
            if(span.position<end) throw IOException("Legacy selected spans overlap")
            end=span.position+span.length
            bytes=try { Math.addExact(bytes,span.length) } catch(_:ArithmeticException) { throw IOException("Legacy byte total overflows") }
        }
        // Every indexed selected span must have been found; don't silently publish a truncated view.
        var indexed=0
        index.rawQuery("SELECT CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=128 THEN name ELSE NULL END FROM $files WHERE name GLOB ? LIMIT ${MIGRATION_RANGES+1}",
            arrayOf("$contentId.*")).use { c -> while(c.moveToNext()) {
            live(); if(++indexed>MIGRATION_RANGES || c.getType(0)!=Cursor.FIELD_TYPE_STRING || spans.none { it.file!!.name==c.getString(0) })
                throw IOException("Legacy resource has missing or unsupported indexed spans")
        } }
        if(indexed!=spans.size) throw IOException("Legacy selected span census differs")
        live(); return ProjectedCache(uid,key,requireNotNull(metadata),spans,bytes,::live)
    }
    private fun decode(bytes:ByteArray):DefaultContentMetadata {
        val values=LinkedHashMap<String,ByteArray>()
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val count=input.readInt()
            if(count !in 0..MIGRATION_METADATA_FIELDS) throw IOException("Legacy metadata field budget exceeded")
            repeat(count) {
                val name=input.readUTF(); val length=input.readInt()
                if(name.length.toLong()*2>MIGRATION_KEY_BYTES || length<0 || length>input.available() || values.containsKey(name))
                    throw IOException("Legacy metadata field malformed")
                val value=ByteArray(length); input.readFully(value); values[name]=value
            }
            if(input.read()!=-1) throw IOException("Legacy metadata trailing bytes")
        }
        return DefaultContentMetadata(values)
    }
    private class ProjectedCache(private val nativeUid:Long,private val key:String,private val metadata:DefaultContentMetadata,
        private val spans:List<CacheSpan>,private val bytes:Long,private val checkpoint:()->Unit):Cache {
        private fun check(name:String=key) { checkpoint(); if(name!=key) throw IOException("Projection cannot access another resource") }
        private fun denied():Nothing=throw IOException("Read-only legacy projection cannot mutate or release its borrowed source")
        override fun getUid():Long { check(); return nativeUid }
        override fun release():Unit=denied()
        override fun addListener(key:String,listener:Cache.Listener):NavigableSet<CacheSpan> =denied()
        override fun removeListener(key:String,listener:Cache.Listener):Unit=denied()
        override fun getCachedSpans(key:String):NavigableSet<CacheSpan> { check(key); return TreeSet<CacheSpan>().also { it.addAll(spans) } }
        override fun getKeys():Set<String> { check(); return setOf(key) }
        override fun getCacheSpace():Long { check(); return bytes }
        override fun startReadWrite(key:String,position:Long,length:Long)=startReadWriteNonBlocking(key,position,length)
        override fun startReadWriteNonBlocking(key:String,position:Long,length:Long):CacheSpan {
            check(key); require(position>=0 && (length==-1L || length>0))
            spans.firstOrNull { position>=it.position && position-it.position<it.length }?.let { return it }
            throw IOException("Requested legacy byte is unavailable; no writer hole can be acquired")
        }
        override fun startFile(key:String,position:Long,length:Long):File=denied()
        override fun commitFile(file:File,length:Long):Unit=denied()
        override fun releaseHoleSpan(holeSpan:CacheSpan):Unit=denied()
        override fun removeResource(key:String):Unit=denied()
        override fun removeSpan(span:CacheSpan):Unit=denied()
        override fun isCached(key:String,position:Long,length:Long)=getCachedLength(key,position,length)>=length
        override fun getCachedLength(key:String,position:Long,length:Long):Long {
            check(key); require(position>=0 && length>=0)
            val maximum=minOf(length,Long.MAX_VALUE-position)
            if(maximum==0L) return 0
            var end=position
            for(span in spans) {
                if(span.position>end) return if(end==position) -minOf(maximum,span.position-position) else minOf(maximum,end-position)
                if(span.position+span.length>end) end=span.position+span.length
            }
            return if(end==position) -maximum else minOf(maximum,end-position)
        }
        override fun getCachedBytes(key:String,position:Long,length:Long):Long {
            check(key); require(position>=0 && length>=0)
            val end=position+minOf(length,Long.MAX_VALUE-position); var total=0L
            for(span in spans) total+=maxOf(0L,minOf(end,span.position+span.length)-maxOf(position,span.position))
            return total
        }
        override fun applyContentMetadataMutations(key:String,mutations:ContentMetadataMutations):Unit=denied()
        override fun getContentMetadata(key:String):ContentMetadata { check(key); return metadata }
    }
}
