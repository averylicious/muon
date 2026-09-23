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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
        // Which playlist is open is about this sitting, not a preference. It is saved with the
        // server it was chosen on, so it survives rotation but never crosses servers, and a
        // refresh that removes or empties it forgets it rather than leaving it to reappear.
        var openOrigin by rememberSaveable { mutableStateOf<String?>(null) }
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
        val origin = model.endpoint?.origin
        // An endpoint exists only after a complete load succeeded, so it is both the identity of
        // the server and the signal that there is something to judge a saved selection against.
        val selection = storedSelection(openOrigin, openId, origin, model.playlists)
        val openList = if (selection == StoredSelection.Open) openPlaylist(openId, model.playlists) else null
        if (selection == StoredSelection.Discard) {
            // Cleared as soon as there is a library to judge against, so Back has nothing phantom
            // to unwind and the identifier cannot come back to life on a later refresh.
            LaunchedEffect(openOrigin, openId, origin) { openOrigin = null; openId = null }
        }
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
        val playerShown = overlayOpen && !lyricsShown
        // The player's one vertical position, held outside its composition so it outlives the
        // closing animation. Opening and closing drive it from the logical state; progress never
        // decides where Back goes.
        val sheet = rememberPlayerSheet(openAtStart = playerShown)
        // Re-presents the logical state whenever it changes and whenever a preview from the mini
        // player ends. A preview that opened the player ends in the same event, so the sheet carries
        // on up from where the finger left it; every other ending — short, cancelled, Back, lost
        // eligibility, a refused open — leaves the player closed, so the sheet is put away rather
        // than left part-way. The gesture never has the last word.
        LaunchedEffect(playerShown, sheet.previewing) { if (!sheet.previewing) sheet.present(playerShown) }
        // Only a detail that is actually on screen takes a Back press. Both handlers read this one
        // decision, so they cannot disagree about where Back goes.
        val target = backTarget(connected, lyricsShown, overlayOpen,
            onLibraryTab = tab == Tab.Library, playlistOpen = openList != null)
        fun goBack() {
            when (target) {
                BackTarget.Lyrics -> lyricsOpen = false
                BackTarget.Player -> playerOpen = false
                BackTarget.Tab -> tab = Tab.Library
                BackTarget.Playlist -> { openOrigin = null; openId = null }
                BackTarget.None -> Unit
            }
        }
        BackHandler(target != BackTarget.None) { goBack() }
        // While the finger is carrying a closed player up, Back cancels that and nothing else: the
        // library underneath is hidden by the rising player and must not be navigated. Registered
        // after the app's own handler, so the dispatcher gives it the press while it is enabled.
        BackHandler(sheet.previewing) { sheet.endPreview() }
        fun startQueue(list: List<TauonTrack>, track: TauonTrack) {
            val endpoint = model.endpoint ?: return
            val queue = list.filter { it.playable }
            val index = queue.indexOfFirst { it.id == track.id }
            if (index < 0 || player == null) return
            player.setMediaItems(queue.map { it.mediaItem(endpoint) }, index, 0L)
            player.prepare(); player.play()
        }
        // Measured here rather than on the player's host, which is not composed until a preview has
        // moved it: the root is always measured and is the size the sheet will be, so the first move
        // of a preview can already be turned into a position, and that position mounts the host.
        Box(Modifier.fillMaxSize().onSizeChanged { sheet.height = it.height.toFloat() }) {
            // While the overlay covers the screen, the tabs behind it stay composed but are taken
            // out of the accessibility tree, so TalkBack cannot wander into hidden content.
            Box(if (overlayOpen) Modifier.clearAndSetSemantics {} else Modifier) {
                Scaffold(containerColor = colors.background, bottomBar = {
                    Column {
                        AnimatedVisibility(visible = ui.item != null && !overlayOpen,
                            enter = slideInVertically(motionMedium()) { it } + fadeIn(motionShort()),
                            exit = slideOutVertically(motionMedium()) { it } + fadeOut(motionShort())) {
                            MiniPlayer(ui, position, player != null,
                                active = connected && player != null && ui.item != null && !overlayOpen,
                                sheet = sheet,
                                open = {
                                    if (model.endpoint != null && player != null && playback.ui.item != null && !overlayOpen)
                                        playerOpen = true
                                },
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
                                    openOrigin = null; openId = null
                                    lyricsOpen = false; playerOpen = false; tab = Tab.Library
                                }
                                Tab.Library -> {
                                    val open = openList
                                    if (open == null) {
                                        // Greeting, chips, then the list; only the list pulls, and
                                        // the greeting unfolds before a pull begins.
                                        LibraryTop(all.size, library.view, library::choose,
                                            model.busy, { model.connect() }) {
                                            if (library.view == LibraryView.Songs)
                                                TrackList(all, model.endpoint, ui.item?.mediaId, player != null,
                                                    emptyText = "No music yet. Add local music in Tauon, then refresh.",
                                                    loading = model.busy) { startQueue(all, it) }
                                            else PlaylistRows(model.playlists, model.busy) {
                                                openOrigin = origin; openId = it
                                            }
                                        }
                                    } else Column {
                                        // One playlist keeps its own compact bar: its name is the
                                        // heading, and a greeting would be in the way.
                                        PlaylistBar(open.name, open.count) { openId = null }
                                        LibraryPane(model.busy, { model.connect() }) {
                                            val tracks = model.tracksByPlaylist[open.id].orEmpty()
                                            TrackList(tracks, model.endpoint, ui.item?.mediaId, player != null,
                                                emptyText = "This playlist is empty. Add local music in Tauon, then refresh.",
                                                loading = model.busy) { startQueue(tracks, it) }
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
            // Dims the library under the player, so a player being dragged, closed or previewed
            // by Back reads as a sheet over it rather than more of the same surface. It stays
            // put while the sheet moves, follows only the player's own visibility, and is gone
            // entirely once the player has closed.
            // Also shown while a preview is rising, so new touches cannot reach the library under
            // it; a finger already down keeps its own stream, so the drag itself carries on.
            AnimatedVisibility(visible = playerShown || sheet.previewing, enter = fadeIn(motionMedium()),
                exit = fadeOut(motionMedium())) {
                PlayerScrim()
            }
            // The player rises from the bottom, where the mini player it grew out of sits, and
            // shrinks while a Back gesture is deciding whether to close it.
            PlayerHost(sheet, open = playerShown, preview = {
                rememberPlayerBackPreview(playerShown) { if (playerGestureCommits(target)) goBack() }
            }) {
                // Only a player that is actually on screen carries the drag: an outgoing one hands
                // nothing down, so its detector goes with it rather than moving what comes next.
                NowPlayingOverlay(ui, position, revision, player, sheet.takeIf { playerShown },
                    // Guarded, so a drag that ends after Lyrics opened over the player, or after
                    // the player has gone, cannot put away whatever took its place.
                    collapse = { if (playerShown) playerOpen = false }) { lyricsOpen = true }
            }
            FullScreenOverlay(visible = lyricsShown) {
                LyricsScreen(ui.item) { lyricsOpen = false }
            }
        }
    }
}

/** What a Back press acts on, named in the order the screens are stacked. */
internal enum class BackTarget { None, Lyrics, Player, Tab, Playlist }

internal fun backTarget(connected: Boolean, lyricsShown: Boolean, overlayOpen: Boolean,
    onLibraryTab: Boolean, playlistOpen: Boolean): BackTarget = when {
    !connected -> BackTarget.None
    lyricsShown -> BackTarget.Lyrics
    overlayOpen -> BackTarget.Player
    !onLibraryTab -> BackTarget.Tab
    playlistOpen -> BackTarget.Playlist
    else -> BackTarget.None
}

/**
 * Whether a Back gesture that began over the player should still act when it is let go.
 *
 * A held gesture outlives the state it started in: `PredictiveBackHandler` hands it to a coroutine
 * and, in activity-compose 1.11.0, does not cancel it when the handler stops being enabled. Lyrics
 * can open over the player meanwhile, or the player can be closed by its own button or by the
 * queue emptying. Lyrics is part of what the gesture was aiming at, so Back acts on it; a player
 * that has gone is not, and sending the user back a tab they never aimed at would be a surprise,
 * so the gesture is let go without navigating.
 */
internal fun playerGestureCommits(target: BackTarget): Boolean =
    target == BackTarget.Lyrics || target == BackTarget.Player

/** Material's modal-sheet scrim opacity (`ScrimTokens.ContainerOpacity` in material3 1.4.0). */
private const val PLAYER_SCRIM_ALPHA = 0.32f

/**
 * Blocks touches to the library it dims, the same way Material's own `Surface` does, and adds
 * nothing a screen reader could focus or activate: closing stays with the collapse button and Back.
 */
@Composable
private fun PlayerScrim() {
    Box(Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.scrim.copy(alpha = PLAYER_SCRIM_ALPHA))
        .pointerInput(Unit) {})
}

/**
 * A surface that covers the tabs and insets itself, because the scaffold below cannot reach it.
 * Material's own `Surface` already blocks touches from reaching what it covers, so nothing here
 * adds a click target that a screen reader would announce. Lyrics uses this; the player has
 * [PlayerHost], because its position is driven by gestures as well as by being opened.
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

/**
 * The player's own host, positioned only by [sheet].
 *
 * It is composed while the player is logically [open], from zero progress, so an opening sheet can
 * be measured and animated in; and kept while any of it is still on screen, so it can leave. Anything
 * remembered inside — including [preview]'s Back gesture state — therefore lasts exactly as long as
 * the player is on screen, as it did inside the old transition.
 *
 * On its way out the player is no longer the thing being used: its content leaves the accessibility
 * tree and a non-semantic cover takes its touches, so partly visible controls cannot be tapped or
 * focused while they slide away.
 */
@Composable
private fun PlayerHost(sheet: PlayerSheet, open: Boolean,
    preview: @Composable () -> PlayerBackPreview?, content: @Composable () -> Unit) {
    if (!playerSheetPresent(open, sheet.onScreen)) return
    // Two layers, each owning its own properties: the sheet moves the surface, the Back preview
    // scales and drifts it, and neither writes what the other reads. On pure black the sheet also
    // outlines its top edge, since nothing else can show where the player ends.
    val colors = MaterialTheme.colorScheme
    val edge = colors.outlineVariant.takeIf { colors.background == Color.Black }
    Surface(Modifier.fillMaxSize()
        .playerSheet(sheet, edge).playerBackPreview(preview()),
        color = colors.background) {
        // The content gives back the top inset as the sheet drops below the status bar.
        Box(Modifier.reclaimTopInset(sheet, WindowInsets.safeDrawing).safeDrawingPadding()) {
            Box(if (open) Modifier else Modifier.clearAndSetSemantics {}) { content() }
            if (!open) Box(Modifier.matchParentSize().pointerInput(Unit) {})
        }
    }
}
