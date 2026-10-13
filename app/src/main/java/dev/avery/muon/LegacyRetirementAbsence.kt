package dev.avery.muon

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Closed-cache absence proof for a stable retained original that never acquired a native key.
 * Stream the supported filesystem and require every actual span to belong to ANOTHER indexed key.
 * Unmapped files, malformed keys/schema or unavailable handles are uncertainty, never absence.
 * Native SQL is read-only, no request/metadata blob/audio is hydrated, and no files are removed. */
internal object LegacyRetirementAbsence {
    fun verify(root:File,uid:Long,key:String,index:SQLiteDatabase,checkpoint:()->Unit,
        ownership:MigrationIoOwnership) {
        if(key.isEmpty() || key.length.toLong()*2>MIGRATION_KEY_BYTES)
            throw IOException("Absent original key exceeds its budget")
        try {
            val live=LegacyResourceProjection.validate(root,uid,index,checkpoint,ownership)
            val hex=java.lang.Long.toHexString(uid);val content="ExoPlayerCacheIndex$hex"
            val metadata="ExoPlayerCacheFileMetadata$hex"
            fun absent() {
                live()
                ownership.useOwned({index.rawQuery("SELECT 1 FROM $content WHERE key=? OR typeof(key)!='text' LIMIT 1",arrayOf(key))}) {
                    if(it.moveToFirst()) throw IOException("Absent original has a native or unsupported key claim")
                }
            }
            absent();var children=0;var marker=false
            ownership.directory(root.toPath()).use {entries ->for(path in entries) {
                live()
                if(++children>11 || Files.isSymbolicLink(path)) throw IOException("Absent original root layout differs")
                val name=path.fileName.toString()
                if(name=="$hex.uid" && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) {marker=true;continue}
                if(name.length!=1 || name[0] !in '0'..'9' || !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))
                    throw IOException("Absent original shard layout differs")
                ownership.directory(path).use {leaves ->for(leaf in leaves) {
                    live();val filename=leaf.fileName.toString()
                    if(filename.length>128 || Files.isSymbolicLink(leaf) || !Files.isRegularFile(leaf,LinkOption.NOFOLLOW_LINKS))
                        throw IOException("Absent original has an unsupported file")
                    val fields=filename.split('.');val id=fields.getOrNull(0)?.toLongOrNull()
                    val position=fields.getOrNull(1)?.toLongOrNull();val stamp=fields.getOrNull(2)?.toLongOrNull()
                    if(fields.size!=5 || id==null || id !in 0L..Int.MAX_VALUE.toLong() || position==null || position<0 ||
                        stamp==null || stamp<0 || filename!="$id.$position.$stamp.v3.exo")
                        throw IOException("Absent original span identity differs")
                    val length=Files.size(leaf)
                    if(length<=0 || position>Long.MAX_VALUE-length) throw IOException("Absent original span extent differs")
                    // Only booleans cross SQLite; an unrelated giant metadata/request is not read.
                    ownership.useOwned({index.rawQuery("SELECT CASE WHEN typeof(key)='text' AND key!=? THEN 1 ELSE 0 END "+
                        "FROM $content WHERE id=? LIMIT 2",arrayOf(key,id.toString()))}) {c ->
                        if(!c.moveToFirst() || c.getInt(0)!=1 || c.moveToNext())
                            throw IOException("Absent original has an unaccounted span owner")
                    }
                    ownership.useOwned({index.rawQuery("SELECT CASE WHEN typeof(length)='integer' THEN length ELSE NULL END,"+
                        "CASE WHEN typeof(last_touch_timestamp)='integer' THEN last_touch_timestamp ELSE NULL END "+
                        "FROM $metadata WHERE name=? LIMIT 2",arrayOf(filename))}) {c ->
                        if(!c.moveToFirst() || c.getType(0)!=Cursor.FIELD_TYPE_INTEGER || c.getLong(0)!=length ||
                            c.getType(1)!=Cursor.FIELD_TYPE_INTEGER || c.getLong(1)<0 || c.moveToNext())
                            throw IOException("Absent original span metadata differs")
                    }
                }}
            }}
            if(!marker) throw IOException("Absent original UID marker is missing")
            absent()
        } catch(failure:SQLiteException) {throw IOException("Absent original index proof unavailable",failure)}
    }
}
