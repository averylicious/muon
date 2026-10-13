package dev.avery.muon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal const val REVIEW_SAVED_STORAGE_ACTION="dev.avery.muon.action.REVIEW_SAVED_STORAGE"
private const val REVIEW_SHORTCUT_ID="review-saved-storage"

/** Fixed own-activity entry; no new exported component, arbitrary configuration or path extras.
 * The same disabled activation gate controls UI, shortcut and production backend selection. */
internal fun installSavedStorageReviewShortcut(context:Context) {
    if(!PARTITION_OPT_IN_AVAILABLE) return
    try {
        val entry=StorageReviewEntry.from(context);val credential=entry.prepare()
        context.getSystemService(ShortcutManager::class.java)?.addDynamicShortcuts(listOf(
            ShortcutInfo.Builder(context,REVIEW_SHORTCUT_ID).setShortLabel("Review saved storage")
                .setLongLabel("Review saved storage before startup")
                .setIntent(entry.intent(context,credential)).build()))
    } catch(_:java.io.IOException) { /* Unknown private credential write never publishes authority. */ }
      catch(_:IllegalArgumentException) { /* Unsupported launcher; ordinary Settings opt-in remains. */ }
      catch(_:IllegalStateException) { /* User locked/rate limited; never infer shortcut publication. */ }
      catch(_:SecurityException) { /* No extra permission or launcher setting is requested. */ }
}

internal fun openMuonAppInfo(context:Context) {
    val intent=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName))
    if(context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {context.startActivity(intent)} catch(_:android.content.ActivityNotFoundException) { /* Instructions remain visible. */ }
}

/** This screen deliberately does not create MuonApp, LibraryModel or a MediaController. Actual
 * preflight/choice writes run on the serial storage worker; UI reads only bounded preferences and
 * fixed status. Returning to the ordinary icon in the same process cannot reset storage admission. */
@Composable
internal fun PartitionStartupReviewScreen(claimed:Boolean,darkTheme:Boolean) {
    val context=LocalContext.current
    val appearance=rememberAppearanceSettings()
    var confirm by remember {mutableStateOf(false)}
    var pending by remember {mutableStateOf(false)}
    var refused by remember {mutableStateOf(false)}
    var refresh by remember {mutableIntStateOf(0)}
    val availability by OfflineStore.availability.collectAsState()
    val revision=DownloadMarks.revision
    val prefs=remember {context.getSharedPreferences("storage",Context.MODE_PRIVATE)}
    val backend=remember(refresh,revision) {runCatching {StorageBackendSelection.read(prefs)}.getOrNull()}
    var phase by remember(refresh,revision) {mutableStateOf(OfflineStore.startupReviewPhase)}
    LaunchedEffect(pending) {
        if(pending) do {
            delay(250);phase=OfflineStore.startupReviewPhase
            if(phase!=PartitionOptInPhase.New && phase!=PartitionOptInPhase.Checking) {pending=false;refresh++}
        } while(pending)
    }
    MuonTheme(darkTheme=darkTheme,dynamicColor=appearance.palette==PaletteChoice.MaterialYou,
        blackSurfaces=useBlackSurfaces(appearance.amoled,darkTheme)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("Review saved storage",style=MaterialTheme.typography.headlineSmall)
                Text(when {
                    !claimed -> "Normal storage startup already owns this process. Use Android App info to Force stop Muon, then long-press its app icon and choose Review saved storage."
                    phase==PartitionOptInPhase.Checking || pending -> "Checking existing storage and saving the startup choice. Wait for confirmation; keep the original SD card connected."
                    phase==PartitionOptInPhase.RestartRequired -> "The startup choice is saved. Use Android App info to Force stop Muon, then reopen its ordinary app icon. Existing originals are kept."
                    phase==PartitionOptInPhase.Uncertain -> "The check or saved choice could not be confirmed. Existing originals are kept. Force stop Muon before reviewing again; do not retry in this process."
                    phase==PartitionOptInPhase.Refused || refused -> "Could not start. Keep the original SD card connected. Missing or unsupported old cache records are not repaired automatically. Force stop Muon before another review."
                    backend==StorageBackendSelection.Legacy -> "Review optimized storage without loading the old full native index. This does not start playback, downloads or a server connection. Reconnect the original SD card, including a card previously used for saved songs."
                    backend==StorageBackendSelection.PartitionV1 -> "A storage choice already exists. Interrupted setup requires explicit Resume below. Otherwise Force stop Muon and reopen its ordinary app icon to validate the saved choice."
                    else -> "The saved storage choice is unsupported. Existing data is protected; it will not be reset or replaced automatically."
                })
                if(claimed && backend==StorageBackendSelection.Legacy && phase==PartitionOptInPhase.New && !refused)
                    Button(enabled=!pending,onClick={confirm=true}) {Text("Review opt-in")}
                if(claimed && backend==StorageBackendSelection.PartitionV1)
                    PartitionSetupControls(null,availability)
                TextButton(onClick={openMuonAppInfo(context)}) {Text("Open Android app info")}
            }
            if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Use optimized saved storage?")},
                text={Text("Save the exact current volumes for startup after a full Force stop and restart. Originals remain readable; moving or deleting them stays explicit. No bulk migration or deletion occurs. Optimizing each copy needs temporary free space. Obsolete old index metadata may remain; this is not secure erasure. Missing or unsupported native bootstrap is refused without creating or repairing it.")},
                confirmButton={TextButton(onClick={
                    confirm=false;pending=OfflineStore.beginStartupReviewOptIn(context,true);refused=!pending
                }) {Text("Save startup choice")}},dismissButton={TextButton(onClick={confirm=false}) {Text("Not now")}})
        }
    }
}
