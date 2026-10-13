package dev.avery.muon

import java.io.IOException

internal enum class PartitionOptInPhase { New,Checking,Refused,RestartRequired,Uncertain }

/** One actual explicit opt-in attempt. Retain BEFORE dispatch/write. Existing native owners stay
 * alive; no new cache/provider/manager is opened. On accepted choice, permanently stop saved I/O
 * admission for this process and require full restart. A failed commit is not a live rollback. */
internal class PartitionOptInOwner(private val barrier:SavedStorageBarrier,
    private val settings:PartitionStartupSettings,private val cardIndex:PartitionCompletionIndex) {
    @Volatile var phase=PartitionOptInPhase.New;private set
    @Volatile var admissionStopped=false;private set
    @Synchronized fun refusePreflight() {if(phase==PartitionOptInPhase.New) phase=PartitionOptInPhase.Refused}
    @Synchronized fun request(plan:PartitionStorePlan,explicitConsent:Boolean,checkCurrent:()->Unit):PartitionStartupChoice {
        if(phase!=PartitionOptInPhase.New) throw IOException("Opt-in attempt cannot be replayed")
        phase=PartitionOptInPhase.Checking
        var permit:SavedStorageBarrier.Lease?=null;var quarantined=false
        try {
            if(!explicitConsent) throw IOException("Partition setup requires explicit opt-in")
            plan.validate()
            if(plan.missingCardVolume!=null || plan.phone.start!=PartitionShelfStart.Fresh ||
                plan.card?.start==PartitionShelfStart.Resume) throw IOException("First opt-in needs every selected original shelf")
            for(root in listOfNotNull(plan.phone,plan.card))
                if(root.directory.exists() || java.nio.file.Files.isSymbolicLink(root.directory.toPath()))
                    throw IOException("Partition setup root already exists; review recovery instead")
            permit=barrier.exclusive();checkCurrent();permit.check()
            if(plan.card==null && cardIndex.hasShelfIfPresent())
                throw IOException("Reconnect the original card before choosing partition storage")
            checkCurrent();plan.validate();permit.check()
            // Block waiting writers too. There is deliberately no reset/live manager swap, even if
            // preference commit reports failure after changing its in-memory map.
            barrier.stopAdmission();admissionStopped=true
            val choice=settings.request(plan,true)
            phase=PartitionOptInPhase.RestartRequired
            return choice
        } catch(failure:Throwable) {
            phase=if(admissionStopped || failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain)
                PartitionOptInPhase.Uncertain else PartitionOptInPhase.Refused
            if(failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain) {
                permit?.quarantine(this);quarantined=permit!=null
            }
            throw failure
        } finally {if(!quarantined) permit?.close()}
    }
}
