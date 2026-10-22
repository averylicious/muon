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

/** One contextual entry point. Child owners stay composed for their existing bounded reads even
 * while details are closed; opening this UI never grants consent or starts recovery work. */
@Composable
internal fun PartitionStorageControls(store:OfflineStore.Store) {
    var expanded by remember(store) {mutableStateOf(false)}
    val presentations=remember(store,store.card?.partition) {mutableStateMapOf<StorageRecoverySection,StorageRecoveryPresentation>()}
    val expected=buildList {
        add(StorageRecoverySection.PhoneMoves);add(StorageRecoverySection.PhoneDeletes)
        if(store.card?.partition!=null) {add(StorageRecoverySection.CardMoves);add(StorageRecoverySection.CardDeletes)}
        if(store.missingPartitionCardVolume==null) {
            add(StorageRecoverySection.PhoneOptimization)
            if(store.card?.partition!=null) add(StorageRecoverySection.CardOptimization)
        }
    }
    val states=expected.map {presentations[it] ?: StorageRecoveryPresentation.Checking}+
        if(store.missingPartitionCardVolume!=null) listOf(StorageRecoveryPresentation.Unavailable) else emptyList()
    val attention=storageRecoveryAttention(states,working=DownloadMarks.moving!=null)
    if(attention==StorageRecoveryPresentation.Checking && !expanded) {
        Text("Checking saved storage…",style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    } else if(attention!=StorageRecoveryPresentation.Empty || expanded) {
        val colors=MaterialTheme.colorScheme
        val warning=attention in setOf(StorageRecoveryPresentation.Unavailable,StorageRecoveryPresentation.Uncertain)
        Surface(shape=MaterialTheme.shapes.large,color=if(warning) colors.errorContainer else colors.secondaryContainer,
            contentColor=if(warning) colors.onErrorContainer else colors.onSecondaryContainer,
            modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(when(attention) {
                    StorageRecoveryPresentation.Uncertain -> "Saved storage needs a restart"
                    StorageRecoveryPresentation.Unavailable -> if(store.missingPartitionCardVolume!=null) "Saved-storage card unavailable" else "Saved storage needs attention"
                    StorageRecoveryPresentation.Working -> "Updating saved storage"
                    StorageRecoveryPresentation.Review -> "Saved storage needs review"
                    else -> "Saved storage"
                },style=MaterialTheme.typography.titleMedium)
                Text(when(attention) {
                    StorageRecoveryPresentation.Uncertain -> "Existing copies are kept. Review the next step before retrying."
                    StorageRecoveryPresentation.Unavailable -> if(store.missingPartitionCardVolume!=null) "Phone copies remain readable. Reconnect the same card and review."
                        else "Storage couldn't be checked or an action couldn't start. Review the details."
                    StorageRecoveryPresentation.Working -> "Review progress or cancel an optimization."
                    StorageRecoveryPresentation.Review -> "Review interrupted work or the last operation's result."
                    StorageRecoveryPresentation.Checking -> "Checking saved storage…"
                    StorageRecoveryPresentation.Empty -> "No interrupted storage work."
                },style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={expanded=!expanded}) {Text(if(expanded) "Close details" else "Review")}
            }
        }
    }
    val report:(StorageRecoverySection,StorageRecoveryPresentation)->Unit={section,presentation ->
        if(presentations[section]!=presentation) presentations[section]=presentation
    }
    PartitionMoveRecoveryControls(store,expanded,report)
    PartitionMigrationControls(store,expanded,report)
}

@Composable
private fun PartitionMoveRecoveryControls(store:OfflineStore.Store,expanded:Boolean,
    report:(StorageRecoverySection,StorageRecoveryPresentation)->Unit) {
    PartitionStorageRecoveryPanel(store,sourceCard=false,delete=false,expanded=expanded,report=report)
    PartitionStorageRecoveryPanel(store,sourceCard=false,delete=true,expanded=expanded,report=report)
    if(store.card?.partition!=null) {
        PartitionStorageRecoveryPanel(store,sourceCard=true,delete=false,expanded=expanded,report=report)
        PartitionStorageRecoveryPanel(store,sourceCard=true,delete=true,expanded=expanded,report=report)
    }
}

@Composable
private fun PartitionStorageRecoveryPanel(store:OfflineStore.Store,sourceCard:Boolean,delete:Boolean,expanded:Boolean,
    report:(StorageRecoverySection,StorageRecoveryPresentation)->Unit) {
    val context=LocalContext.current
    val source=if(sourceCard) store.card?.partition else store.phone.partition
    if(source==null) return
    val destination=if(sourceCard) store.phone.partition else store.card?.partition
    var after by remember(source) {mutableStateOf<String?>(null)}
    var refresh by remember(source) {mutableIntStateOf(0)}
    var page by remember(source,after,refresh) {mutableStateOf<PartitionStorageRecoveryPage?>(null)}
    var unavailable by remember(source,after,refresh) {mutableStateOf(false)}
    var uncertain by remember(source,after,refresh) {mutableStateOf(false)}
    var selected by remember(source,after,refresh) {mutableStateOf<PartitionStorageRecoverySummary?>(null)}
    var admissionFailed by remember(source,after,refresh) {mutableStateOf(false)}
    val moving=DownloadMarks.moving!=null
    val revision=DownloadMarks.revision
    LaunchedEffect(source,after,refresh,revision,moving) {
        if(!moving) {
            selected=null; page=null; unavailable=false;uncertain=false
            try {page=withContext(Dispatchers.IO) {if(delete) source.pendingDeleteSummaries(after) else source.pendingMoveSummaries(after)}}
            catch(failure:Exception) {
                if(failure is CancellationException) throw failure
                unavailable=true
                uncertain=failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain || failure is SavedCoverCloseUncertain
            }
        }
    }
    val presentation=storageRecoveryPresentation(pageLoaded=page!=null,firstPage=after==null,
        hasEntries=page?.entries?.isNotEmpty()==true,hasMore=page?.hasMore==true,
        unavailable=unavailable,selected=selected!=null,admissionFailed=admissionFailed,uncertain=uncertain)
    val section=if(delete) {if(sourceCard) StorageRecoverySection.CardDeletes else StorageRecoverySection.PhoneDeletes}
        else if(sourceCard) StorageRecoverySection.CardMoves else StorageRecoverySection.PhoneMoves
    SideEffect {report(section,presentation)}
    if(!expanded || !storageRecoveryDetailsVisible(presentation)) return
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
            text={Text(if(delete) "$direction · ${formatBytes(entry.bytes)}. Verify, then delete the remaining saved files and their record. This cannot be undone. Songs in Tauon are unchanged."
                else "$direction · ${formatBytes(entry.bytes)}. Verify the destination copy again, then remove the remaining original. Keep both storage locations available. Recovery stops if verification fails.")},
            confirmButton={TextButton(enabled=!moving && (delete || destination!=null) && entry.action!=PartitionRecoveryAction.UnsupportedDelete,onClick={
                selected=null
                admissionFailed=!(if(delete) OfflineStore.recoverDelete(context,sourceCard,entry.key,entry.token,true)
                    else OfflineStore.recoverMove(context,sourceCard,entry.key,entry.token,true))
            }) {Text(if(delete) "Verify and delete" else "Verify and finish")}},
            dismissButton={TextButton(onClick={selected=null}) {Text("Not now")}})
    }
}
