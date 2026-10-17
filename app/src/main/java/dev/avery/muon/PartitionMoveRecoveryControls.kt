package dev.avery.muon

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Visible only for an actually prepared partition store; this does not activate that backend. */
@Composable
internal fun PartitionMoveRecoveryControls(store:OfflineStore.Store) {
    PartitionStorageRecoveryPanel(store,sourceCard=false,delete=false)
    PartitionStorageRecoveryPanel(store,sourceCard=false,delete=true)
    if(store.card?.partition!=null) {
        PartitionStorageRecoveryPanel(store,sourceCard=true,delete=false)
        PartitionStorageRecoveryPanel(store,sourceCard=true,delete=true)
    }
}

@Composable
private fun PartitionStorageRecoveryPanel(store:OfflineStore.Store,sourceCard:Boolean,delete:Boolean) {
    val context=LocalContext.current
    val source=if(sourceCard) store.card?.partition else store.phone.partition
    if(source==null) return
    val destination=if(sourceCard) store.phone.partition else store.card?.partition
    var after by remember(source) {mutableStateOf<String?>(null)}
    var refresh by remember(source) {mutableIntStateOf(0)}
    var page by remember(source,after,refresh) {mutableStateOf<PartitionStorageRecoveryPage?>(null)}
    var unavailable by remember(source,after,refresh) {mutableStateOf(false)}
    var selected by remember(source,after,refresh) {mutableStateOf<PartitionStorageRecoverySummary?>(null)}
    var admissionFailed by remember(source,after,refresh) {mutableStateOf(false)}
    val moving=DownloadMarks.moving!=null
    val revision=DownloadMarks.revision
    LaunchedEffect(source,after,refresh,revision,moving) {
        if(!moving) {
            selected=null; page=null; unavailable=false
            try {page=withContext(Dispatchers.IO) {if(delete) source.pendingDeleteSummaries(after) else source.pendingMoveSummaries(after)}}
            catch(failure:Exception) {if(failure is CancellationException) throw failure;unavailable=true}
        }
    }
    val direction=if(delete) {if(sourceCard) "On SD card" else "On phone"}
        else if(sourceCard) "SD card to phone" else "Phone to SD card"
    val operation=if(delete) "deletions" else "moves"
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Interrupted $operation · $direction",style=MaterialTheme.typography.titleSmall)
            Text(if(delete) "Review an interrupted removal before deleting the remaining saved files."
                else "Review a saved move before continuing. Existing copies are kept until their replacement is verified.",
                style=MaterialTheme.typography.bodySmall)
            if(!delete && destination==null) Text("Insert the original SD card to finish these moves.",style=MaterialTheme.typography.bodySmall)
            when {
                moving -> Text("Storage work in progress…")
                unavailable -> Text("Recovery entries unavailable. Check storage; fully restart Muon if storage remains unavailable.")
                page==null -> Text("Checking interrupted $operation…")
                page!!.entries.isEmpty() -> Text(if(page!!.hasMore) "No interrupted $operation on this page." else "No interrupted $operation.")
            }
            page?.entries?.forEachIndexed {index,entry ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${if(delete) "Removal" else "Move"} ${index+1} · ${formatBytes(entry.bytes)}",modifier=Modifier.weight(1f))
                    TextButton(enabled=!moving && (delete || destination!=null) && entry.action!=PartitionRecoveryAction.UnsupportedDelete,onClick={selected=entry;admissionFailed=false}) {Text("Review")}
                }
            }
            if(page?.entries?.any {it.action==PartitionRecoveryAction.UnsupportedDelete}==true)
                Text("An older interrupted removal needs further recovery support. Its remaining files are kept.",style=MaterialTheme.typography.bodySmall)
            if(admissionFailed) Text("Couldn't start recovery. Wait for other storage work or refresh this list.",
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
        AlertDialog(onDismissRequest={selected=null},title={Text(if(delete) "Finish interrupted deletion?" else "Finish interrupted move?")},
            text={Text(if(delete) "$direction · ${formatBytes(entry.bytes)}. Muon will verify the selected saved copy before removing its remaining files and saved record. This cannot be undone. Songs in Tauon are unchanged."
                else "$direction · ${formatBytes(entry.bytes)}. Muon will verify the saved destination again before removing the remaining original copy. If verification fails, recovery stops. Keep both storage locations available.")},
            confirmButton={TextButton(enabled=!moving && (delete || destination!=null) && entry.action!=PartitionRecoveryAction.UnsupportedDelete,onClick={
                selected=null
                admissionFailed=!(if(delete) OfflineStore.recoverDelete(context,sourceCard,entry.key,entry.token,true)
                    else OfflineStore.recoverMove(context,sourceCard,entry.key,entry.token,true))
            }) {Text(if(delete) "Verify and delete" else "Verify and finish")}},
            dismissButton={TextButton(onClick={selected=null}) {Text("Not now")}})
    }
}
