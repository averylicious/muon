@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal const val LEGACY_RETIREMENT_MANIFEST_BYTES=256*1024
internal data class LegacyRetirementSpan(val path:String,val position:Long,val length:Long,
    val fileIdentity:String,val digest:String)

/** Bounded exact-file observation for a CLOSED legacy cache, not deletion authority. A future
 * coordinator must durably record explicit Delete/verified Move intent before any unlink, hold the
 * app-wide exclusive gate and volume/owner checkpoint, and bind index/alias/Ready routing too.
 * No SimpleCache open, private native SQL write, file deletion or manager command occurs here.
 * Hashes detect changed bytes; they do not authenticate an untrusted filesystem or database.
 */
internal data class LegacyRetirementManifest private constructor(val key:String,val uid:Long,
    val rootIdentity:String,val databaseIdentity:String,val markerIdentity:String,
    val contentId:Long,val metadataDigest:String,val spans:List<LegacyRetirementSpan>,
    val completedDownloadIdentity:String?=null,val moveDestination:PartitionMoveDestination?=null,val moveCover:SavedMoveCoverIdentity?=null) {
    private fun validate() {
        if(key.length.toLong()*2>MIGRATION_KEY_BYTES || uid<0 || contentId !in 0L..Int.MAX_VALUE.toLong() ||
            spans.size>MIGRATION_RANGES) throw IOException("Legacy retirement identity exceeds its budget")
        listOf(rootIdentity,databaseIdentity,markerIdentity).forEach(::requireIdentity)
        requireDigest(metadataDigest); completedDownloadIdentity?.let(::requireDigest)
        moveDestination?.let {destination ->
            destination.validate()
            if(completedDownloadIdentity==null || destination.ticket.allocation.key==key)
                throw IOException("Move retirement requires a bound completed original and distinct fresh destination")
        }
        moveCover?.let {cover ->
            cover.validate()
            if(moveDestination==null) throw IOException("Move cover requires a bound destination")
        }
        var end=0L
        for((i,span) in spans.withIndex()) {
            requireIdentity(span.fileIdentity); requireDigest(span.digest)
            if(span.path.length>130 || span.path.length<3 || span.path[0] !in '0'..'9' || span.path[1]!='/')
                throw IOException("Legacy retirement path leaves its shard")
            val name=span.path.substring(2); val parts=name.split('.')
            val stamp=parts.getOrNull(2)?.toLongOrNull()
            if(stamp==null || stamp<0 || name!="$contentId.${span.position}.$stamp.v3.exo" ||
                span.position<end || span.length<=0 || span.position>Long.MAX_VALUE-span.length ||
                (0 until i).any {spans[it].path==span.path}) throw IOException("Legacy retirement span differs")
            end=span.position+span.length
        }
    }
    /** Bind the exact completed row before durable intent. Unbound v1/files-only records remain
     * readable for files-stage recovery, but cannot authorize terminal-index deletion. */
    internal fun withCompletedDownload(expected:PartitionSaveCompletion):LegacyRetirementManifest {
        if(expected.key!=key) throw IOException("Retirement completion belongs to another original")
        return copy(completedDownloadIdentity=expected.retirementIdentity()).also {it.validate()}
    }
    internal fun withMoveDestination(destination:PartitionMoveDestination)=copy(moveDestination=destination).also {it.validate()}
    internal fun withMoveCover(cover:SavedMoveCoverIdentity)=copy(moveCover=cover).also {it.validate()}
    /** SQL projection is bounded BEFORE CursorWindow; validation never rehydrates the whole old
     * index. Missing files are allowed ONLY when the future durable pending intent says they may
     * already have been retired. A missing file alone is never proof of a completed deletion.
     */
    fun verifyRemaining(root:File,index:SQLiteDatabase,allowMissing:Boolean,checkpoint:()->Unit,
        ownership:MigrationIoOwnership=MigrationIoOwnership()) {
        validate(); requireRoot(root)
        LegacyResourceProjection.validate(root,uid,index,checkpoint,ownership)()
        if(identity(root)!=rootIdentity || identity(File(index.path))!=databaseIdentity ||
            identity(File(root,java.lang.Long.toHexString(uid)+".uid"))!=markerIdentity ||
            row(index,uid,key,ownership)!=(contentId to metadataDigest)) throw IOException("Legacy retirement source changed")
        indexed(index,ownership)
        for(span in spans) {
            checkpoint(); val file=File(root,span.path)
            exactParent(root,file)
            // NOFOLLOW also observes a dangling link, which exists() alone would miss.
            if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                if(!allowMissing) throw IOException("Legacy retirement file is missing")
                continue
            }
            exactSpan(root,file,span.length,span.fileIdentity)
            if(digest(file,span.length,ownership,checkpoint)!=span.digest) throw IOException("Legacy retirement bytes changed")
            exactSpan(root,file,span.length,span.fileIdentity)
        }
        checkpoint()
    }
    /** Internal cleanup primitive. Caller must hold actual exclusive source/volume admission and
     * an exact durable Pending record; missing files then represent interrupted work, not success.
     * Recheck bytes and file identity immediately before each unlink. Native tables remain readonly.
     * This does not dispose of a Ready duplicate, download record or artwork, or mark Complete.
     */
    internal fun retireRemaining(root:File,index:SQLiteDatabase,checkpoint:()->Unit,
        unlink:(File)->Unit,ownership:MigrationIoOwnership,beforeUnlink:()->Unit={}):LegacyRetirementFileResult {
        verifyRemaining(root,index,true,checkpoint,ownership)
        var removed=0L; var missing=0L
        for(span in spans) {
            checkpoint(); val file=File(root,span.path); exactParent(root,file)
            if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                missing=Math.addExact(missing,span.length); continue
            }
            beforeUnlink(); checkpoint()
            exactSpan(root,file,span.length,span.fileIdentity)
            if(digest(file,span.length,ownership,checkpoint)!=span.digest)
                throw IOException("Legacy retirement bytes changed before unlink")
            exactSpan(root,file,span.length,span.fileIdentity); checkpoint()
            exactSpan(root,file,span.length,span.fileIdentity)
            unlink(file)
            checkpoint(); removed=Math.addExact(removed,span.length)
        }
        verifyRemaining(root,index,true,checkpoint,ownership)
        for(span in spans) if(Files.exists(File(root,span.path).toPath(),LinkOption.NOFOLLOW_LINKS))
            throw IOException("Legacy retirement files remain")
        checkpoint(); return LegacyRetirementFileResult(removed,missing)
    }
    private fun indexed(index:SQLiteDatabase,ownership:MigrationIoOwnership) {
        val table="ExoPlayerCacheFileMetadata"+java.lang.Long.toHexString(uid)
        var count=0
        ownership.useOwned({index.rawQuery("SELECT CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=128 THEN name ELSE NULL END,"+
            "CASE WHEN typeof(length)='integer' THEN length ELSE NULL END FROM $table WHERE name GLOB ? LIMIT ${MIGRATION_RANGES+1}",
            arrayOf("$contentId.*"))}) { c -> while(c.moveToNext()) {
            if(++count>MIGRATION_RANGES || c.getType(0)!=Cursor.FIELD_TYPE_STRING || c.getType(1)!=Cursor.FIELD_TYPE_INTEGER)
                throw IOException("Legacy retirement span census exceeds its budget")
            val name=c.getString(0)
            val span=spans.singleOrNull {it.path.substring(2)==name}
            if(span==null || span.length!=c.getLong(1)) throw IOException("Legacy retirement index changed")
        } }
        if(count!=spans.size) throw IOException("Legacy retirement span census changed")
    }
    fun encode():ByteArray {
        validate()
        val buffer=object:ByteArrayOutputStream() {
            override fun write(b:Int) { if(size()>=LEGACY_RETIREMENT_MANIFEST_BYTES) throw IOException("Retirement manifest exceeds budget"); super.write(b) }
            override fun write(b:ByteArray,off:Int,len:Int) {
                if(len<0 || len>LEGACY_RETIREMENT_MANIFEST_BYTES-size()) throw IOException("Retirement manifest exceeds budget")
                super.write(b,off,len)
            }
        }
        DataOutputStream(buffer).use { out ->
            out.writeInt(4); out.writeUTF(key); out.writeLong(uid)
            out.writeUTF(rootIdentity); out.writeUTF(databaseIdentity); out.writeUTF(markerIdentity)
            out.writeLong(contentId); out.writeUTF(metadataDigest); out.writeInt(spans.size)
            for(span in spans) {
                out.writeUTF(span.path); out.writeLong(span.position); out.writeLong(span.length)
                out.writeUTF(span.fileIdentity); out.writeUTF(span.digest)
            }
            out.writeBoolean(completedDownloadIdentity!=null)
            completedDownloadIdentity?.let(out::writeUTF)
            out.writeBoolean(moveDestination!=null); moveDestination?.write(out)
            out.writeBoolean(moveCover!=null); moveCover?.let {cover ->
                out.writeLong(cover.length); out.writeBoolean(cover.digest!=null); cover.digest?.let(out::writeUTF)
            }
        }
        return buffer.toByteArray()
    }
    companion object {
        private fun requireIdentity(value:String) {
            if(value.isEmpty() || value.length>256) throw IOException("Legacy retirement file identity unsupported")
        }
        private fun requireDigest(value:String) {
            if(value.length!=64 || value.any {it !in '0'..'9' && it !in 'a'..'f'}) throw IOException("Legacy retirement digest differs")
        }
        private fun requireRoot(file:File) {
            if(!file.isDirectory || file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath()))
                throw IOException("Legacy retirement root differs")
        }
        private fun attributes(file:File)=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        private fun identity(file:File):String {
            if(file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath())) throw IOException("Legacy retirement file is redirected")
            return (attributes(file).fileKey()?.toString() ?: throw IOException("Legacy retirement file identity unavailable"))
                .also(::requireIdentity)
        }
        private fun exactParent(root:File,file:File) {
            val parent=requireNotNull(file.parentFile)
            if(parent.parentFile!=root || parent.name.length!=1 || parent.name[0] !in '0'..'9' ||
                parent.absoluteFile!=parent.canonicalFile || !parent.isDirectory || Files.isSymbolicLink(parent.toPath()))
                throw IOException("Legacy retirement shard differs")
        }
        private fun exactSpan(root:File,file:File,length:Long,expected:String) {
            exactParent(root,file)
            val attributes=attributes(file)
            if(!attributes.isRegularFile || attributes.size()!=length || identity(file)!=expected)
                throw IOException("Legacy retirement span identity differs")
        }
        private fun row(index:SQLiteDatabase,uid:Long,key:String,ownership:MigrationIoOwnership):Pair<Long,String> {
            if(!index.isReadOnly || key.length.toLong()*2>MIGRATION_KEY_BYTES || uid<0)
                throw IOException("Legacy retirement requires a bounded read-only index")
            val table="ExoPlayerCacheIndex"+java.lang.Long.toHexString(uid)
            return ownership.useOwned({index.rawQuery("SELECT CASE WHEN typeof(id)='integer' THEN id ELSE NULL END,"+
                "CASE WHEN typeof(metadata)='blob' AND length(metadata)<=$LEGACY_PROJECTION_METADATA_BYTES THEN metadata ELSE NULL END "+
                "FROM $table WHERE typeof(key)='text' AND key=? LIMIT 2",arrayOf(key))}) { c ->
                if(!c.moveToFirst() || c.getType(0)!=Cursor.FIELD_TYPE_INTEGER || c.getLong(0) !in 0L..Int.MAX_VALUE.toLong() ||
                    c.getType(1)!=Cursor.FIELD_TYPE_BLOB) throw IOException("Legacy retirement record changed or exceeds budget")
                val result=c.getLong(0) to hash(c.getBlob(1))
                if(c.moveToNext()) throw IOException("Legacy retirement key is not unique")
                result
            }
        }
        private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        private fun digest(file:File,length:Long,ownership:MigrationIoOwnership,checkpoint:()->Unit):String {
            val hash=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(8192)
            val input=ownership.input(file)
            try {
                var read=0L
                while(read<length) {
                    checkpoint(); val count=input.read(buffer,0,minOf(buffer.size.toLong(),length-read).toInt())
                    if(count<=0) throw IOException("Legacy retirement source ended or made no progress")
                    hash.update(buffer,0,count); read+=count
                }
                checkpoint(); if(input.read()!=-1) throw IOException("Legacy retirement source grew")
            } finally {ownership.close(input)}
            return hash.digest().joinToString("") {"%02x".format(it)}
        }
        /** The caller owes actual exclusive storage/availability exclusion; a folder-lock snapshot
         * is insufficient. Existing projection validates the entire supported layout and selected
         * span/index correspondence before any manifest can be persisted or used for retirement.
         */
        fun capture(root:File,uid:Long,key:String,index:SQLiteDatabase,checkpoint:()->Unit,
            ownership:MigrationIoOwnership=MigrationIoOwnership()):LegacyRetirementManifest {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Legacy retirement observation requires a worker"}
            requireRoot(root)
            val rootId=identity(root); val dbId=identity(File(index.path))
            val markerId=identity(File(root,java.lang.Long.toHexString(uid)+".uid"))
            val projected=LegacyResourceProjection.read(root,uid,key,index,checkpoint,ownership)
            val row=row(index,uid,key,ownership)
            val spans=projected.getCachedSpans(key).map { span ->
                checkpoint(); val file=requireNotNull(span.file); val owner=identity(file)
                exactSpan(root,file,span.length,owner)
                LegacyRetirementSpan(file.relativeTo(root).invariantSeparatorsPath,span.position,span.length,owner,digest(file,span.length,ownership,checkpoint))
            }
            val made=LegacyRetirementManifest(key,uid,rootId,dbId,markerId,row.first,row.second,spans)
            made.verifyRemaining(root,index,false,checkpoint,ownership)
            return made
        }
        fun decode(bytes:ByteArray):LegacyRetirementManifest {
            if(bytes.size>LEGACY_RETIREMENT_MANIFEST_BYTES) throw IOException("Retirement manifest exceeds budget")
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version=input.readInt()
                if(version !in 1..4) throw IOException("Unsupported retirement manifest version")
                val key=input.readUTF(); val uid=input.readLong()
                val root=input.readUTF(); val database=input.readUTF(); val marker=input.readUTF()
                val id=input.readLong(); val metadata=input.readUTF(); val count=input.readInt()
                if(count !in 0..MIGRATION_RANGES) throw IOException("Retirement manifest span budget exceeded")
                val spans=ArrayList<LegacyRetirementSpan>(count)
                repeat(count) {spans+=LegacyRetirementSpan(input.readUTF(),input.readLong(),input.readLong(),input.readUTF(),input.readUTF())}
                val completed=if(version>=2 && input.readBoolean()) input.readUTF() else null
                val destination=if(version>=3 && input.readBoolean()) PartitionMoveDestination.read(input) else null
                val cover=if(version>=4 && input.readBoolean()) SavedMoveCoverIdentity(input.readLong(),
                    if(input.readBoolean()) input.readUTF() else null).also {it.validate()} else null
                LegacyRetirementManifest(key,uid,root,database,marker,id,metadata,spans,completed,destination,cover).also {
                    it.validate(); if(input.read()!=-1) throw IOException("Retirement manifest has trailing data")
                }
            }
        }
    }
}
