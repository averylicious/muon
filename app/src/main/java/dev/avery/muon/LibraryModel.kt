package dev.avery.muon

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    /** Tauon could not be reached, so the library is only what was downloaded from it (#112). */
    var offline by mutableStateOf(false); private set
    private var job: Job? = null
    val allTracks: List<TauonTrack> get() = tracksByPlaylist.values.flatten().distinctBy { it.id }
    init { if (address.isNotBlank()) connect() }
    fun connect() {
        if (busy) return
        // The download store reports to the main thread, so it is made here before anything reads it.
        OfflineStore.get(getApplication<Application>())
        job = viewModelScope.launch {
            busy = true; error = null
            try {
                val e = ServerEndpoint.parse(address)
                val api = TauonApi(e)
                progress = "Connecting to Tauon…"; api.connect()
                val lists = api.playlists()
                val loaded = linkedMapOf<String, List<TauonTrack>>()
                var firstFailure: Exception? = null
                lists.forEachIndexed { i, list ->
                    progress = "Loading playlists ${i + 1} / ${lists.size}"
                    // One playlist failing no longer throws the rest away (#53).
                    try { loaded[list.id] = api.tracks(list.id) }
                    catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { if (firstFailure == null) firstFailure = failure }
                }
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
                // With no library from this sitting, what was downloaded from the saved server is
                // still playable, so it is shown rather than the connect screen. A library already
                // loaded stays as it was.
                val saved = if (endpoint == null || offline) runCatching { ServerEndpoint.parse(address) }.getOrNull() else null
                val kept = saved?.let { withContext(Dispatchers.IO) { OfflineStore.downloadedSongs(getApplication<Application>(), it.origin) } }.orEmpty()
                if (saved != null && kept.isNotEmpty()) showOffline(saved, kept)
                else {
                    error = friendlyError(e)
                    progress = if (endpoint != null) "Showing last loaded library" else "Not connected"
                }
            } finally { busy = false }
        }
    }
    private fun showOffline(server: ServerEndpoint, songs: List<TauonTrack>) {
        endpoint = server; playlists = emptyList(); tracksByPlaylist = mapOf(OFFLINE_LIBRARY to songs); offline = true
        OfflineStore.offline = true
        error = OFFLINE_NOTE
        progress = "Offline · ${songs.size} ${if (songs.size == 1) "song" else "songs"} on this phone"
    }

    /**
     * Opens the downloads without a server, from the Connect screen: after Disconnect there is no saved
     * address to fail over from, but the songs downloaded from it still play. Remembers that server, so
     * Retry reconnects to it.
     */
    fun listenOffline() {
        if (busy) return
        OfflineStore.get(getApplication<Application>())
        job = viewModelScope.launch {
            busy = true
            try {
                val (origin, songs) = withContext(Dispatchers.IO) {
                    OfflineStore.downloadedLibrary(getApplication<Application>())
                } ?: return@launch
                val server = ServerEndpoint.parse(origin)
                address = server.origin; prefs.edit().putString("origin", server.origin).apply()
                showOffline(server, songs)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = friendlyError(e) }
            finally { busy = false }
        }
    }

    fun disconnect() {
        job?.cancel(); busy = false; endpoint = null; playlists = emptyList(); tracksByPlaylist = emptyMap(); offline = false
        OfflineStore.offline = false
        prefs.edit().clear().apply(); address = ""; error = null; progress = ""
    }
}
/** Shown on the library while it holds only downloads; Retry asks Tauon again. */
internal const val OFFLINE_NOTE = "Tauon isn't reachable. Your downloads still play."

fun friendlyError(e: Throwable): String = when (e) {
    is java.net.SocketTimeoutException -> "Tauon did not respond. Check the server, LAN firewall and VPN LAN access, then retry."
    is java.net.ConnectException -> "Cannot reach Tauon. Enable remote control, restart Tauon, and check the address."
    is java.net.UnknownHostException -> "Server address could not be resolved."
    else -> e.message ?: "Connection failed. Check your LAN connection and retry."
}
