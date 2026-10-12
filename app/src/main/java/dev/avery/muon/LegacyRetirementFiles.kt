package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

internal data class LegacyRetirementFileResult(val removedBytes:Long,val alreadyMissingBytes:Long)

/** One explicitly admitted exact-Pending attempt. The caller owns the real source/index/gate and
 * must register this object before run(). Unknown unlink/close retains this object and exact file
 * or handles; it is never retried in this process. A later process needs explicit recovery consent.
 * Successful return proves only this manifest's files absent, not complete user-visible Delete.
 */
internal class LegacyRetirementFiles(private val unlink:(File)->Unit={Files.delete(it.toPath())},
    private val ownership:MigrationIoOwnership=MigrationIoOwnership()) {
    private enum class Phase { New,Active,Finished,Uncertain }
    private var phase=Phase.New
    private var current:File?=null
    @get:Synchronized val uncertain:Boolean get()=phase==Phase.Uncertain
    @Synchronized fun run(record:LegacyRetirementRecord,root:File,index:SQLiteDatabase,
        checkpoint:()->Unit):LegacyRetirementFileResult {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Original file cleanup requires a worker"}
        if(phase!=Phase.New || record.phase!=LegacyRetirementPhase.Pending)
            throw IOException("Original file cleanup requires a fresh owner and exact Pending intent")
        phase=Phase.Active
        try {
            val result=record.manifest.retireRemaining(root,index,checkpoint,{file ->
                current=file // Retain exact target before a possibly partial unlink.
                try {
                    unlink(file)
                    if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))
                        throw IOException("Original unlink did not establish absence")
                    current=null
                } catch(failure:Throwable) {
                    phase=Phase.Uncertain; throw PartitionOwnershipUncertain(this,failure)
                }
            },ownership)
            phase=Phase.Finished; return result
        } catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || !ownership.quiescent || phase==Phase.Uncertain) {
                phase=Phase.Uncertain
                throw if(failure is PartitionOwnershipUncertain) failure else PartitionOwnershipUncertain(this,failure)
            }
            phase=Phase.Finished; throw failure
        }
    }
}
