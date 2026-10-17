package dev.avery.muon

import java.io.IOException

internal enum class PartitionSetupReviewPhase { New,Checking,Refused,RestartRequired,Uncertain }

/** Preferences-only explicit recovery; retains actual settings BEFORE reading/writing. Never
 * opens roots, repairs journals, retries old startup or grants Fresh after interrupted creation. */
internal class PartitionSetupResumeOwner {
    private val startup=StorageStartup<PartitionStartupChoice>(1)
    @Volatile var phase=PartitionSetupReviewPhase.New;private set
    @Synchronized fun request(explicitConsent:Boolean,settings:()->PartitionStartupSettings):PartitionStartupChoice {
        if(phase!=PartitionSetupReviewPhase.New) throw IOException("Setup review requires a new process")
        if(!explicitConsent) {phase=PartitionSetupReviewPhase.Refused;throw IOException("Setup review requires explicit consent")}
        phase=PartitionSetupReviewPhase.Checking
        try {
            return startup.open {opening ->
                val owner=opening.own(settings);val expected=owner.read()
                owner.reviewResume(expected,true)
            }.also {phase=PartitionSetupReviewPhase.RestartRequired}
        } catch(failure:Throwable) {phase=PartitionSetupReviewPhase.Uncertain;throw failure}
    }
}
