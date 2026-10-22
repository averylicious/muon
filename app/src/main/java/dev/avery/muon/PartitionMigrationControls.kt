package dev.avery.muon

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Only an actually prepared partition store exposes these controls; no backend activation. */
@Composable
internal fun PartitionMigrationControls(store:OfflineStore.Store,expanded:Boolean,
    report:(StorageRecoverySection,StorageRecoveryPresentation)->Unit) {
    if(store.missingPartitionCardVolume!=null) {
        if(!expanded) return
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier=Modifier.fillMaxWidth()) {
            Text("Your selected SD card is unavailable. Phone-saved audio remains readable. Return the same card and fully restart Muon to save, optimize, delete or move copies; the card's ownership records are preserved.",
                modifier=Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium)
        }
        return
    }
    MigrationPanel(store,false,expanded,report)
    if(store.card?.partition!=null) MigrationPanel(store,true,expanded,report)
}

@Composable
private fun MigrationPanel(store:OfflineStore.Store,sourceCard:Boolean,expanded:Boolean,
    report:(StorageRecoverySection,StorageRecoveryPresentation)->Unit) {
    val context=LocalContext.current
    val source=if(sourceCard) store.card?.partition else store.phone.partition
    if(source==null) return
    val location=if(sourceCard) "SD card" else "Phone"
    val moving=DownloadMarks.moving!=null
    val revision=DownloadMarks.revision
    var after by remember(source) {mutableStateOf<String?>(null)}
    var refresh by remember(source) {mutableIntStateOf(0)}
    var page by remember(source,after,refresh) {mutableStateOf<MigrationRecoveryPage?>(null)}
    var unavailable by remember(source,after,refresh) {mutableStateOf(false)}
    var uncertain by remember(source,after,refresh) {mutableStateOf(false)}
    var selected by remember(source,after,refresh) {mutableStateOf<MigrationRecord?>(null)}
    var admissionFailed by remember(source,after,refresh) {mutableStateOf(false)}
    var attempt by remember(store) {mutableStateOf(store.migrationAttempt?.takeIf {it.sourceCard==sourceCard})}
    var progress by remember(store) {mutableStateOf(attempt?.control?.progress ?: store.migrationStatus?.takeIf {it.sourceCard==sourceCard}?.progress)}
    var cancelRequested by remember(source,moving) {mutableStateOf(false)}
    LaunchedEffect(store,moving,sourceCard) {
        do {
            val current=store.migrationAttempt
            attempt=current?.takeIf {it.sourceCard==sourceCard}
            progress=attempt?.control?.progress ?: store.migrationStatus?.takeIf {it.sourceCard==sourceCard}?.progress
            if(current==null) break
            delay(500) // One current scalar snapshot, no observer queue or retained progress history.
        } while(true)
    }
    LaunchedEffect(source,after,refresh,revision,moving) {
        selected=null
        if(!moving) {
            page=null;unavailable=false;uncertain=false
            try {page=withContext(Dispatchers.IO) {source.pendingMigrationSummaries(after)}}
            catch(failure:Exception) {
                if(failure is CancellationException) throw failure
                unavailable=true
                uncertain=failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain || failure is SavedCoverCloseUncertain
            }
        }
    }
    // Ready is a completed success, not an outstanding recovery task. Retain the backend status;
    // active work and every non-success result remain reviewable, including neutral cancellation.
    val presentation=storageRecoveryPresentation(pageLoaded=page!=null,firstPage=after==null,
        hasEntries=page?.entries?.isNotEmpty()==true,hasMore=page?.hasMore==true,
        working=attempt!=null,progress=storageMigrationNeedsReview(progress?.phase,attempt!=null),
        unavailable=unavailable,selected=selected!=null,admissionFailed=admissionFailed,
        uncertain=uncertain || progress?.phase==MigrationWorkPhase.Uncertain)
    val section=if(sourceCard) StorageRecoverySection.CardOptimization else StorageRecoverySection.PhoneOptimization
    SideEffect {report(section,presentation)}
    if(!expanded || !storageRecoveryDetailsVisible(presentation)) return
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Saved storage optimization · $location",style=MaterialTheme.typography.titleSmall)
            Text("Optimization creates a verified replacement and needs temporary free space. Originals stay saved; interrupted replacements are kept for review.",
                style=MaterialTheme.typography.bodySmall)
            progress?.let {value ->
                Text(when(value.phase) {
                    MigrationWorkPhase.Waiting -> "Waiting for storage worker…"
                    MigrationWorkPhase.Preparing -> "Checking saved audio and temporary space…"
                    MigrationWorkPhase.Running -> "Copied ${formatBytes(value.writtenBytes)} of ${formatBytes(value.copyBytes)} · verification still required"
                    MigrationWorkPhase.Ready -> if(attempt!=null) "Replacement verified; finishing…" else "Replacement verified. Original copy kept."
                    MigrationWorkPhase.Cancelled -> "Optimization cancelled. Original copy kept; review any interrupted replacement below."
                    MigrationWorkPhase.Expired -> "Optimization reached its time limit. Original copy kept; review before retrying."
                    MigrationWorkPhase.Failed -> "Optimization did not finish. Stop saved playback and check storage and temporary space before retrying."
                    MigrationWorkPhase.Uncertain -> "Storage ownership is uncertain. Fully restart Muon before reviewing recovery; existing copies are kept."
                },style=MaterialTheme.typography.bodySmall)
            }
            attempt?.let {current ->
                TextButton(enabled=!cancelRequested,onClick={cancelRequested=OfflineStore.cancelPartitionMigration(current)}) {
                    Text(if(cancelRequested) "Cancelling…" else "Cancel optimization")
                }
                if(cancelRequested) Text("Waiting for the current storage operation to stop safely.",style=MaterialTheme.typography.bodySmall)
            }
            when {
                moving -> Text("Other saved-storage actions are unavailable until the worker finishes.",style=MaterialTheme.typography.bodySmall)
                unavailable -> Text("Interrupted optimizations unavailable. Check storage; fully restart Muon if it remains unavailable.")
                page==null -> Text("Checking interrupted optimizations…")
                page!!.entries.isEmpty() -> Text("No interrupted optimizations on this page.")
            }
            page?.entries?.forEachIndexed {index,entry ->
                Row(Modifier.fillMaxWidth()) {
                    Text("Interrupted optimization ${index+1}",modifier=Modifier.weight(1f))
                    TextButton(enabled=!moving,onClick={selected=entry;admissionFailed=false}) {Text("Review")}
                }
            }
            if(admissionFailed) Text("Couldn't start. Wait for playback or saved-storage work to finish, then refresh.",
                color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(enabled=!moving,onClick={after=null;refresh++}) {Text("Refresh")}
                page?.takeIf {it.hasMore && it.after!=null}?.let {current ->
                    TextButton(enabled=!moving,onClick={after=current.after}) {Text("Next page")}
                }
            }
        }
    }
    selected?.let {entry ->
        AlertDialog(onDismissRequest={selected=null},title={Text("Retry interrupted optimization?")},
            text={Text("$location. Recheck this interrupted attempt and create a fresh replacement. Originals and earlier partial copies stay saved. Keep storage available and enough space for another copy. Nothing is deleted.")},
            confirmButton={TextButton(enabled=!moving,onClick={
                selected=null
                admissionFailed=!OfflineStore.beginPartitionMigration(context,sourceCard,entry.ticket.allocation.key,true,entry)
            }) {Text("Verify and retry")}},
            dismissButton={TextButton(onClick={selected=null}) {Text("Not now")}})
    }
}
