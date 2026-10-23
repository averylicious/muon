package dev.avery.muon

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Update storage format (formerly Optimize) for one user-selected saved locator; no whole-library
 * migration or arbitrary key input. The row menu offers it only after a confirmed check; this dialog
 * checks again before anything can start.
 */
@Composable
internal fun SavedMigrationDialog(entry:SavedEntry,onDismiss:()->Unit) {
    val context=LocalContext.current
    val store=OfflineStore.current()
    val missingCard=store?.missingPartitionCardVolume!=null
    val source=if(entry.ref.shelf==SavedShelf.Card) store?.card?.partition else store?.phone?.partition
    var available by remember(entry.ref,store) {mutableStateOf<Boolean?>(null)}
    var failed by remember(entry.ref,store) {mutableStateOf(false)}
    LaunchedEffect(entry.ref,store) {
        try {available=if(missingCard || source==null || !entry.ref.migrationLocator()) false else
            withContext(Dispatchers.IO) {source.freshMigrationAvailable(entry.ref.key)}}
        catch(failure:Exception) {if(failure is CancellationException) throw failure;failed=true}
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Update storage format?")},
        text={Text(when {
            missingCard -> "Your SD card isn't available. Phone copies still play. Put the same card back, then fully restart Muon before updating copies."
            failed -> "This copy couldn't be checked. Stop playback from this list and try again."
            available==null -> "Checking this copy…"
            available==false -> "This copy can't be updated now. Nothing was changed."
            else -> "Muon makes a new copy of “${entry.title()}” in the current format and checks it before replacing this one. It needs temporary space for a second copy. You can follow or cancel it in Settings."
        })},
        confirmButton={TextButton(enabled=!missingCard && available==true && !failed && DownloadMarks.moving==null,onClick={
            if(OfflineStore.beginSavedMigration(context,entry.ref,true)) onDismiss() else failed=true
        }) {Text("Update")}},
        dismissButton={TextButton(onClick=onDismiss) {Text("Not now")}})
}
