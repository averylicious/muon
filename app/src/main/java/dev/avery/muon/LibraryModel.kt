package dev.avery.muon

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LibraryModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("connection", 0)
    var address by mutableStateOf(prefs.getString("origin", "").orEmpty())
    var endpoint by mutableStateOf<ServerEndpoint?>(null); private set
    var playlists by mutableStateOf<List<TauonPlaylist>>(emptyList()); private set
    var tracksByPlaylist by mutableStateOf<Map<String, List<TauonTrack>>>(emptyMap()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf(""); private set
    private var job: Job? = null
    val allTracks: List<TauonTrack> get() = tracksByPlaylist.values.flatten().distinctBy { it.id }
    init { if (address.isNotBlank()) connect() }
    fun connect() {
        if (busy) return
        job = viewModelScope.launch {
            busy = true; error = null
            try {
                val e = ServerEndpoint.parse(address)
                val api = TauonApi(e)
                progress = "Connecting to Tauon…"; api.connect()
                val lists = api.playlists()
                val tracks = linkedMapOf<String, List<TauonTrack>>()
                lists.forEachIndexed { i, list ->
                    progress = "Loading playlists ${i + 1} / ${lists.size}"
                    tracks[list.id] = api.tracks(list.id)
                }
                endpoint = e; playlists = lists; tracksByPlaylist = tracks
                address = e.origin; prefs.edit().putString("origin", e.origin).apply()
                progress = "Connected · ${allTracks.size} tracks"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = friendlyError(e)
                progress = if (endpoint != null) "Showing last loaded library" else "Not connected"
            } finally { busy = false }
        }
    }
    fun disconnect() {
        job?.cancel(); busy = false; endpoint = null; playlists = emptyList(); tracksByPlaylist = emptyMap()
        prefs.edit().clear().apply(); address = ""; error = null; progress = ""
    }
}
fun friendlyError(e: Throwable): String = when (e) {
    is java.net.SocketTimeoutException -> "Tauon did not respond. Check the server, LAN firewall and VPN LAN access, then retry."
    is java.net.ConnectException -> "Cannot reach Tauon. Enable remote control, restart Tauon, and check the address."
    is java.net.UnknownHostException -> "Server address could not be resolved."
    else -> e.message ?: "Connection failed. Check your LAN connection and retry."
}
