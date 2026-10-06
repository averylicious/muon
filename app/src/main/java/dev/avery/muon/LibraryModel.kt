package dev.avery.muon

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("connection", 0)
    var address by mutableStateOf(prefs.getString("origin", "").orEmpty())
    var endpoint by mutableStateOf<ServerEndpoint?>(null); private set
    var playlists by mutableStateOf<List<TauonPlaylist>>(emptyList()); private set
    var tracksByPlaylist by mutableStateOf<Map<String, List<TauonTrack>>>(emptyMap()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf(""); private set
    /** Tauon could not be reached, so the library is only the saved copies (#112, #213). */
    var offline by mutableStateOf(false); private set
    /** Every saved copy, each its own Unverified entry (#213); read by [refreshSaved], off the main thread. */
    internal var saved by mutableStateOf<List<SavedEntry>>(emptyList()); private set
    private var savedLoad: kotlinx.coroutines.Job? = null

    /** Reads the saved copies again, as when Saved copies opens or one is added or removed. */
    fun refreshSaved() {
        savedLoad?.cancel()
        savedLoad = viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { OfflineStore.savedEntries(getApplication<Application>()) }.getOrNull() }
            if (found != null) saved = found
        }
    }
    private val loads = LibraryLoads(viewModelScope) { busy = it }
    val allTracks: List<TauonTrack> get() = tracksByPlaylist.values.flatten().distinctBy { it.id }
    init { if (address.isNotBlank()) connect() }
    fun connect() {
        if (busy) return
        // The download store reports to the main thread, so it is made here before anything reads it.
        OfflineStore.get(getApplication<Application>())
        loads.start {
            error = null
            try {
                val e = ServerEndpoint.parse(address)
                // Android 17: without local network access a connection would only time out, so it is
                // not attempted; the app asks for access instead (LocalNetwork.kt).
                val allowed = localNetworkGranted(getApplication<Application>())
                LocalNetworkState.granted = allowed
                if (!allowed) throw LocalNetworkDenied()
                val api = TauonApi(e)
                progress = "Connecting to Tauon…"; api.connect()
                ensureCurrent()
                val lists = api.playlists()
                ensureCurrent()
                val loaded = linkedMapOf<String, List<TauonTrack>>()
                var firstFailure: Exception? = null
                lists.forEachIndexed { i, list ->
                    ensureCurrent()
                    progress = "Loading playlists ${i + 1} / ${lists.size}"
                    // One playlist failing no longer throws the rest away (#53).
                    try { loaded[list.id] = api.tracks(list.id) }
                    catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { if (firstFailure == null) firstFailure = failure }
                }
                ensureCurrent()
                // Only this server's own last library can fill a gap; never another's, nor the offline one.
                val previous = tracksByPlaylist.takeIf { endpoint?.origin == e.origin && !offline }
                val load = combineLoad(lists, loaded, previous)
                // Nothing at all to show: handled as a failed connection, exactly as before.
                if (lists.isNotEmpty() && load.tracks.isEmpty()) throw firstFailure ?: IllegalStateException("No playlists loaded")
                endpoint = e; playlists = load.playlists; tracksByPlaylist = load.tracks; offline = false; OfflineStore.offline = false
                address = e.origin; prefs.edit().putString("origin", e.origin).apply()
                if (load.failed > 0) error = partialLoadMessage(load.failed, lists.size)
                progress = "Connected · ${allTracks.size} tracks"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureCurrent()
                // With no library from this sitting, the saved copies still play, so they are shown
                // rather than the connect screen. A library already loaded stays as it was.
                val server = if (endpoint == null || offline) runCatching { ServerEndpoint.parse(address) }.getOrNull() else null
                val kept = server?.let { withContext(Dispatchers.IO) { OfflineStore.savedEntries(getApplication<Application>()) } }.orEmpty()
                ensureCurrent()
                if (server != null && kept.any { it.complete }) {
                    showOffline(server, kept)
                    if (e is LocalNetworkDenied) error = "Muon needs your permission to reach Tauon. Your saved copies still play."
                }
                else {
                    error = friendlyError(e)
                    progress = if (endpoint != null) "Showing last loaded library" else "Not connected"
                }
            }
        }
    }
    /**
     * The saved copies in place of the library (#213). No live song is listed: none could be streamed, and
     * a copy kept under a track number is not shown as that song. Each copy is its own Unverified entry.
     */
    private fun showOffline(server: ServerEndpoint, entries: List<SavedEntry>) {
        endpoint = server; playlists = emptyList(); tracksByPlaylist = emptyMap(); offline = true
        saved = entries
        OfflineStore.offline = true
        error = OFFLINE_NOTE
        progress = "Offline · ${entries.size} saved ${if (entries.size == 1) "copy" else "copies"}"
    }

    /**
     * Opens the downloads without a server, from the Connect screen: after Disconnect there is no saved
     * address to fail over from, but the songs downloaded from it still play. Remembers that server, so
     * Retry reconnects to it.
     */
    fun listenOffline() {
        if (busy) return
        OfflineStore.get(getApplication<Application>())
        loads.start {
            try {
                val (origin, entries) = withContext(Dispatchers.IO) {
                    OfflineStore.savedLibrary(getApplication<Application>())
                } ?: return@start
                ensureCurrent()
                val server = ServerEndpoint.parse(origin)
                address = server.origin; prefs.edit().putString("origin", server.origin).apply()
                showOffline(server, entries)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ensureCurrent(); error = friendlyError(e) }
        }
    }

    fun disconnect() {
        loads.cancel(); endpoint = null; playlists = emptyList(); tracksByPlaylist = emptyMap(); offline = false
        OfflineStore.offline = false
        prefs.edit().clear().apply(); address = ""; error = null; progress = ""
    }
}
/** Shown on the library while it holds only saved copies; Retry asks Tauon again. */
internal const val OFFLINE_NOTE = "Tauon isn't reachable. Your saved copies still play."

/** Connecting was not attempted: Android 17's local network access is missing. */
internal class LocalNetworkDenied : Exception("Muon needs your permission to reach Tauon on your local network.")

/** Parser diagnostics may contain the entire response; they are not UI copy. */
private const val INVALID_TAUON_DATA = "Tauon returned unreadable data. Check its library and retry."
private const val CONNECTION_FAILURE = "Connection failed. Check your LAN connection and retry."
private const val MAX_CONNECTION_ERROR_LENGTH = 512

fun friendlyError(e: Throwable): String = when (e) {
    is java.io.InterruptedIOException -> "Tauon did not respond. Check the server, LAN firewall and VPN LAN access, then retry."
    is java.net.ConnectException -> "Cannot reach Tauon. Enable remote control, restart Tauon, and check the address."
    is java.net.UnknownHostException -> "Server address could not be resolved."
    is org.json.JSONException -> INVALID_TAUON_DATA
    // Preserve useful short validation/HTTP errors without rendering arbitrary response-sized text.
    else -> e.message?.takeIf { it.length <= MAX_CONNECTION_ERROR_LENGTH && it.isNotBlank() }
        ?: CONNECTION_FAILURE
}
