package dev.avery.muon

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal val PARTITION_BOOTSTRAP_JOURNALS=listOf("partition-locators-v1.db","migration-journal-v1.db","new-save-journal-v1.db",
    "legacy-retirements-v1.db","partition-moves-v1.db","partition-deletes-v1.db")

/** Publish a complete CLOSED empty journal set, never a partially usable shelf. Register this
 * actual owner before publish. Interrupted temporary folders are preserved, never repaired or
 * adopted; explicit reviewed setup can start another bounded attempt if the final root is absent.
 * Six actual SQL owners, one tracked directory/channel at a time. No native cache/audio/manager.
 */
internal class PartitionBootstrapOwner(private val target:File,private val barrier:SavedStorageBarrier,
    private val available:()->Boolean,private val io:MigrationIoOwnership=MigrationIoOwnership(),
    private val move:(File,File)->Unit={from,to ->Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);Unit}) {
    private val startup=StorageStartup<File>(6)
    private var attempted=false
    private var stage:File?=null
    private var parentIdentity:Any?=null
    private var stageIdentity:Any?=null
    private fun identity(file:File):Any=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()
        ?: throw IOException("Setup directory identity unavailable")
    private val parent get()=requireNotNull(target.parentFile)
    private val prefix get()="."+target.name+".setup-"
    private fun checkpoint(beforePublication:Boolean=true) {
        if(!available() || parent!=parent.canonicalFile || !parent.isDirectory || Files.isSymbolicLink(parent.toPath()) ||
            identity(parent)!=parentIdentity || target!=target.canonicalFile ||
            beforePublication && Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))
            throw IOException("Setup destination changed or is not absent")
        stage?.let {
            if(beforePublication && (!it.isDirectory || it!=it.canonicalFile || Files.isSymbolicLink(it.toPath()) || identity(it)!=stageIdentity))
                throw IOException("Setup temporary directory changed")
        }
    }
    private fun sync(directory:File)=io.useOwned({FileChannel.open(directory.toPath(),StandardOpenOption.READ)}) {it.force(true)}
    @Synchronized fun publish():File {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Setup publication requires a worker"}
        if(attempted) throw IOException("Setup publication cannot retry in this process")
        attempted=true
        val permit=barrier.exclusive();var quarantined=false
        try {return startup.open {owners ->
            parentIdentity=identity(parent);checkpoint()
            var abandoned=0
            io.directory(parent.toPath()).use {entries ->for(entry in entries) {
                checkpoint();if(entry.fileName.toString().startsWith(prefix) && ++abandoned>=4)
                    throw IOException("Interrupted setup attempt budget reached; preserve folders for review")
            }}
            stage=Files.createTempDirectory(parent.toPath(),prefix).toFile();stageIdentity=identity(requireNotNull(stage));checkpoint()
            val folder=requireNotNull(stage)
            val catalog=owners.own {CachePartitionCatalog(folder,create=true,autoOpen=false)};catalog.open();checkpoint()
            val migrations=owners.own {CacheMigrationJournal(folder,create=true,autoOpen=false)};migrations.open();checkpoint()
            val saves=owners.own {PartitionSaveJournal(folder,create=true,autoOpen=false)};saves.open();checkpoint()
            val retirements=owners.own {LegacyRetirementJournal(folder,true)};retirements.open();checkpoint()
            val moves=owners.own {PartitionSavedMoveJournal(folder,true)};moves.open();checkpoint()
            val deletes=owners.own {PartitionSavedDeleteJournal(folder,true)};deletes.open();checkpoint()
            for(owner in listOf<Closeable>(deletes,moves,retirements,saves,migrations,catalog)) {owner.close();checkpoint()}
            // No database/native handles cross the rename. Ensure the exact six closed files only;
            // SQLite sidecars/foreign entries are not discarded or silently published.
            var count=0
            io.directory(folder.toPath()).use {entries ->for(entry in entries) {
                checkpoint();if(++count>PARTITION_BOOTSTRAP_JOURNALS.size || entry.fileName.toString() !in PARTITION_BOOTSTRAP_JOURNALS ||
                    Files.isSymbolicLink(entry) || !Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS))
                    throw IOException("Setup journal set is unsupported")
            }}
            if(count!=PARTITION_BOOTSTRAP_JOURNALS.size) throw IOException("Setup journal set is incomplete")
            sync(folder);checkpoint();permit.check()
            move(folder,target)
            checkpoint(beforePublication=false)
            if(!target.isDirectory || identity(target)!=stageIdentity) throw IOException("Published setup identity differs")
            sync(parent);permit.check();target
        }} catch(failure:Throwable) {
            // Even a known refusal is sticky for this attempted owner. Actual partial SQL/unknown
            // channel/rename ownership remains reachable; do not close/delete/repair on failure.
            permit.quarantine(this);quarantined=true
            throw PartitionOwnershipUncertain(this,failure)
        } finally {if(!quarantined) permit.close()}
    }
}
