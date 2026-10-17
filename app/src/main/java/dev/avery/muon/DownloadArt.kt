package dev.avery.muon

import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import okio.source
import okio.buffer
import java.io.OutputStream
import okhttp3.Call
import java.security.MessageDigest
import okhttp3.Request

/**
 * The covers of saved copies, kept beside them (#112) so they show without Tauon. The artwork cache
 * cannot be relied on for this: it is trimmed to a size and an age, and Disconnect clears it.
 *
 * Each new save owns its cover (#213), named from that entry's own request ID, fetched once when the
 * save is asked for and removed with it. Older downloads kept one cover per track number, shared by
 * every copy of that number and possibly fetched after the number had moved to another song; those
 * files are left as they are, neither shown for a saved copy, served for a live song, adopted by a new
 * save nor deleted.
 */
internal class DownloadArt(private val dir: File,
    private val calls:Call.Factory?=null,
    private val outputs:(File)->OutputStream={FileOutputStream(it)},
    private val barrier:SavedStorageBarrier?=null,
    private val inputs:(File)->InputStream={FileInputStream(it)}) {
    @Volatile private var retained:SavedCoverIo?=null
    private fun retain(owner:SavedCoverIo)=synchronized(this) {
        if(retained==null) retained=owner
        barrier?.invalidate()
    }
    private fun entryFile(requestId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("muon-saved-entry\u0000$requestId".toByteArray())
        return File(dir, "entry-" + digest.joinToString("") { "%02x".format(it) })
    }

    /** Whether the entry with this request ID has its own cover. */
    fun hasEntry(requestId: String): Boolean = retained==null && entryFile(requestId).isFile

    /** The entry's own cover, if it has one and is within the cover size cap; never an older per-track cover. */
    fun forEntry(requestId: String): ByteArray? {
        if(retained!=null) return null
        val permit=try { barrier?.shared() } catch(_:IOException) { return null }
        val io=SavedCoverIo(); var unknown=false
        try {
            try {
                permit?.check()
                val file=entryFile(requestId)
                if(!file.isFile || file.length() !in 1..COVER_BYTES) return null
                val input=inputs(file).also { io.input=it }
                val source=input.source().buffer().also { io.source=it }
                if(source.request(COVER_BYTES+1)) return null
                val bytes=source.readByteArray(source.buffer.size)
                permit?.check()
                return bytes.takeIf { it.isNotEmpty() }
            } finally { io.finish() }
        } catch(failure:SavedCoverCloseUncertain) {
            unknown=true; permit?.quarantine(failure.owner); retain(failure.owner); return null
        } catch(_:Exception) { return null }
        finally { if(!unknown) permit?.close() }
    }

    /**
     * Fetches and keeps the entry's own cover from [url]. Blocking; call it off the main thread. Uses the
     * finite metadata client, whose whole-call deadline also bounds a slow-drip response, and the cap.
     */
    fun fetchEntry(requestId: String, url: String) {
        retained?.let { throw SavedCoverCloseUncertain(it,null) }
        if (hasEntry(requestId)) return
        try {
            val io=SavedCoverIo()
            val target=entryFile(requestId)
            val temp=File(dir,target.name+".part")
            var failure:Throwable?=null
            try {
                io.response=(calls ?: Transport.metadataClient).newCall(Request.Builder().url(url).build()).execute()
                val response=requireNotNull(io.response)
                val source=response.body?.source()?.also { io.source=it }
                if(!response.isSuccessful || source==null || source.request(COVER_BYTES+1)) return
                val bytes=source.readByteArray(source.buffer.size)
                if(bytes.isEmpty()) return
                dir.mkdirs()
                val output=outputs(temp).also { io.output=it }
                output.write(bytes); output.flush()
            } catch(error:Throwable) { failure=error; throw error }
            finally { try { io.finish() } catch(unknown:SavedCoverCloseUncertain) {
                if(failure!=null && failure!==unknown && failure!==unknown.cause) unknown.addSuppressed(failure)
                throw unknown
            } }
            // Publication is after BOTH observable closes, never after a quiet response close.
            temp.renameTo(target)
        } catch(unknown:SavedCoverCloseUncertain) { retain(unknown.owner); throw unknown }
        catch(_:Exception) { /* Optional cover failure; known-closed audio/index remain unaffected. */ }
    }

    /** Removes the entry's own cover; any other file, including an older per-track cover, stays. */
    fun removeEntry(requestId: String) {
        if(retained!=null) throw IOException("Saved-cover ownership uncertain; removal refused")
        entryFile(requestId).delete()
    }

    /** Confirm only this entry's own cover absence under the actual exclusive saved-storage gate.
     * Caller cancels queued fetches first. Older shared per-track covers are never named here. */
    internal fun removeEntryConfirmed(requestId:String,permit:SavedStorageBarrier.Lease) {
        val gate=barrier ?: throw IOException("Confirmed cover removal needs its actual storage gate")
        if(!permit.exclusive || !permit.belongsTo(gate) || retained!=null)
            throw IOException("Confirmed cover removal ownership unavailable")
        permit.check()
        val file=entryFile(requestId)
        if(dir.absoluteFile!=dir.canonicalFile || file.absoluteFile!=file.canonicalFile ||
            java.nio.file.Files.isSymbolicLink(dir.toPath()) || java.nio.file.Files.isSymbolicLink(file.toPath()) ||
            (dir.exists() && !dir.isDirectory)) throw IOException("Saved cover path changed")
        java.nio.file.Files.deleteIfExists(file.toPath())
        if(java.nio.file.Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw IOException("Saved cover removal unconfirmed")
        permit.check()
    }
    /** Copies only this source entry's own cover, without removing it. Caller cancels queued
     * source/target artwork first under the same gate. Exact reads/closes and file/directory sync
     * precede the receipt; a differing existing target is never overwritten. Worker-only. */
    internal fun copyEntryConfirmed(sourceId:String,targetId:String,permit:SavedStorageBarrier.Lease):SavedMoveCoverIdentity {
        requireCoverPermit(permit)
        if(sourceId.isEmpty() || sourceId==targetId || !targetId.startsWith(NEW_SAVE_PREFIX) || targetId==NEW_SAVE_PREFIX ||
            sourceId.length.toLong()*2>MIGRATION_KEY_BYTES || targetId.length.toLong()*2>MIGRATION_KEY_BYTES)
            throw IOException("Cover copy requires distinct bounded saved identities")
        val source=entryFile(sourceId); val target=entryFile(targetId)
        val original=coverIdentity(source,permit)
        val existing=coverIdentity(target,permit)
        if(original.length==0L) {
            if(existing.length!=0L) throw IOException("Absent source cannot adopt an existing target cover")
            return original
        }
        if(existing.length!=0L) {
            if(existing!=original) throw IOException("Existing target cover differs; overwrite refused")
            syncCoverDirectory(permit)
            if(coverIdentity(source,permit)!=original || coverIdentity(target,permit)!=original)
                throw IOException("Cover identity changed during confirmation")
            return original
        }
        val directory=coverAttributes(dir) ?: throw IOException("Cover directory disappeared")
        if(!directory.isDirectory || directory.fileKey()==null) throw IOException("Cover directory identity unavailable")
        val temp=Files.createTempFile(dir.toPath(),target.name+".move-", ".part").toFile()
        val created=coverAttributes(temp) ?: throw IOException("Cover temporary file disappeared")
        if(!created.isRegularFile || created.fileKey()==null) throw IOException("Cover temporary identity unavailable")
        val io=SavedCoverIo(); var failure:Throwable?=null; var unknown=false
        io.disposition=arrayOf(source,target,temp,directory,created)
        try {
            try {
                permit.check(); checkCoverPath(source); checkCoverPath(temp)
                val input=inputs(source).also {io.input=it}
                val output=outputs(temp).also {io.output=it}
                if(output !is FileOutputStream) throw IOException("Cover copy needs a syncable file output")
                val buffer=ByteArray(8192); var left=original.length
                while(left>0) {
                    permit.check()
                    val count=input.read(buffer,0,minOf(buffer.size.toLong(),left).toInt())
                    if(count<=0) throw IOException("Source cover ended before its confirmed length")
                    output.write(buffer,0,count); left-=count
                }
                if(input.read()!=-1) throw IOException("Source cover grew during copy")
                output.flush(); output.fd.sync(); permit.check()
            } catch(error:Throwable) {failure=error; throw error}
            finally {try {io.finish()} catch(close:SavedCoverCloseUncertain) {
                if(failure!=null && failure!==close && failure!==close.cause) close.addSuppressed(failure)
                throw close
            }}
            if(coverIdentity(source,permit)!=original || coverIdentity(temp,permit)!=original ||
                coverAttributes(dir)?.fileKey()!=directory.fileKey() || coverIdentity(target,permit).length!=0L)
                throw IOException("Cover paths or bytes changed before publication")
            // An ambiguous rename retains its exact paths and blocks this owner. No retry/cleanup.
            try {Files.move(temp.toPath(),target.toPath(),ATOMIC_MOVE)}
            catch(error:Throwable) {
                unknown=true; val owner=SavedCoverIo().also {it.disposition=arrayOf(source,target,temp,directory,created,error)}
                retain(owner); permit.quarantine(owner); throw PartitionOwnershipUncertain(owner,error)
            }
            syncCoverDirectory(permit)
            if(coverAttributes(dir)?.fileKey()!=directory.fileKey() ||
                coverIdentity(source,permit)!=original || coverIdentity(target,permit)!=original)
                throw IOException("Published cover identity changed")
            return original
        } catch(close:SavedCoverCloseUncertain) {
            unknown=true; retain(close.owner); permit.quarantine(close.owner); throw close
        } finally {
            if(!unknown && retained==null) {
                // Only our known-closed unpublished temporary inode; never source/target/other parts.
                val remaining=coverAttributes(temp)
                if(remaining!=null && remaining.fileKey()==created.fileKey()) Files.delete(temp.toPath())
            }
        }
    }

    /** Snapshot this entry's own cover for explicit Delete; shared track covers are unrelated. */
    internal fun savedDeleteCover(key:String,permit:SavedStorageBarrier.Lease):SavedMoveCoverIdentity {
        requireCoverPermit(permit)
        return coverIdentity(sourceEntry(key),permit).also {requireCoverPermit(permit)}
    }
    /** Exact persisted own-cover observation, allowing absence from a previous Delete attempt.
     * Directory sync and actual absence precede the final durable Delete tombstone. */
    internal fun removeSavedDeleteCoverConfirmed(key:String,expected:SavedMoveCoverIdentity,permit:SavedStorageBarrier.Lease) {
        verifyMovedSource(key,expected,permit)
        removeEntryConfirmed(key,permit);syncCoverDirectory(permit);verifyEntryAbsent(key,permit)
    }
    /** Reobserve both own covers before a Move retirement. No source deletion authority. */
    internal fun verifyCopiedEntry(sourceId:String,targetId:String,expected:SavedMoveCoverIdentity,
        permit:SavedStorageBarrier.Lease) {
        requireCoverPermit(permit); expected.validate()
        if(sourceId==targetId || coverIdentity(sourceEntry(sourceId),permit)!=expected ||
            coverIdentity(sourceEntry(targetId),permit)!=expected) throw IOException("Moved cover identity changed")
    }
    /** The destination is rechecked even when a previous explicit attempt already removed source.
     * The persisted receipt is required; absence after an interrupted Move never invents proof. */
    internal fun verifyMovedTarget(targetId:String,expected:SavedMoveCoverIdentity,permit:SavedStorageBarrier.Lease) {
        requireCoverPermit(permit); expected.validate()
        if(!targetId.startsWith(NEW_SAVE_PREFIX) || targetId==NEW_SAVE_PREFIX ||
            coverIdentity(sourceEntry(targetId),permit)!=expected) throw IOException("Moved target cover changed")
    }
    internal fun verifyMovedSource(sourceId:String,expected:SavedMoveCoverIdentity,permit:SavedStorageBarrier.Lease) {
        requireCoverPermit(permit); expected.validate()
        val original=coverIdentity(sourceEntry(sourceId),permit)
        if(original.length!=0L && original!=expected) throw IOException("Move original cover changed")
    }
    internal fun verifyEntryAbsent(sourceId:String,permit:SavedStorageBarrier.Lease) {
        requireCoverPermit(permit)
        if(coverAttributes(sourceEntry(sourceId))!=null) throw IOException("Removed own-cover reappeared")
        requireCoverPermit(permit)
    }
    internal fun removeMovedSourceConfirmed(sourceId:String,targetId:String,expected:SavedMoveCoverIdentity,
        permit:SavedStorageBarrier.Lease) {
        if(sourceId==targetId) throw IOException("Moved cover source aliases destination")
        verifyMovedTarget(targetId,expected,permit)
        verifyMovedSource(sourceId,expected,permit)
        removeEntryConfirmed(sourceId,permit)
        syncCoverDirectory(permit)
        verifyMovedTarget(targetId,expected,permit)
    }
    private fun sourceEntry(id:String):File {
        if(id.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Cover identity exceeds supported bound")
        return entryFile(id)
    }
    private fun requireCoverPermit(permit:SavedStorageBarrier.Lease) {
        val gate=barrier ?: throw IOException("Confirmed cover copy needs its actual storage gate")
        if(!permit.exclusive || !permit.belongsTo(gate) || retained!=null)
            throw IOException("Confirmed cover copy ownership unavailable")
        permit.check()
    }
    private fun checkCoverPath(file:File) {
        if(dir.absoluteFile!=dir.canonicalFile || file.absoluteFile!=file.canonicalFile ||
            Files.isSymbolicLink(dir.toPath()) || Files.isSymbolicLink(file.toPath()) ||
            (dir.exists() && !dir.isDirectory)) throw IOException("Saved cover path changed")
    }
    private fun coverAttributes(file:File):BasicFileAttributes? {
        checkCoverPath(file)
        return try {Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)}
        catch(_:NoSuchFileException) {null}
    }
    private fun coverIdentity(file:File,permit:SavedStorageBarrier.Lease):SavedMoveCoverIdentity {
        permit.check()
        val before=coverAttributes(file) ?: return SavedMoveCoverIdentity(0,null)
        if(!before.isRegularFile || before.fileKey()==null || before.size() !in 1..COVER_BYTES)
            throw IOException("Saved cover file is unsupported")
        val io=SavedCoverIo(); io.disposition=arrayOf(file,before)
        val digest=MessageDigest.getInstance("SHA-256"); var failure:Throwable?=null
        try {
            try {
                val input=inputs(file).also {io.input=it}; val bytes=ByteArray(8192); var left=before.size()
                while(left>0) {
                    permit.check(); val count=input.read(bytes,0,minOf(bytes.size.toLong(),left).toInt())
                    if(count<=0) throw IOException("Saved cover ended early")
                    digest.update(bytes,0,count); left-=count
                }
                if(input.read()!=-1) throw IOException("Saved cover exceeds its exact length")
            } catch(error:Throwable) {failure=error; throw error}
            finally {try {io.finish()} catch(close:SavedCoverCloseUncertain) {
                if(failure!=null && failure!==close && failure!==close.cause) close.addSuppressed(failure)
                throw close
            }}
        } catch(close:SavedCoverCloseUncertain) {
            retain(close.owner); permit.quarantine(close.owner); throw close
        }
        val after=coverAttributes(file)
        if(after==null || after.fileKey()!=before.fileKey() || after.size()!=before.size() ||
            after.lastModifiedTime()!=before.lastModifiedTime()) throw IOException("Saved cover changed during read")
        permit.check()
        return SavedMoveCoverIdentity(before.size(),digest.digest().joinToString("") {"%02x".format(it)})
    }
    private fun syncCoverDirectory(permit:SavedStorageBarrier.Lease) {
        permit.check(); val io=SavedCoverIo(); io.disposition=dir
        try {
            try {
                val channel=FileChannel.open(dir.toPath(),READ).also {io.extra=it}; channel.force(true)
            } finally {io.finish()}
        } catch(close:SavedCoverCloseUncertain) {
            retain(close.owner); permit.quarantine(close.owner); throw close
        }
        permit.check()
    }
    private companion object { const val COVER_BYTES = 4L * 1024 * 1024 }
}
