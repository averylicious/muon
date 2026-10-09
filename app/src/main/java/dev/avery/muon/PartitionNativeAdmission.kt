@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.VersionTable
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

internal data class PartitionNativeEvidence(val bytes:Long,val spans:Int)

/** UNWIRED read-only preflight for a CLOSED, known private single-resource partition, never legacy.
 * Native initialization can drop a mismatched index/delete unrecognized files before post-open guards
 * run. Inspect only the pinned published database/filename format; unknown format refuses, never edits
 * or repairs Media3 schemas. Caller supplies an ALREADY-OPEN owned DB and holds volume/creation/writer
 * exclusion throughout preflight and native open. This is an observation, not that lock or ownership
 * authority. Sidecar identity/ready journal/volume and global native residency are separate checks.
 */
internal object PartitionNativeAdmission {
    private data class SpanFile(val length:Long,val position:Long)
    fun inspect(directory:File,uid:Long,key:String,index:SQLiteDatabase,
        limits:PartitionResourceLimits=PartitionResourceLimits(),checkpoint:()->Unit={}):PartitionNativeEvidence {
        checkpoint()
        if(uid<0 || key.length.toLong()*2>limits.keyBytes || !index.isOpen) throw IOException("Partition identity unavailable")
        if(!directory.isDirectory || directory.canonicalFile!=directory.absoluteFile || SimpleCache.isCacheFolderLocked(directory))
            throw IOException("Partition directory is not a closed exact owner path")
        val hex=java.lang.Long.toHexString(uid)
        val content=DatabaseProvider.TABLE_PREFIX+"CacheIndex"+hex
        val files=DatabaseProvider.TABLE_PREFIX+"CacheFileMetadata"+hex
        if(VersionTable.getVersion(index,VersionTable.FEATURE_CACHE_CONTENT_METADATA,hex)!=1 ||
            VersionTable.getVersion(index,VersionTable.FEATURE_CACHE_FILE_METADATA,hex)!=1)
            throw IOException("Unknown or missing native partition index version")
        // Exact schemas are pinned format checks, not manually managed native migrations.
        val contentSql="CREATE TABLE $content (id INTEGER PRIMARY KEY NOT NULL,key TEXT NOT NULL,metadata BLOB NOT NULL)"
        val filesSql="CREATE TABLE $files (name TEXT PRIMARY KEY NOT NULL,length INTEGER NOT NULL,last_touch_timestamp INTEGER NOT NULL)"
        var tables=0; var objects=0
        index.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE tbl_name IN (?,?) LIMIT 5",arrayOf(content,files)).use { rows ->
            while(rows.moveToNext()) {
                checkpoint(); if(++objects>3) throw IOException("Unknown native partition schema objects")
                val type=rows.getString(0); val name=rows.getString(1)
                when {
                    type=="table" && name==content && rows.getString(2)==contentSql -> tables++
                    type=="table" && name==files && rows.getString(2)==filesSql -> tables++
                    type=="index" && name=="sqlite_autoindex_${files}_1" && rows.isNull(2) -> Unit
                    else -> throw IOException("Unknown native partition schema")
                }
            }
        }
        if(tables!=2) throw IOException("Native partition tables missing")
        var contentId:Long?=null
        // Scalar/CASE gates prevent loading oversized native metadata/keys into a CursorWindow.
        index.rawQuery("SELECT id,CASE WHEN typeof(key)='text' AND length(key)<=8192 THEN key ELSE NULL END,"+
            "CASE WHEN typeof(metadata)='blob' AND length(metadata)=4 THEN metadata ELSE NULL END FROM $content LIMIT 2",null).use { rows ->
            if(rows.moveToFirst()) {
                checkpoint()
                if(rows.getType(0)!=Cursor.FIELD_TYPE_INTEGER || rows.getLong(0) !in 0L..Int.MAX_VALUE.toLong() ||
                    rows.getType(1)!=Cursor.FIELD_TYPE_STRING || rows.getString(1)!=key || rows.getType(2)!=Cursor.FIELD_TYPE_BLOB ||
                    !rows.getBlob(2).contentEquals(ByteArray(4))) throw IOException("Native partition content differs from its owner")
                contentId=rows.getLong(0)
                if(rows.moveToNext()) throw IOException("Native partition has multiple resources")
            }
        }
        val names=LinkedHashMap<String,SpanFile>()
        var total=0L
        index.rawQuery("SELECT CASE WHEN typeof(name)='text' AND length(name)<=128 THEN name ELSE NULL END,length,last_touch_timestamp FROM $files LIMIT ${limits.spans+1}",null).use { rows ->
            while(rows.moveToNext()) {
                checkpoint()
                if(names.size>=limits.spans || rows.getType(0)!=Cursor.FIELD_TYPE_STRING || rows.getType(1)!=Cursor.FIELD_TYPE_INTEGER ||
                    rows.getType(2)!=Cursor.FIELD_TYPE_INTEGER || rows.getLong(1)<=0 || rows.getLong(2)<0)
                    throw IOException("Native partition file metadata exceeds or differs from its budget")
                val name=rows.getString(0); val length=rows.getLong(1)
                val position=validateName(name,contentId,length)
                if(names.put(name,SpanFile(length,position))!=null) throw IOException("Duplicate native span file")
                total=try { Math.addExact(total,length) } catch(_:ArithmeticException) { throw IOException("Native partition byte total overflows") }
            }
        }
        var previousEnd=0L
        for(span in names.values.sortedBy { it.position }) {
            if(span.position<previousEnd) throw IOException("Native partition spans overlap")
            previousEnd=span.position+span.length
        }
        val expected=names.size
        var uidFound=false; var children=0; var spans=0
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for(path in entries) {
                checkpoint()
                if(++children>11 || Files.isSymbolicLink(path)) throw IOException("Native partition folder has unknown entries")
                val file=path.toFile(); val name=file.name
                when {
                    name=="$hex.uid" && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && file.length()==0L -> {
                        if(uidFound) throw IOException("Duplicate native partition UID"); uidFound=true
                    }
                    name.length==1 && name[0] in '0'..'9' && Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) -> {
                        Files.newDirectoryStream(path).use { leaves -> for(leaf in leaves) {
                            checkpoint()
                            if(++spans>limits.spans || !Files.isRegularFile(leaf,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(leaf))
                                throw IOException("Native partition spans exceed or differ from the owned layout")
                            val saved=names.remove(leaf.fileName.toString()) ?: throw IOException("Unindexed or repeated native span")
                            if(leaf.toFile().length()!=saved.length) throw IOException("Native span byte length changed")
                        } }
                    }
                    else -> throw IOException("Native partition UID or folder entry differs")
                }
            }
        }
        if(!uidFound || names.isNotEmpty() || spans!=expected) throw IOException("Native partition files unavailable")
        checkpoint()
        return PartitionNativeEvidence(total,spans)
    }
    private fun validateName(name:String,contentId:Long?,length:Long):Long {
        val fields=name.split('.')
        if(fields.size!=5 || fields[3]!="v3" || fields[4]!="exo") throw IOException("Unknown native span format")
        val id=fields[0].toLongOrNull(); val position=fields[1].toLongOrNull(); val timestamp=fields[2].toLongOrNull()
        if(id==null || id!=contentId || position==null || position<0 || timestamp==null || timestamp<0 || length>Long.MAX_VALUE-position ||
            name!="$id.$position.$timestamp.v3.exo") throw IOException("Native span identity or extent differs")
        return position
    }
}
