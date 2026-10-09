@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

internal enum class PartitionShelfStart { Fresh, Resume }

/** Real shelf resources assembled WITHOUT first opening the legacy SimpleCache. Register this
 * process owner in OfflineStore's StorageStartup before prepare(); production selection/opt-in is
 * still required. Fresh is explicit and refuses existing roots; Resume refuses missing journals.
 * No live backend switch, source cleanup, automatic repair or unsafe shutdown/reset API.
 */
internal class OwnedPartitionShelf(private val context:Context,private val directory:File,
    private val legacyDirectory:File,private val legacyDatabase:File,private val database:DatabaseProvider,
    private val indexName:String,private val volume:String,private val currentVolume:()->String?,
    private val start:PartitionShelfStart,private val barrier:SavedStorageBarrier,
    /** Complete app-wide current key/index/played/cover claim census, not merely this shelf. */
    private val unclaimed:(String)->Boolean,private val upstream:DataSource.Factory,
    private val budget:PartitionNativeBudget=PartitionNativeBudget.process) {
    init { require(volume.isNotEmpty()) }
    private val startup=StorageStartup<Resources>()
    private enum class Phase { New,Preparing,Ready,Uncertain }
    @Volatile private var phase=Phase.New
    private var rootIdentity:Any?=null
    private val journalNames=arrayOf("partition-locators-v1.db","migration-journal-v1.db","new-save-journal-v1.db")
    private val identities=arrayOfNulls<Any>(journalNames.size)
    private class Resources(val legacy:LegacyTransitionOwner,val catalog:CachePartitionCatalog,
        val migrations:CacheMigrationJournal,val saves:PartitionSaveJournal,val session:PartitionStorageSession,
        val manager:PartitionShelfManager)
    private fun exactDirectory(file:File) {
        if(!file.isDirectory || file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath()))
            throw IOException("Partition shelf directory identity differs")
    }
    private fun key(file:File):Any=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS).fileKey() ?: throw IOException("Partition shelf file identity cannot be observed")
    private fun exactJournal(file:File) {
        if(!file.isFile || file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath()))
            throw IOException("Partition shelf journal is missing or changed")
    }
    private fun located() {
        if(currentVolume()!=volume) throw IOException("Partition shelf volume unavailable or changed")
        exactDirectory(directory)
        if(key(directory)!=rootIdentity) throw IOException("Partition shelf root replaced")
        for(i in journalNames.indices) {
            val file=File(directory,journalNames[i]); exactJournal(file)
            if(key(file)!=identities[i]) throw IOException("Partition shelf journal replaced")
        }
    }
    private fun available():Boolean = phase==Phase.Ready && runCatching { located(); true }.getOrDefault(false)
    private fun preflight() {
        if(currentVolume()!=volume || directory.absoluteFile!=directory.canonicalFile ||
            directory.toPath().startsWith(legacyDirectory.toPath()) || legacyDirectory.toPath().startsWith(directory.toPath()))
            throw IOException("Partition shelf cannot alias or replace legacy storage")
        exactDirectory(requireNotNull(directory.parentFile))
        when(start) {
            PartitionShelfStart.Fresh -> if(directory.exists()) throw IOException("Fresh partition shelf root already exists")
            PartitionShelfStart.Resume -> {
                exactDirectory(directory)
                rootIdentity=key(directory)
                for(i in journalNames.indices) {
                    val file=File(directory,journalNames[i]); exactJournal(file); identities[i]=key(file)
                }
                located()
            }
        }
    }
    /** Bounded filesystem/schema work; run off main before the UI/service exposes this backend.
     * A failed attempt is sticky and retains resources. Never construct another root over it.
     */
    @Synchronized fun prepare() {
        if(phase==Phase.Ready) { located(); return }
        if(phase!=Phase.New) throw IOException("Partition shelf startup is active or uncertain")
        phase=Phase.Preparing
        try { startup.open { owner ->
            preflight()
            val legacy=owner.own { LegacyTransitionOwner(legacyDirectory,legacyDatabase,barrier,{currentVolume()==volume}) }
            val audio=legacy.open()
            if(start==PartitionShelfStart.Fresh) Files.createDirectory(directory.toPath())
            exactDirectory(directory)
            if(start==PartitionShelfStart.Fresh) rootIdentity=key(directory) else located()
            val catalog=owner.own { CachePartitionCatalog(directory,create=start==PartitionShelfStart.Fresh) }
            if(start==PartitionShelfStart.Resume) located()
            val migrations=owner.own { CacheMigrationJournal(directory,create=start==PartitionShelfStart.Fresh) }
            if(start==PartitionShelfStart.Resume) located()
            val saves=owner.own { PartitionSaveJournal(directory,create=start==PartitionShelfStart.Fresh) }
            if(start==PartitionShelfStart.Fresh) for(i in journalNames.indices) {
                val file=File(directory,journalNames[i]); exactJournal(file); identities[i]=key(file)
            }
            located()
            val native=owner.own { PartitionNativeOwner(catalog,migrations,volume,
                { if(runCatching { located(); true }.getOrDefault(false)) volume else null },budget=budget,saves=saves) }
            val index=PartitionCompletionIndex(database,indexName)
            val session=owner.own { PartitionStorageSession(catalog,saves,migrations,native,database,indexName,
                audio,upstream,{ name -> !audio.contains(name) && !index.holdsId(name) && unclaimed(name) },::available,barrier) }
            val manager=owner.own { PartitionShelfManager(context.applicationContext,database,indexName,session,barrier) }
            Resources(legacy,catalog,migrations,saves,session,manager)
        }; phase=Phase.Ready }
        catch(failure:Throwable) {
            phase=Phase.Uncertain; barrier.invalidate(); throw failure
        }
    }
    private fun resources():Resources {
        if(!available()) throw IOException("Partition shelf is not prepared or its identity changed")
        return startup.open { throw IOException("Partition shelf resources missing") }
    }
    val isAvailable:Boolean get()=available()
    val audio:SavedAudio get()=resources().session.audio
    fun initializeManager():DownloadManager=resources().manager.initialize()
    fun managerForService():DownloadManager=resources().manager.managerForService()
    fun containsId(id:String,completedOnly:Boolean=false)=resources().session.containsId(id,completedOnly)
    fun prepareSave(request:DownloadRequest)=resources().manager.prepare(request)
    fun abandonPrepared(request:DownloadRequest)=resources().manager.abandonPrepared(request)
    fun deliverPrepared(request:DownloadRequest,start:(DownloadManager)->Unit)=resources().manager.deliverPrepared(request,start)
    fun abandon(command:PartitionSaveCommands.Command,request:DownloadRequest)=resources().manager.abandon(command,request)
    fun deliver(command:PartitionSaveCommands.Command,request:DownloadRequest,start:(DownloadManager)->Unit)=
        resources().manager.deliver(command,request,start)
    fun migrate(control:CacheMigrationControl,name:String,availableBytes:()->Long,checkpoint:()->Unit):MigrationRecord {
        val made=resources()
        return made.legacy.migrate(made.session,control,name,availableBytes) { located(); checkpoint(); located() }
    }
}
