@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import androidx.media3.datasource.cache.SimpleCache
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Own the read-only legacy connection BEFORE opening a partition shelf. Register this owner in
 * StorageStartup before open(). All writers, readers, migration and eventual teardown must share
 * this gate; a folder-lock observation alone is not exclusion. No SimpleCache, DB creation/repair,
 * implicit fallback, legacy deletion, hot service-manager swap or full-index Java load.
 * Unsupported layouts remain intact. The caller still owes actual production backend selection.
 */
internal class LegacyTransitionOwner(private val directory:File,private val databaseFile:File,
    private val barrier:SavedStorageBarrier,private val available:()->Boolean,
    private val openDatabase:(File)->SQLiteDatabase={ SQLiteDatabase.openDatabase(it.path,null,SQLiteDatabase.OPEN_READONLY) },
    private val closeDatabase:(SQLiteDatabase)->Unit={ it.close() },
    private val directoryIo:MigrationIoOwnership=MigrationIoOwnership()):Closeable {
    private enum class Phase { New,Opening,Open,Closing,Closed,Unsupported,Uncertain }
    @Volatile private var phase=Phase.New
    private var index:SQLiteDatabase?=null
    private var identity:Any?=null
    private var rootIdentity:Any?=null
    private var markerIdentity:Any?=null
    private var uid:Long?=null
    private var audio:SavedAudio?=null

    private fun observed() {
        if(!available() || !directory.isDirectory || directory.absoluteFile!=directory.canonicalFile ||
            Files.isSymbolicLink(directory.toPath()) || SimpleCache.isCacheFolderLocked(directory))
            throw IOException("Legacy storage is unavailable or still has a native owner")
        val rootNow=Files.readAttributes(directory.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()
            ?: throw IOException("Legacy directory identity cannot be established")
        if(rootIdentity==null) rootIdentity=rootNow else if(rootIdentity!=rootNow) throw IOException("Legacy root was replaced")
        uid?.let { value ->
            val marker=File(directory,java.lang.Long.toHexString(value)+".uid").toPath()
            val attributes=Files.readAttributes(marker,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            if(!attributes.isRegularFile || attributes.size()!=0L) throw IOException("Legacy UID marker changed")
            val now=attributes.fileKey() ?: throw IOException("Legacy marker identity cannot be established")
            if(markerIdentity==null) markerIdentity=now else if(markerIdentity!=now) throw IOException("Legacy UID marker was replaced")
        }
        if(!databaseFile.isFile || databaseFile.absoluteFile!=databaseFile.canonicalFile ||
            Files.isSymbolicLink(databaseFile.toPath())) throw IOException("Legacy database identity unavailable")
        val now=Files.readAttributes(databaseFile.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()
            ?: throw IOException("Legacy database file identity cannot be established")
        if(identity==null) identity=now else if(identity!=now) throw IOException("Legacy database file was replaced")
    }
    private fun checkpoint() {
        if(phase!=Phase.Opening && phase!=Phase.Open) throw IOException("Legacy transition owner is closed or uncertain")
        observed()
    }
    private fun discoverUid():Long {
        var found:Long?=null; var count=0
        directoryIo.directory(directory.toPath()).use { entries -> for(path in entries) {
            checkpoint()
            if(++count>11 || Files.isSymbolicLink(path)) throw IOException("Unsupported legacy root layout")
            val name=path.fileName.toString()
            if(name.length==1 && name[0] in '0'..'9' && Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) continue
            if(!name.endsWith(".uid") || name.length>20 || found!=null ||
                !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=0L)
                throw IOException("Unsupported legacy UID marker")
            val hex=name.removeSuffix(".uid")
            val parsed=hex.toLongOrNull(16)
            if(parsed==null || parsed<0 || java.lang.Long.toHexString(parsed)!=hex)
                throw IOException("Noncanonical legacy UID marker")
            found=parsed
        } }
        return found ?: throw IOException("Legacy UID marker missing; no native cache will be created")
    }

    /** Must run off-main in the eventual production startup/migration worker. No retry after failed
     * startup on this owner, even if its known connection close succeeded. Nothing adopts a new UID.
     */
    @Synchronized fun open():SavedAudio {
        if(phase==Phase.Open) return requireNotNull(audio)
        if(phase!=Phase.New) throw IOException("Legacy transition startup cannot be retried on this owner")
        val permit=barrier.exclusive(); var quarantined=false
        phase=Phase.Opening
        try {
            checkpoint(); val found=discoverUid(); uid=found
            index=openDatabase(databaseFile)
            if(!requireNotNull(index).isReadOnly) throw IOException("Legacy connection must be read-only")
            LegacyResourceProjection.validate(directory,found,requireNotNull(index),::checkpoint)()
            audio=ReadOnlyLegacySavedAudio(directory,found,requireNotNull(index),barrier,::checkpoint)
            phase=Phase.Open
            return requireNotNull(audio)
        } catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || !directoryIo.quiescent) {
                phase=Phase.Uncertain; permit.quarantine(this); quarantined=true
            } else {
                try { index?.let(closeDatabase); index=null; phase=Phase.Unsupported }
                catch(cleanup:Throwable) {
                    phase=Phase.Uncertain; permit.quarantine(this); quarantined=true
                    if(cleanup!==failure) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        } finally { if(!quarantined) permit.close() }
    }

    /** Borrow the same live connection for an exclusive migration in the SAME session/gate. Ready
     * still grants routing only, never source deletion. Reader-at-EOF prevents this acquisition.
     */
    @Synchronized fun migrate(session:PartitionStorageSession,control:CacheMigrationControl,key:String,
        availableBytes:()->Long,checked:()->Unit):MigrationRecord {
        checkpoint()
        if(!session.usesBarrier(barrier)) throw IOException("Legacy migration requires the owner's shared storage gate")
        return session.migrateLegacy(control,directory,requireNotNull(uid),key,requireNotNull(index),availableBytes) {
            checkpoint(); checked(); checkpoint()
        }
    }
    @Synchronized override fun close() {
        if(phase==Phase.Closed || phase==Phase.Unsupported || phase==Phase.New) { phase=Phase.Closed; return }
        if(phase!=Phase.Open) throw IOException("Legacy transition close is active or uncertain")
        val permit=barrier.exclusive(); var quarantined=false
        phase=Phase.Closing
        try { index?.let(closeDatabase); index=null; phase=Phase.Closed }
        catch(failure:Throwable) {
            phase=Phase.Uncertain; permit.quarantine(this); quarantined=true; throw failure
        } finally { if(!quarantined) permit.close() }
    }
}
