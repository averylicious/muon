@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File
import java.io.IOException

/** First opt-in before old full-index native initialization. Review owns the process before any
 * normal startup; validate existing supported bootstrap through actual bounded read-only owners.
 * No fresh native bootstrap, repairs, automatic consent or live manager replacement. */
internal class PartitionStartupReviewOwner(private val admission:StorageStartupAdmission) {
    private val startup=StorageStartup<PartitionStartupChoice>(4)
    @Volatile var phase=PartitionOptInPhase.New;private set
    @Synchronized fun request(plan:PartitionStorePlan,databaseFile:File,explicitConsent:Boolean,
        settings:()->PartitionStartupSettings):PartitionStartupChoice {
        if(phase!=PartitionOptInPhase.New) throw IOException("Startup review cannot be replayed in this process")
        phase=PartitionOptInPhase.Checking
        try {
            if(!admission.reviewing || !explicitConsent) throw IOException("First opt-in needs exclusive startup review and explicit consent")
            plan.validate()
            if(plan.missingCardVolume!=null || plan.phone.start!=PartitionShelfStart.Fresh || plan.card?.let {it.start!=PartitionShelfStart.Fresh}==true)
                throw IOException("First opt-in requires exact Fresh roots")
            for(root in listOfNotNull(plan.phone,plan.card))
                if(root.directory.exists() || java.nio.file.Files.isSymbolicLink(root.directory.toPath()))
                    throw IOException("Existing setup root needs explicit recovery, never Fresh")
            return startup.open {opening ->
                val choice=opening.own(settings)
                // request() itself also checks the current backend before committing. Read-only
                // source validation cannot turn an existing setup choice into Fresh authority.
                val barrier=SavedStorageBarrier()
                fun legacy(root:PartitionRootPlan)=opening.own {LegacyTransitionOwner(root.legacyDirectory,databaseFile,
                    barrier,{admission.reviewing && root.currentVolume()==root.volume})}.also {it.open()}
                val phone=legacy(plan.phone)
                if(plan.card==null && phone.hasCardShelf()) throw IOException("Reconnect the original SD card before first opt-in")
                val card=plan.card?.let(::legacy)
                // Actual close must finish before preference mutation. Unknown close stays retained
                // by startup; review mode still blocks all normal initialization and live retries.
                card?.close();phone.close();plan.validate()
                choice.request(plan,true)
            }.also {phase=PartitionOptInPhase.RestartRequired}
        } catch(failure:Throwable) {
            phase=if(startup.retained>0) PartitionOptInPhase.Uncertain else PartitionOptInPhase.Refused
            throw failure
        }
    }
    fun request(context:Context,explicitConsent:Boolean):PartitionStartupChoice {
        val app=context.applicationContext
        if(StorageBackendSelection.read(app.getSharedPreferences("storage",Context.MODE_PRIVATE))!=StorageBackendSelection.Legacy)
            throw IOException("Existing setup cannot be replaced by first opt-in")
        return request(PartitionPlatformRoots.from(app).fresh(cardFolder(app)),
            app.getDatabasePath(StandaloneDatabaseProvider.DATABASE_NAME),explicitConsent) {
                PartitionStartupSettings(app.getSharedPreferences("storage",Context.MODE_PRIVATE))
            }
    }
    @Synchronized fun refusePreflight() {if(phase==PartitionOptInPhase.New) phase=PartitionOptInPhase.Refused}
}
