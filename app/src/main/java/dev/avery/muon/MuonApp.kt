package dev.avery.muon

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Screen { Library, Search, Playing, Lyrics, Settings, Connect }

@Composable
fun MuonApp(player: MediaController?, controllerError: String?, model: LibraryModel = viewModel(),
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme()) {
    val appearance = rememberAppearanceSettings()
    MuonTheme(darkTheme = darkTheme, dynamicColor = appearance.palette == PaletteChoice.MaterialYou,
        blackSurfaces = useBlackSurfaces(appearance.amoled, darkTheme)) {
        val colors = MaterialTheme.colorScheme
        var screen by rememberSaveable { mutableStateOf(Screen.Library) }
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        var query by rememberSaveable { mutableStateOf("") }
        val playback = rememberPlayback(player)
        val ui = playback.ui
        val position = remember(playback) { { playback.position } }
        val all = remember(model.tracksByPlaylist) { model.allTracks }
        // Filtering a large library on the composition thread stalled typing. Debounced, kept off
        // the main thread, and hoisted here so results survive a trip to another tab.
        // Reset immediately when the library changes; never offer old server track IDs while
        // the replacement library's search is still debouncing.
        val search by key(all) {
            produceState(SearchResults(), query) {
                if (query.isBlank()) { value = SearchResults(); return@produceState }
                value = value.copy(searching = true)
                delay(SEARCH_DEBOUNCE_MS)
                val found = withContext(Dispatchers.Default) { searchTracks(all, query) }
                value = SearchResults(found, searching = false, completed = query.trim())
            }
        }
        val connected = model.endpoint != null
        val shownScreen = if (!connected) Screen.Connect else screen
        BackHandler(connected && screen != Screen.Library) { screen = Screen.Library }
        fun startQueue(list: List<TauonTrack>, track: TauonTrack) {
            val endpoint = model.endpoint ?: return
            val queue = list.filter { it.playable }
            val index = queue.indexOfFirst { it.id == track.id }
            if (index < 0 || player == null) return
            player.setMediaItems(queue.map { it.mediaItem(endpoint) }, index, 0L)
            player.prepare(); player.play()
        }
        Scaffold(containerColor = colors.background, bottomBar = {
            Column {
                AnimatedVisibility(visible = ui.item != null && shownScreen != Screen.Playing,
                    enter = slideInVertically(motionMedium()) { it } + fadeIn(motionShort()),
                    exit = slideOutVertically(motionMedium()) { it } + fadeOut(motionShort())) {
                    MiniPlayer(ui, position, player != null, { screen = Screen.Playing },
                        toggle = { if (ui.playing) player?.pause() else player?.play() },
                        next = { player?.seekToNextMediaItem() })
                }
                AnimatedVisibility(visible = connected,
                    enter = slideInVertically(motionMedium()) { it } + fadeIn(motionShort()),
                    exit = slideOutVertically(motionMedium()) { it } + fadeOut(motionShort())) {
                    NavigationBar(containerColor = colors.background, tonalElevation = 0.dp) {
                    listOf(Screen.Library, Screen.Search, Screen.Playing, Screen.Settings).forEach { destination ->
                        NavigationBarItem(selected = shownScreen == destination || (destination == Screen.Playing && shownScreen == Screen.Lyrics),
                            onClick = { screen = destination },
                            icon = { MuonIcon(when (destination) {
                                Screen.Library -> "library"; Screen.Search -> "search"; Screen.Playing -> "music"; else -> "settings"
                            }) }, label = { Text(if (destination == Screen.Playing) "Playing" else destination.name) })
                    }
                    }
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                AnimatedVisibility(controllerError != null,
                    enter = expandVertically(motionMedium()) + fadeIn(motionShort()),
                    exit = shrinkVertically(motionMedium()) + fadeOut(motionShort())) {
                    ErrorCard(controllerError.orEmpty())
                }
                AnimatedVisibility(connected && model.error != null && shownScreen != Screen.Settings,
                    enter = expandVertically(motionMedium()) + fadeIn(motionShort()),
                    exit = shrinkVertically(motionMedium()) + fadeOut(motionShort())) {
                    ErrorCard(model.error.orEmpty(), "Retry") { model.connect() }
                }
                BusyStrip(model.busy && connected)
                // Tabs are siblings, so this fades with a small lift rather than sliding sideways.
                AnimatedContent(shownScreen, transitionSpec = {
                    (fadeIn(motionMedium()) + slideInVertically(motionMedium()) { it / 24 })
                        .togetherWith(fadeOut(motionShort()))
                }, label = "screen") { shown ->
                when (shown) {
                    Screen.Connect -> ConnectScreen(model)
                    Screen.Settings -> SettingsScreen(model, appearance) {
                        player?.stop(); player?.clearMediaItems(); model.disconnect(); selected = null; screen = Screen.Library
                    }
                    Screen.Library -> {
                        val tracks = if (selected == null) all else model.tracksByPlaylist[selected].orEmpty()
                        Column {
                            LibraryBar(model.busy) { model.connect() }
                            PlaylistChips(model.playlists, all.size, selected) { selected = it }
                            TrackList(tracks, model.endpoint, ui.item?.mediaId, player != null,
                                emptyText = "This playlist is empty. Add local music in Tauon, then refresh.",
                                loading = model.busy) { startQueue(tracks, it) }
                        }
                    }
                    Screen.Search -> {
                        Column {
                            SearchField(query, { query = it }, search.searching)
                            TrackList(search.tracks, model.endpoint, ui.item?.mediaId, player != null,
                                emptyText = searchEmptyText(query, search.searching, search.completed)) {
                                startQueue(search.tracks, it)
                            }
                        }
                    }
                    Screen.Playing -> NowPlaying(ui, position, player) { screen = Screen.Lyrics }
                    Screen.Lyrics -> LyricsScreen(ui.item) { screen = Screen.Playing }
                }
                }
            }
        }
    }
}
