package dev.avery.muon

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Visible only for an actually prepared partition store; this does not activate that backend. */
@Composable
internal fun PartitionMoveRecoveryControls(store:OfflineStore.Store) {
    PartitionMoveRecoveryPanel(store,sourceCard=false)
    if(store.card?.partition!=null) PartitionMoveRecoveryPanel(store,sourceCard=true)
}

@Composable
private fun PartitionMoveRecoveryPanel(store:OfflineStore.Store,sourceCard:Boolean) {
    val context=LocalContext.current
    val source=if(sourceCard) store.card?.partition else store.phone.partition
    if(source==null) return
    val destination=if(sourceCard) store.phone.partition else store.card?.partition
    var after by remember(source) {mutableStateOf<String?>(null)}
    var refresh by remember(source) {mutableIntStateOf(0)}
    var page by remember(source,after,refresh) {mutableStateOf<PartitionMoveRecoveryPage?>(null)}
    var unavailable by remember(source,after,refresh) {mutableStateOf(false)}
    var selected by remember(source,after,refresh) {mutableStateOf<PartitionMoveRecoverySummary?>(null)}
    var admissionFailed by remember(source,after,refresh) {mutableStateOf(false)}
    val moving=DownloadMarks.moving!=null
    val revision=DownloadMarks.revision
    LaunchedEffect(source,after,refresh,revision,moving) {
        if(!moving) {
            selected=null; page=null; unavailable=false
            try {page=withContext(Dispatchers.IO) {source.pendingMoveSummaries(after)}}
            catch(_:IOException) {unavailable=true}
        }
    }
    val direction=if(sourceCard) "SD card to phone" else "Phone to SD card"
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Interrupted moves · $direction",style=MaterialTheme.typography.titleSmall)
            Text("Review a saved move before continuing. Existing copies are kept until their replacement is verified.",
                style=MaterialTheme.typography.bodySmall)
            if(destination==null) Text("Insert the original SD card to finish these moves.",style=MaterialTheme.typography.bodySmall)
            when {
                moving -> Text("Storage work in progress…")
                unavailable -> Text("Recovery entries unavailable. Check storage; fully restart Muon if storage remains unavailable.")
                page==null -> Text("Checking interrupted moves…")
                page!!.entries.isEmpty() -> Text(if(page!!.hasMore) "No interrupted moves on this page." else "No interrupted moves.")
            }
            page?.entries?.forEachIndexed {index,entry ->
                Row(Modifier.fillMaxWidth()) {
                    Text("Move ${index+1} · ${formatBytes(entry.bytes)}",modifier=Modifier.weight(1f))
                    TextButton(enabled=!moving && destination!=null,onClick={selected=entry;admissionFailed=false}) {Text("Review")}
                }
            }
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
        AlertDialog(onDismissRequest={selected=null},title={Text("Finish interrupted move?")},
            text={Text("$direction · ${formatBytes(entry.bytes)}. Muon will verify the saved destination again before removing the remaining original copy. If verification fails, recovery stops. Keep both storage locations available.")},
            confirmButton={TextButton(enabled=!moving && destination!=null,onClick={
                selected=null
                admissionFailed=!OfflineStore.recoverMove(context,sourceCard,entry.key,entry.token,true)
            }) {Text("Verify and finish")}},
            dismissButton={TextButton(onClick={selected=null}) {Text("Not now")}})
    }
}
