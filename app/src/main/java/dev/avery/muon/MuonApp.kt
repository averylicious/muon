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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The three tabs. Now Playing is not one of them: it is an overlay that grows out of the mini
 * player, so it can be opened from anywhere without losing the tab underneath it.
 */
private enum class Tab { Library, Search, Settings }

@Composable
fun MuonApp(player: MediaController?, controllerError: String?, model: LibraryModel = viewModel(),
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme()) {
    val appearance = rememberAppearanceSettings()
    MuonTheme(darkTheme = darkTheme, dynamicColor = appearance.palette == PaletteChoice.MaterialYou,
        blackSurfaces = useBlackSurfaces(appearance.amoled, darkTheme)) {
        val colors = MaterialTheme.colorScheme
        var tab by rememberSaveable { mutableStateOf(Tab.Library) }
        // Two booleans rather than a navigation library: the overlay sits above whichever tab is
        // showing, and Lyrics sits above the overlay. Album and artist pages will need more than
        // this, and that is the point at which a real back stack earns its keep.
        var playerOpen by rememberSaveable { mutableStateOf(false) }
        var lyricsOpen by rememberSaveable { mutableStateOf(false) }
        val library = rememberLibrarySettings()
        // Which playlist is open is about this sitting, not a preference: it survives rotation but
        // not a different server, and a refresh that removes or empties it closes it by itself.
        var openId by rememberSaveable { mutableStateOf<String?>(null) }
        var query by rememberSaveable { mutableStateOf("") }
        val playback = rememberPlayback(player)
        val ui = playback.ui
        val position = remember(playback) { { playback.position } }
        // Read through a lambda so a player event reaches a gesture already in progress, without
        // waiting for a recomposition to carry the new value down.
        val revision = remember(playback) { { playback.revision } }
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
        // Identifiers are only meaningful for the server that issued them.
        LaunchedEffect(model.endpoint?.origin) { openId = null }
        // The queue was emptied while the overlay was open: close it rather than leaving an empty
        // surface on top. Reading the controller directly, because the snapshot above can still be
        // the empty one for a frame after a controller reconnects.
        LaunchedEffect(player, ui.item) {
            if (overlayShouldClose(player != null, player?.currentMediaItem != null)) {
                lyricsOpen = false; playerOpen = false
            }
        }
        val overlayOpen = connected && playerOpen && ui.item != null
        val lyricsShown = overlayOpen && lyricsOpen
        BackHandler(connected && (overlayOpen || tab != Tab.Library || openId != null)) {
            when {
                lyricsShown -> lyricsOpen = false
                overlayOpen -> playerOpen = false
                tab != Tab.Library -> tab = Tab.Library
                else -> openId = null
            }
        }
        fun startQueue(list: List<TauonTrack>, track: TauonTrack) {
            val endpoint = model.endpoint ?: return
            val queue = list.filter { it.playable }
            val index = queue.indexOfFirst { it.id == track.id }
            if (index < 0 || player == null) return
            player.setMediaItems(queue.map { it.mediaItem(endpoint) }, index, 0L)
            player.prepare(); player.play()
        }
        Box(Modifier.fillMaxSize()) {
            // While the overlay covers the screen, the tabs behind it stay composed but are taken
            // out of the accessibility tree, so TalkBack cannot wander into hidden content.
            Box(if (overlayOpen) Modifier.clearAndSetSemantics {} else Modifier) {
                Scaffold(containerColor = colors.background, bottomBar = {
                    Column {
                        AnimatedVisibility(visible = ui.item != null && !overlayOpen,
                            enter = slideInVertically(motionMedium()) { it } + fadeIn(motionShort()),
                            exit = slideOutVertically(motionMedium()) { it } + fadeOut(motionShort())) {
                            MiniPlayer(ui, position, player != null, { playerOpen = true },
                                toggle = { if (ui.playing) player?.pause() else player?.play() },
                                next = { player?.seekToNextMediaItem() })
                        }
                        AnimatedVisibility(visible = connected,
                            enter = slideInVertically(motionMedium()) { it } + fadeIn(motionShort()),
                            exit = slideOutVertically(motionMedium()) { it } + fadeOut(motionShort())) {
                            NavigationBar(containerColor = colors.background, tonalElevation = 0.dp) {
                                Tab.entries.forEach { destination ->
                                    NavigationBarItem(selected = tab == destination,
                                        onClick = { tab = destination },
                                        icon = { MuonIcon(when (destination) {
                                            Tab.Library -> "library"; Tab.Search -> "search"; else -> "settings"
                                        }) }, label = { Text(destination.name) })
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
                        AnimatedVisibility(connected && model.error != null && tab != Tab.Settings,
                            enter = expandVertically(motionMedium()) + fadeIn(motionShort()),
                            exit = shrinkVertically(motionMedium()) + fadeOut(motionShort())) {
                            ErrorCard(model.error.orEmpty(), "Retry") { model.connect() }
                        }
                        BusyStrip(model.busy && connected)
                        // Tabs are siblings, so this fades with a small lift rather than sliding sideways.
                        AnimatedContent(if (connected) tab else null, transitionSpec = {
                            (fadeIn(motionMedium()) + slideInVertically(motionMedium()) { it / 24 })
                                .togetherWith(fadeOut(motionShort()))
                        }, label = "screen") { shown ->
                            when (shown) {
                                null -> ConnectScreen(model)
                                Tab.Settings -> SettingsScreen(model, appearance) {
                                    player?.stop(); player?.clearMediaItems(); model.disconnect()
                                    openId = null; lyricsOpen = false; playerOpen = false; tab = Tab.Library
                                }
                                Tab.Library -> {
                                    // Asked every time it draws, so a playlist that a refresh
                                    // removed or emptied simply stops being open.
                                    val open = openPlaylist(openId, model.playlists)
                                    Column {
                                        if (open == null) {
                                            LibraryBar()
                                            LibraryChips(library.view, library::choose)
                                        } else {
                                            PlaylistBar(open.name, open.count) { openId = null }
                                        }
                                        // Only the list pulls: the bar and the chips stay put, and
                                        // the chips keep their own horizontal scrolling.
                                        LibraryPane(model.busy, { model.connect() }) {
                                            when {
                                                open != null -> {
                                                    val tracks = model.tracksByPlaylist[open.id].orEmpty()
                                                    TrackList(tracks, model.endpoint, ui.item?.mediaId, player != null,
                                                        emptyText = "This playlist is empty. Add local music in Tauon, then refresh.",
                                                        loading = model.busy) { startQueue(tracks, it) }
                                                }
                                                library.view == LibraryView.Songs ->
                                                    TrackList(all, model.endpoint, ui.item?.mediaId, player != null,
                                                        emptyText = "No music yet. Add local music in Tauon, then refresh.",
                                                        loading = model.busy) { startQueue(all, it) }
                                                else -> PlaylistRows(model.playlists, model.busy) { openId = it }
                                            }
                                        }
                                    }
                                }
                                Tab.Search -> {
                                    Column {
                                        SearchField(query, { query = it }, search.searching)
                                        TrackList(search.tracks, model.endpoint, ui.item?.mediaId, player != null,
                                            emptyText = searchEmptyText(query, search.searching, search.completed)) {
                                            startQueue(search.tracks, it)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // The overlay rises from the bottom, where the mini player it grew out of sits.
            FullScreenOverlay(visible = overlayOpen && !lyricsShown) {
                NowPlayingOverlay(ui, position, revision, player,
                    collapse = { playerOpen = false }) { lyricsOpen = true }
            }
            FullScreenOverlay(visible = lyricsShown) {
                LyricsScreen(ui.item) { lyricsOpen = false }
            }
        }
    }
}

/**
 * A surface that covers the tabs and insets itself, because the scaffold below cannot reach it.
 * Material's own `Surface` already blocks touches from reaching what it covers, so nothing here
 * adds a click target that a screen reader would announce.
 */
@Composable
private fun FullScreenOverlay(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible,
        enter = slideInVertically(motionMedium()) { it } + fadeIn(motionShort()),
        exit = slideOutVertically(motionMedium()) { it } + fadeOut(motionShort())) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.safeDrawingPadding()) { content() }
        }
    }
}
