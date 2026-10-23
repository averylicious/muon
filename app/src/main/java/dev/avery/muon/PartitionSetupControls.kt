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
                    "Setup couldn't be confirmed. Existing copies are kept. Force stop and reopen Muon before reviewing again."
                pending -> "Saving setup choice… Saved storage may already be paused. Wait for confirmation before restarting."
                stopped -> "Setup choice saved. Force stop Muon in Android App info, then reopen it. Existing copies are kept."
                interrupted -> "Setup was interrupted. Reconnect the same SD card, if used, then review Resume. Existing files are kept."
                else -> "Storage designed to use less memory as your saved library grows. Restart required."
            },style=MaterialTheme.typography.bodyMedium)
            if(refused || optIn==PartitionOptInPhase.Refused) Text("Couldn't start. Stop saved playback and downloads, wait for storage work, and reconnect the same SD card if used.",style=MaterialTheme.typography.bodySmall)
            if(stopped) TextButton(onClick={openMuonAppInfo(context)}) {Text("Open Android app info")}
            if(!stopped) TextButton(enabled=!pending,onClick={confirm=true}) {
                Text(if(pending) "Checking storage…" else if(interrupted) "Review Resume" else "Review setup")
            }
        }
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text(if(interrupted) "Resume setup after restart?" else "Set up saved-song storage?")},
        text={Text(if(interrupted) "Keep the same storage locations. Save the Resume choice, then Force stop and reopen Muon. Existing audio and unfinished files are kept; missing or unsupported records won't be reset."
            else "Setup doesn't move, copy or delete audio. An SD card is optional; reconnect the same card if you used one for saved copies. Stop saved playback and downloads. Save this choice, then Force stop and reopen Muon. Keep a backup.")},
        confirmButton={TextButton(onClick={
            confirm=false
            val accepted=if(interrupted) OfflineStore.beginPartitionSetupResume(context,true) else OfflineStore.beginPartitionOptIn(context,true)
            pending=accepted;refused=!accepted;if(!accepted) refresh++
        }) {Text(if(interrupted) "Save Resume choice" else "Save setup choice")}},
        dismissButton={TextButton(onClick={confirm=false}) {Text("Not now")}})
    return stopped
}
