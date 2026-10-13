package dev.avery.muon

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One user-selected saved locator; no whole-library migration or arbitrary key input. */
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
    AlertDialog(onDismissRequest=onDismiss,title={Text("Optimize this saved copy?")},
        text={Text(when {
            missingCard -> "Your selected SD card is unavailable. Phone-saved audio remains readable. Return the same card and Force stop/reopen Muon before optimizing copies; its ownership records are kept."
            failed -> "This saved copy could not be checked. Stop saved playback and check storage before trying again."
            available==null -> "Checking saved storage…"
            available==false -> "This copy is already using current storage or cannot be optimized by this path. Existing audio is kept. Review interrupted optimizations in Settings if needed."
            else -> "“${entry.title()}”. Muon will create and verify a replacement, keeping this original and its saved record. Temporary free space is needed for another copy. Progress, Cancel and interrupted-attempt recovery are in Settings. This does not confirm a match with Tauon's current song."
        })},
        confirmButton={TextButton(enabled=!missingCard && available==true && !failed && DownloadMarks.moving==null,onClick={
            if(OfflineStore.beginSavedMigration(context,entry.ref,true)) onDismiss() else failed=true
        }) {Text("Optimize copy")}},
        dismissButton={TextButton(onClick=onDismiss) {Text("Not now")}})
}

