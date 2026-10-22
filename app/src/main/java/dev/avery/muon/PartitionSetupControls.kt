package dev.avery.muon

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Staged setup controls share the selector's compile-time gate. Bounded preferences and fixed
 * status only; no actual journal/native scan, coroutine retry or filesystem inference on UI. */
@Composable
internal fun PartitionSetupControls(store:OfflineStore.Store?,availability:StorageAvailability):Boolean {
    if(!PARTITION_OPT_IN_AVAILABLE) return false
    val context=LocalContext.current
    var refresh by remember {mutableIntStateOf(0)}
    var pending by remember {mutableStateOf(false)}
    var confirm by remember {mutableStateOf(false)}
    var refused by remember {mutableStateOf(false)}
    val revision=DownloadMarks.revision
    val prefs=remember {context.getSharedPreferences("storage",Context.MODE_PRIVATE)}
    val backend=remember(refresh,revision) {runCatching {StorageBackendSelection.read(prefs)}.getOrNull()}
    val choice=remember(refresh,revision) {runCatching {PartitionStartupSettings(prefs).read()}.getOrNull()}
    var optIn by remember(store,refresh,revision) {mutableStateOf(store?.partitionOptInOwner?.phase)}
    var review by remember(refresh,revision) {mutableStateOf(OfflineStore.partitionSetupReviewPhase)}
    LaunchedEffect(pending,store,refresh) {
        if(pending) do {
            delay(250)
            optIn=store?.partitionOptInOwner?.phase;review=OfflineStore.partitionSetupReviewPhase
            val active=optIn in setOf(PartitionOptInPhase.New,PartitionOptInPhase.Checking) ||
                review==PartitionSetupReviewPhase.Checking || (store==null && review==PartitionSetupReviewPhase.New)
            if(!active) {pending=false;refresh++}
        } while(pending)
    }
    val stopped=store?.partitionOptInOwner?.admissionStopped==true ||
        optIn==PartitionOptInPhase.RestartRequired || optIn==PartitionOptInPhase.Uncertain ||
        review in setOf(PartitionSetupReviewPhase.RestartRequired,PartitionSetupReviewPhase.Uncertain)
    val interrupted=choice?.phase==PartitionStartupPhase.Preparing && (availability==StorageAvailability.Failed || OfflineStore.isStartupReviewSelected) && store==null
    val legacy=store?.phone?.partition==null && store!=null && backend==StorageBackendSelection.Legacy
    if(!legacy && !interrupted && !stopped) return false
    // Canary (including a non-debuggable one) is the debug build type; Stable is release.
    val canary=BuildConfig.BUILD_TYPE=="debug"
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(interrupted) "Interrupted saved-storage setup" else if(canary) "Saved-song storage · Canary preview"
                else "Saved-song storage · optional setup",style=MaterialTheme.typography.titleSmall)
            Text(when {
                optIn==PartitionOptInPhase.Uncertain || review==PartitionSetupReviewPhase.Uncertain ->
                    "The setup choice could not be confirmed. Existing copies are kept. Fully restart Muon before reviewing it again."
                pending -> "Saving the startup choice. Wait for confirmation before fully restarting Muon; saved storage may already be paused."
                stopped -> "The startup choice is saved. Use Android App info to Force stop Muon, then reopen it before using saved storage. Existing copies are kept; no managers are replaced while the app is running."
                interrupted -> "Setup stopped before every storage owner was ready. Review Resume to reuse completed roots and finish unpublished setup. Interrupted temporary folders are kept; unsupported published journals are not repaired. If you used an SD card for saved songs, reconnect that same card first."
                canary -> "Optional Canary preview for downloads in phone storage or on an SD card. Keep a backup of any existing saved songs while testing. A full restart is required. Setup does not copy or delete audio; replacing an existing copy is separate and needs temporary free space."
                else -> "Optional setup for downloads in phone storage or on an SD card. You can use it before saving your first song. Existing copies stay readable. A full restart is required. Setup does not copy or delete audio; replacing an existing copy is separate and needs temporary free space."
            },style=MaterialTheme.typography.bodyMedium)
            if(refused || optIn==PartitionOptInPhase.Refused) Text("Couldn't start. Stop saved playback and downloads and wait for storage work. If you used an SD card for saved songs, reconnect that same card before trying again.",style=MaterialTheme.typography.bodySmall)
            if(stopped) TextButton(onClick={openMuonAppInfo(context)}) {Text("Open Android app info")}
            if(!stopped) TextButton(enabled=!pending,onClick={confirm=true}) {
                Text(if(pending) "Checking storage…" else if(interrupted) "Review Resume" else "Review storage setup")
            }
        }
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text(if(interrupted) "Resume setup after restart?" else "Set up saved-song storage?")},
        text={Text(if(interrupted) "Keep the exact selected volumes. After a full restart, Muon reuses completed roots and can create a complete new root only where none was published. Interrupted temporary folders remain intact. Missing or unsupported journals inside an existing published root are protected; no copies are erased."
            else "This works with phone storage; no SD card is required. Stop saved playback and downloads first. If you previously saved songs to a card, reconnect that same card. Saved storage pauses until you fully restart Muon. If you already have saved songs, keep a backup of their originals. Setup does not move, duplicate or delete audio. Replacing an existing copy is a separate action that needs temporary free space.")},
        confirmButton={TextButton(onClick={
            confirm=false
            val accepted=if(interrupted) OfflineStore.beginPartitionSetupResume(context,true) else OfflineStore.beginPartitionOptIn(context,true)
            pending=accepted;refused=!accepted;if(!accepted) refresh++
        }) {Text(if(interrupted) "Save Resume choice" else "Save startup choice")}},
        dismissButton={TextButton(onClick={confirm=false}) {Text("Not now")}})
    return stopped
}
