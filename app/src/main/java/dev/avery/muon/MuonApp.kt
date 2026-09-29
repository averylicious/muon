package dev.avery.muon

import android.os.Build
import androidx.compose.ui.platform.LocalLayoutDirection
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The three tabs. Now Playing is not one of them: it is an overlay that grows out of the mini
 * player, so it can be opened from anywhere without losing the tab underneath it.
 */
private enum class Tab { Library, Search, Settings }

@OptIn(ExperimentalSharedTransitionApi::class)
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
        // Queue sits over the player exactly as Lyrics does; only one of the two is ever open.
        var queueOpen by rememberSaveable { mutableStateOf(false) }
        val library = rememberLibrarySettings()
        val context = LocalContext.current
        // Downloads are read in at launch, so rows can mark them, and any left unfinished carry on.
        LaunchedEffect(Unit) { OfflineStore.get(context); OfflineStore.resume(context) }
        val scope = rememberCoroutineScope()
        // Which playlist is open is about this sitting, not a preference. It is saved with the
        // server it was chosen on, so it survives rotation but never crosses servers, and a
        // refresh that removes or empties it forgets it rather than leaving it to reappear.
        var openOrigin by rememberSaveable { mutableStateOf<String?>(null) }
        var openId by rememberSaveable { mutableStateOf<String?>(null) }
        // The open artist, on the same terms: saved with its server, never persisted beyond this
        // sitting, and forgotten when the server changes or a refresh removes the artist.
        var artistOrigin by rememberSaveable { mutableStateOf<String?>(null) }
        var artistKey by rememberSaveable { mutableStateOf<String?>(null) }
        // Only a title for the page while it waits for the grouping; never used to find the artist.
        var artistName by rememberSaveable { mutableStateOf<String?>(null) }
        // The open album, on the same terms again; its title only names the page while it waits.
        var albumOrigin by rememberSaveable { mutableStateOf<String?>(null) }
        var albumKey by rememberSaveable { mutableStateOf<String?>(null) }
        var albumTitle by rememberSaveable { mutableStateOf<String?>(null) }
        // Where each library list was, and how far the greeting had folded, held here rather than
        // in the lists so they outlive an open artist or playlist and a trip to another tab: Back
        // returns to the same row, at the same offset, at the same height on screen. The fold is
        // shared by all three views, so all three lists are kept; keeping only some would leave a
        // list back at its top under a folded greeting. Saved across rotation; disconnecting starts
        // them afresh, so another server's library never opens part-way down.
        var songList by rememberSaveable(stateSaver = LazyListState.Saver) { mutableStateOf(LazyListState()) }
        var artistList by rememberSaveable(stateSaver = LazyListState.Saver) { mutableStateOf(LazyListState()) }
        var playlistList by rememberSaveable(stateSaver = LazyListState.Saver) { mutableStateOf(LazyListState()) }
        // The open artist's page keeps its place while one of its albums is open; another artist starts at the top.
        val artistPageList = rememberSaveable(artistKey, saver = LazyListState.Saver) { LazyListState() }
        // The library switching between what is on the phone and Tauon's whole collection is a new
        // list, not the old one grown or shrunk: every list starts again from its top (#16 QA).
        LaunchedEffect(model.offline) {
            if (songList.firstVisibleItemIndex == 0 && songList.firstVisibleItemScrollOffset == 0) return@LaunchedEffect
            songList = LazyListState(); artistList = LazyListState(); playlistList = LazyListState()
        }
        var albumGrid by rememberSaveable(stateSaver = LazyGridState.Saver) { mutableStateOf(LazyGridState()) }
        var libraryBar by rememberSaveable(stateSaver = TopAppBarState.Saver) { mutableStateOf(TopAppBarState(
            initialHeightOffsetLimit = -Float.MAX_VALUE, initialHeightOffset = 0f, initialContentOffset = 0f)) }
        var query by rememberSaveable { mutableStateOf("") }
        // Whether the open library page was opened from Search, which Back then returns to.
        var fromSearch by rememberSaveable { mutableStateOf(false) }
        // Whether the search bar is expanded over the Search tab; kept here so a page opened from the
        // results comes back to them.
        var searchOpen by rememberSaveable { mutableStateOf(false) }
        val playback = rememberPlayback(player)
        val ui = playback.ui
        val position = remember(playback) { { playback.position } }
        // Read through a lambda so a player event reaches a gesture already in progress, without
        // waiting for a recomposition to carry the new value down.
        val revision = remember(playback) { { playback.revision } }
        // One object per library snapshot: the tracks everything lists, and the identity artist
        // grouping is keyed and checked on.
        val snapshot = remember(model.tracksByPlaylist) { LibrarySnapshot(model.allTracks) }
        val all = snapshot.tracks
        // Which song each artwork address names, published before any of this library's artwork
        // loads, so a picture kept on disk is only ever shown for the song it was stored for.
        val identities = remember(snapshot, model.endpoint) {
            model.endpoint?.let { artworkIdentities(it, all) } ?: emptyMap()
        }
        SideEffect { ArtworkIdentities.publish(identities) }
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
        // Grouped once per library snapshot, off the main thread, and labelled with the server and
        // the snapshot it was grouped from. Keyed on the same snapshot identity that decides whether
        // the groups still apply, so every snapshot the guard would refuse starts its own grouping.
        // A newer snapshot cancels an unfinished grouping of the old one.
        var artistGroups by remember { mutableStateOf<ArtistGroups?>(null) }
        var albumGroups by remember { mutableStateOf<AlbumGroups?>(null) }
        LaunchedEffect(snapshot, origin) {
            val grouping = origin ?: run { artistGroups = null; albumGroups = null; return@LaunchedEffect }
            // Albums are grouped in the same pass, on the same terms as artists.
            val (grouped, albumsGrouped) = withContext(Dispatchers.Default) {
                groupArtists(snapshot.tracks) to groupAlbums(snapshot.tracks)
            }
            artistGroups = ArtistGroups(grouping, snapshot, grouped)
            albumGroups = AlbumGroups(grouping, snapshot, albumsGrouped)
        }
        val artists = currentArtists(artistGroups, origin, snapshot)
        val albums = currentAlbums(albumGroups, origin, snapshot)
        // The chosen orders, applied once per library and per choice rather than on every frame.
        // Both derive from data already bound to this snapshot, so sorting adds no stale state.
        val songs = remember(snapshot, library.songOrder) { sortSongs(all, library.songOrder) }
        val sortedArtists = remember(artists, library.artistOrder) { artists?.let { sortArtists(it, library.artistOrder) } }
        val sortedAlbums = remember(albums, library.albumOrder) { albums?.let { sortAlbums(it, library.albumOrder) } }
        // Jump back in: each album is remembered as a song from it starts, for the server it came from.
        val playingId = trackIdOf(ui.item?.mediaId, origin)
        LaunchedEffect(playingId, origin, snapshot) {
            val server = origin ?: return@LaunchedEffect
            val track = playingId?.let { id -> snapshot.tracks.firstOrNull { it.id == id } } ?: return@LaunchedEffect
            if (track.album.isNotBlank()) library.played(server, albumKey(track))
        }
        val recent = remember(albums, origin, library.recentOrigin, library.recentAlbums) {
            if (albums != null && origin != null && origin == library.recentOrigin) recentAlbums(library.recentAlbums, albums)
            else emptyList()
        }
        // Matched against the query the songs finished with, so all three sections agree; cheap enough
        // for the main thread, since there are far fewer artists and albums than songs.
        val foundArtists = remember(artists, search.completed) { artists?.let { searchArtists(it, search.completed) }.orEmpty() }
        val foundAlbums = remember(sortedAlbums, search.completed) { sortedAlbums?.let { searchAlbums(it, search.completed) }.orEmpty() }
        // Kept here so Back from a page opened from the results finds them where they were; a new
        // search starts from its top.
        val searchList = rememberSaveable(search.completed, saver = LazyListState.Saver) { LazyListState() }
        // What Search shows before anything is typed.
        val browseArtists = remember(artists) { artists?.let { topArtists(it) }.orEmpty() }
        val browseAlbums = remember(albums) { albums?.let { newestAlbums(it) }.orEmpty() }
        // An endpoint exists only after a complete load succeeded, so it is both the identity of
        // the server and the signal that there is something to judge a saved selection against.
        val selection = storedSelection(openOrigin, openId, origin, model.playlists)
        val openList = if (selection == StoredSelection.Open) openPlaylist(openId, model.playlists) else null
        if (selection == StoredSelection.Discard) {
            // Cleared as soon as there is a library to judge against, so Back has nothing phantom
            // to unwind and the identifier cannot come back to life on a later refresh.
            LaunchedEffect(openOrigin, openId, origin) { openOrigin = null; openId = null }
        }
        val artistSelection = storedArtist(artistOrigin, artistKey, origin, artists)
        val openArtist = if (artistSelection == StoredSelection.Open)
            artists?.firstOrNull { it.key == artistKey } else null
        val artistPage = artistPageShown(artistSelection, connected)
        fun closeArtist() { artistOrigin = null; artistKey = null; artistName = null }
        if (artistSelection == StoredSelection.Discard) {
            LaunchedEffect(artistOrigin, artistKey, origin) { closeArtist() }
        }
        val albumSelection = storedAlbum(albumOrigin, albumKey, origin, albums)
        val openAlbum = if (albumSelection == StoredSelection.Open) albums?.firstOrNull { it.key == albumKey } else null
        val albumPage = artistPageShown(albumSelection, connected)
        fun closeAlbum() { albumOrigin = null; albumKey = null; albumTitle = null }
        if (albumSelection == StoredSelection.Discard) {
            LaunchedEffect(albumOrigin, albumKey, origin) { closeAlbum() }
        }
        // The queue was emptied while the overlay was open: close it rather than leaving an empty
        // surface on top. Reading the controller directly, because the snapshot above can still be
        // the empty one for a frame after a controller reconnects.
        LaunchedEffect(player, ui.item) {
            if (overlayShouldClose(player != null, player?.currentMediaItem != null)) {
                lyricsOpen = false; queueOpen = false; playerOpen = false
            }
        }
        val overlayOpen = connected && playerOpen && ui.item != null
        val lyricsShown = overlayOpen && lyricsOpen
        val queueShown = overlayOpen && queueOpen && !lyricsShown
        val playerShown = overlayOpen && !lyricsShown && !queueShown
        // The player's one vertical position, held outside its composition so it outlives the
        // closing animation. Opening and closing drive it from the logical state; progress never
        // decides where Back goes.
        val sheet = rememberPlayerSheet(openAtStart = playerShown)
        // With Expressive motion the player grows out of the mini player rather than rising from
        // below the screen (PlayerMorph), which changes how far its edge travels.
        val morph = remember(sheet) { PlayerMorph(sheet).also { m -> sheet.morphTravel = { m.travel() } } }
        val topInset = WindowInsets.safeDrawing.getTop(LocalDensity.current).toFloat()
        SideEffect { morph.topInset = topInset }
        // Re-presents the logical state whenever it changes and whenever a preview from the mini
        // player ends. A preview that opened the player ends in the same event, so the sheet carries
        // on up from where the finger left it; every other ending — short, cancelled, Back, lost
        // eligibility, a refused open — leaves the player closed, so the sheet is put away rather
        // than left part-way. The gesture never has the last word. Keyed on the count of completed
        // previews as well, because a preview can begin and end before any frame sees it running,
        // and a flag that flipped and flipped back would leave the keys unchanged.
        val turn = sheet.turn
        LaunchedEffect(playerShown, turn.previewing, turn.completions) {
            if (!turn.previewing) sheet.present(playerShown)
        }
        // Only a detail that is actually on screen takes a Back press. Both handlers read this one
        // decision, so they cannot disagree about where Back goes.
        val target = backTarget(connected, lyricsShown, overlayOpen,
            onLibraryTab = tab == Tab.Library, playlistOpen = openList != null || artistPage || albumPage,
            queueShown = queueShown)
        // Leaves the library page on show: an album opened from an artist's page goes back to that
        // page; anything else goes to the top, or back to Search if it was opened from there.
        fun closePage() {
            if (albumPage && artistPage) { closeAlbum(); return }
            openOrigin = null; openId = null; closeArtist(); closeAlbum()
            if (fromSearch) { fromSearch = false; tab = Tab.Search }
        }
        // Search opens a page over a fresh Library, so Back from it leads back to the results.
        fun openFromSearch(open: () -> Unit) {
            openOrigin = null; openId = null; closeArtist(); closeAlbum()
            open(); fromSearch = true; tab = Tab.Library
        }
        fun goBack() {
            when (target) {
                BackTarget.Lyrics -> lyricsOpen = false
                BackTarget.Queue -> queueOpen = false
                BackTarget.Player -> playerOpen = false
                BackTarget.Tab -> tab = Tab.Library
                BackTarget.Playlist -> closePage()
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
        // An album's Play and Shuffle: in order from the first song, or shuffled from a random one.
        // Both set shuffle to match, as the buttons promise.
        fun playAll(list: List<TauonTrack>, shuffle: Boolean) {
            val endpoint = model.endpoint ?: return
            val queue = list.filter { it.playable }
            if (queue.isEmpty() || player == null) return
            player.shuffleModeEnabled = shuffle
            player.setMediaItems(queue.map { it.mediaItem(endpoint) }, if (shuffle) queue.indices.random() else 0, 0L)
            player.prepare(); player.play()
        }
        // The song a long press chose (#46), while its actions sheet is open. Not saved: a sheet is a
        // passing choice, and a rotation that closes it loses nothing.
        var actionTrack by remember { mutableStateOf<TauonTrack?>(null) }
        val snackbar = remember { SnackbarHostState() }
        // Android 17's local network permission (LocalNetwork.kt): asked for in context, from Connect or
        // the library's card. Once Android stops showing the prompt, the same button opens Settings;
        // coming back with access granted connects.
        val activity = LocalActivity.current
        val askLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            LocalNetworkState.granted = granted
            LocalNetworkState.denied = !granted
            if (granted) model.connect()
        }
        fun allowLocalNetwork() {
            val rationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, ACCESS_LOCAL_NETWORK) } ?: false
            when (localNetworkAsk(LocalNetworkState.denied, rationale)) {
                LocalNetworkAsk.Ask -> askLocalNetwork.launch(ACCESS_LOCAL_NETWORK)
                LocalNetworkAsk.OpenSettings -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)))
            }
        }
        LifecycleResumeEffect(Unit) {
            val now = localNetworkGranted(context)
            val was = LocalNetworkState.granted
            LocalNetworkState.granted = now
            if (now && !was && model.address.isNotBlank()) model.connect()
            onPauseOrDispose { }
        }
        // Once the played-song cache fills, say so once per limit (#112): nothing is lost, the oldest
        // songs make room, but a bigger limit keeps more, and Settings is where it is.
        val cacheIsFull = cacheFull(PlayedCacheState.used, PlayedCacheState.limit)
        LaunchedEffect(cacheIsFull) {
            if (!cacheIsFull) return@LaunchedEffect
            val limit = PlayedCacheState.limit
            val prefs = context.getSharedPreferences("storage", android.content.Context.MODE_PRIVATE)
            if (prefs.getLong("fullNoticed", 0L) == limit) return@LaunchedEffect
            prefs.edit().putLong("fullNoticed", limit).apply()
            val result = snackbar.showSnackbar("The played-song cache is full (${formatBytes(limit)}). The oldest songs now make room.",
                actionLabel = "Settings", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) tab = Tab.Settings
        }
        // Play next goes straight after the playing song and Add to queue at the end; the shuffle
        // order keeps both there with shuffle on. With nothing queued, the song simply plays. Undo
        // takes back that same entry, found again if the queue has moved since.
        fun queueSong(track: TauonTrack, next: Boolean) {
            val endpoint = model.endpoint ?: return
            val p = player ?: return
            val item = track.mediaItem(endpoint)
            if (p.mediaItemCount == 0) { p.setMediaItems(listOf(item)); p.prepare(); p.play(); return }
            val at = if (next) p.currentMediaItemIndex + 1 else p.mediaItemCount
            p.addMediaItem(at, item)
            snackbar.currentSnackbarData?.dismiss()
            scope.launch {
                val result = snackbar.showSnackbar(queuedMessage(track.title, next), actionLabel = "Undo",
                    duration = SnackbarDuration.Short)
                if (result != SnackbarResult.ActionPerformed) return@launch
                val entries = (0 until p.mediaItemCount).filter { p.getMediaItemAt(it).mediaId == item.mediaId }
                entries.minByOrNull { kotlin.math.abs(it - at) }?.let { p.removeMediaItem(it) }
            }
        }
        // One lambda for the whole composition, so providing it to the rows never recomposes them.
        val latestQueueSong by rememberUpdatedState(::queueSong)
        val queueFromSwipe = remember { { track: TauonTrack, next: Boolean -> latestQueueSong(track, next) } }
        // Go to album and Go to artist open the page over the one on show: an album over an open artist
        // page returns to it, as from the artist's own row; from Search, Back returns to the results.
        fun goToAlbum(album: LibraryAlbum) {
            val open = { albumOrigin = origin; albumKey = album.key; albumTitle = album.title }
            if (tab != Tab.Library) openFromSearch(open)
            else { openOrigin = null; openId = null; open() }
        }
        fun goToArtist(artist: LibraryArtist) {
            val open = { artistOrigin = origin; artistKey = artist.key; artistName = artist.name }
            if (tab != Tab.Library) openFromSearch(open)
            else { openOrigin = null; openId = null; closeAlbum(); open() }
        }
        // Measured here rather than on the player's host, which is not composed until a preview has
        // moved it: the root is always measured and is the size the sheet will be, so the first move
        // of a preview can already be turned into a position, and that position mounts the host.
        BoxWithConstraints(Modifier.fillMaxSize().onSizeChanged { sheet.height = it.height.toFloat() }
            .onGloballyPositioned { morph.root = it }) {
            // A phone on its side: the tabs move to a rail at the start, so the height they took
            // along the bottom goes to the list instead, and the mini player keeps the bottom alone.
            val rail = sidewaysLayout(maxWidth.value, maxHeight.value)
            // The Canary experiment's blur: the library, and the side panel in the same row, soften
            // under the player as it rises, and behind the song menu. Draw phase only (BlurBehind.kt).
            val menuBlur by animateFloatAsState(if (actionTrack != null) 1f else 0f, motionMedium(), label = "menu blur")
            val behind = Modifier.blurBehind(sheet) { menuBlur }
            // Frosted glass (FrostedGlass.kt): with the experiment's blur on, Android 12 or newer and
            // the bars along the bottom, the mini player and navigation bar show the tab under them,
            // blurred, and the lists scroll on underneath. Otherwise the bars stay solid, as before.
            val glass = rememberFrost()
            val frost = if (connected && frostedGlassOn(Expressive.blur, Build.VERSION.SDK_INT, rail)) glass else null
            val barTint = if (frost != null) GLASS_TINT else 1f
            // While the overlay covers the screen, the tabs behind it stay composed but are taken
            // out of the accessibility tree, so TalkBack cannot wander into hidden content.
            // A rising preview obscures them just the same, so they leave the tree for it too, and
            // come back as soon as it is cancelled. Only semantics change: the mini player's own
            // gesture detector, which is carrying the preview, is not touched.
            // Painted with the theme's own background: the side panel sits in this row beside the
            // scaffold, which stops short of it, so the window background showed through around the
            // panel in a slightly different colour (and grey under pure black).
            Row(Modifier.fillMaxSize().then(behind).background(colors.background).then(if (overlayOpen || sheet.previewing) Modifier.clearAndSetSemantics {} else Modifier)) {
                AnimatedVisibility(visible = connected && rail,
                    enter = slideInHorizontally(motionSpatial()) { -it } + fadeIn(motionShort()),
                    exit = slideOutHorizontally(motionSpatial()) { -it } + fadeOut(motionShort())) {
                    // The Canary experiment: Material 3 Expressive's navigation rail, collapsed, with
                    // the same pill behind the chosen tab as the flexible navigation bar.
                    if (Expressive.motion) WideNavigationRail(
                        colors = WideNavigationRailDefaults.colors(containerColor = colors.background),
                        // Centred, where a thumb holding the phone sideways reaches them.
                        arrangement = Arrangement.Center) {
                        Tab.entries.forEach { destination ->
                            WideNavigationRailItem(selected = tab == destination,
                                onClick = { tab = destination; fromSearch = false },
                                icon = { TabIcon(destination) }, label = { Text(destination.name) },
                                railExpanded = false)
                        }
                    } else NavigationRail(containerColor = colors.background) {
                        // Centred, where a thumb holding the phone sideways reaches them.
                        Spacer(Modifier.weight(1f))
                        Tab.entries.forEach { destination ->
                            NavigationRailItem(selected = tab == destination,
                                onClick = { tab = destination; fromSearch = false },
                                icon = { TabIcon(destination) }, label = { Text(destination.name) })
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
                // The rail takes the start inset itself, so the content leaves it out.
                Scaffold(Modifier.weight(1f), containerColor = colors.background, snackbarHost = { SnackbarHost(snackbar) },
                    // The player panel, when it is there, takes the end inset in the same way.
                    contentWindowInsets = if (rail && connected) ScaffoldDefaults.contentWindowInsets
                        .only(if (ui.item != null) WindowInsetsSides.Top + WindowInsetsSides.Bottom
                            else WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom)
                        else ScaffoldDefaults.contentWindowInsets, bottomBar = {
                    // Without the navigation bar under it, the mini player keeps clear of the system
                    // bars itself; the bottom one, and the end one where three-button navigation sits.
                    Column((if (rail) Modifier.windowInsetsPadding(
                        WindowInsets.systemBars.only(WindowInsetsSides.End + WindowInsetsSides.Bottom)) else Modifier)
                        .frostedBehind(frost, backdrop = colors.background)) {
                        // Sideways, the player is a panel beside the list instead (mockup B).
                        // With Expressive motion it stays while the player is open, since the player grows
                        // out of it and shrinks back into it (PlayerMorph); the player covers it meanwhile.
                        AnimatedVisibility(visible = ui.item != null && (!overlayOpen || Expressive.motion) && !(rail && connected),
                            enter = slideInVertically(motionSpatial()) { it } + fadeIn(motionShort()),
                            exit = slideOutVertically(motionSpatial()) { it } + fadeOut(motionShort())) {
                            // In the song cover's colours, as Now Playing is, so the player grows out of a
                            // mini player already wearing them; the navigation bar keeps the library's.
                            ArtworkTheme(ui.item) {
                                MiniPlayer(ui, position, player != null,
                                    active = connected && player != null && ui.item != null && !overlayOpen,
                                    sheet = sheet,
                                    open = {
                                        if (model.endpoint != null && player != null && playback.ui.item != null && !overlayOpen)
                                            playerOpen = true
                                    },
                                    toggle = { if (ui.playing) player?.pause() else player?.play() },
                                    next = { player?.seekToNextMediaItem() },
                                    previous = { player?.seekToPreviousMediaItem() }, morph = morph,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = barTint))
                            }
                        }
                        AnimatedVisibility(visible = connected && !rail,
                            enter = slideInVertically(motionSpatial()) { it } + fadeIn(motionShort()),
                            exit = slideOutVertically(motionSpatial()) { it } + fadeOut(motionShort())) {
                            // The Canary experiment: Material 3 Expressive's flexible navigation bar,
                            // shorter, with a pill behind the chosen tab's icon.
                            // 6 dp more room above the pill than the flexible bar's own: with no edge to the
                            // glass, its tight top made the bar look cut short (the user's layout-bounds
                            // check on the Pixel). Added as an inset, so the bar's tint covers it.
                            if (Expressive.motion) ShortNavigationBar(containerColor = colors.background.copy(alpha = barTint),
                                windowInsets = ShortNavigationBarDefaults.windowInsets.add(WindowInsets(top = NAV_BAR_TOP_ROOM))) {
                                Tab.entries.forEach { destination ->
                                    ShortNavigationBarItem(selected = tab == destination,
                                        onClick = { tab = destination; fromSearch = false },
                                        icon = { TabIcon(destination) }, label = { Text(destination.name) })
                                }
                            } else NavigationBar(containerColor = colors.background.copy(alpha = barTint), tonalElevation = 0.dp) {
                                Tab.entries.forEach { destination ->
                                    NavigationBarItem(selected = tab == destination,
                                        onClick = { tab = destination; fromSearch = false },
                                        icon = { TabIcon(destination) }, label = { Text(destination.name) })
                                }
                            }
                        }
                    }
                }) { padding ->
                    // Under glass the content runs to the bottom, behind the bars, and each list adds the
                    // bars' height to its own end padding instead (LocalUnderBars), so its last row can
                    // still scroll clear of them.
                    val direction = LocalLayoutDirection.current
                    CompositionLocalProvider(LocalUnderBars provides if (frost != null) padding.calculateBottomPadding() else 0.dp,
                        LocalQueueSong provides queueFromSwipe.takeIf { player != null && connected }) {
                    Column(Modifier.frostSource(frost)
                        .then(if (frost != null) Modifier.padding(start = padding.calculateStartPadding(direction),
                            top = padding.calculateTopPadding(), end = padding.calculateEndPadding(direction))
                            else Modifier.padding(padding))
                        .fillMaxSize()) {
                        AnimatedVisibility(controllerError != null,
                            enter = expandVertically(motionSpatialFull()) + fadeIn(motionShort()),
                            exit = shrinkVertically(motionSpatialFull()) + fadeOut(motionShort())) {
                            ErrorCard(controllerError.orEmpty())
                        }
                        AnimatedVisibility(connected && model.error != null && tab != Tab.Settings,
                            enter = expandVertically(motionSpatialFull()) + fadeIn(motionShort()),
                            exit = shrinkVertically(motionSpatialFull()) + fadeOut(motionShort())) {
                            // Without Android 17's local network access, Retry cannot help: the card asks for it instead.
                            if (!LocalNetworkState.granted) ErrorCard(model.error.orEmpty(), "Allow", quiet = true) { allowLocalNetwork() }
                            else ErrorCard(model.error.orEmpty(), "Retry", quiet = model.offline) { model.connect() }
                        }
                        BusyStrip(model.busy && connected)
                        // Tabs are siblings, so this fades with a small lift rather than sliding sideways.
                        AnimatedContent(if (connected) tab else null, transitionSpec = {
                            (fadeIn(motionMedium()) + slideInVertically(motionSpatial()) { it / 24 })
                                .togetherWith(fadeOut(motionShort()))
                        }, label = "screen") { shown ->
                            when (shown) {
                                null -> ConnectScreen(model, ::allowLocalNetwork)
                                Tab.Settings -> SettingsScreen(model, appearance) {
                                    player?.stop(); player?.clearMediaItems(); model.disconnect()
                                    // Nothing from the server just left is shown again or kept on disk.
                                    val disk = ArtworkStore.disk(context)
                                    scope.launch(Dispatchers.IO) { forgetArtwork(disk) }
                                    openOrigin = null; openId = null; closeArtist(); closeAlbum()
                                    songList = LazyListState(); artistList = LazyListState(); albumGrid = LazyGridState()
                                    playlistList = LazyListState()
                                    libraryBar = TopAppBarState(-Float.MAX_VALUE, 0f, 0f)
                                    lyricsOpen = false; queueOpen = false; playerOpen = false; tab = Tab.Library; fromSearch = false
                                    searchOpen = false
                                }
                                Tab.Library -> {
                                    val page = libraryPage(openList?.id, artistPage, artistKey, albumPage, albumKey)
                                    val shift = with(LocalDensity.current) { LIBRARY_PAGE_SHIFT.roundToPx() }
                                    // Opening a playlist or an artist steps down a level, so the page
                                    // moves along the reading direction; Back reverses it. Back itself is
                                    // still decided above, from the logical state, never from here.
                                    SharedTransitionLayout {
                                    val shared = this
                                    AnimatedContent(page, contentKey = { it.key }, label = "library page",
                                        transitionSpec = { libraryPageTransform(shift) }) { shown ->
                                        val leaving = transition.targetState != EnterExitState.Visible
                                        // The pages share their pictures while one replaces another (motion pass 2).
                                        CompositionLocalProvider(LocalSharedMotion provides SharedMotion(shared, this)) {
                                        Box(Modifier.fillMaxSize().leaving(leaving)) {
                                            when (shown) {
                                                is LibraryPage.Album -> {
                                                    val album = keptWhileLeaving(leaving,
                                                        openAlbum?.takeIf { it.key == shown.albumKey })
                                                    val title = keptWhileLeaving(leaving, albumTitle.orEmpty())
                                                    LibraryPane(model.busy, { model.connect() }) {
                                                        AlbumPage(album, title, model.endpoint, ui.item?.mediaId, ui.playing, player != null,
                                                            backLabel = when {
                                                                shown.fromArtist != null -> "Back to artist"
                                                                fromSearch -> "Back to search"
                                                                else -> "Back to albums"
                                                            },
                                                            actions = { actionTrack = it }, back = { closePage() },
                                                            playAll = { shuffle -> album?.let { playAll(it.tracks, shuffle) } }) {
                                                            startQueue(album?.tracks.orEmpty(), it)
                                                        }
                                                    }
                                                }
                                                is LibraryPage.Artist -> {
                                                    val artist = keptWhileLeaving(leaving,
                                                        openArtist?.takeIf { it.key == shown.artistKey })
                                                    val name = keptWhileLeaving(leaving, artistName.orEmpty())
                                                    val appearsOn = remember(artist, albums) {
                                                        if (artist == null || albums == null) emptyList() else artistAlbums(artist, albums)
                                                    }
                                                    LibraryPane(model.busy, { model.connect() }) {
                                                        ArtistPage(artist, name, appearsOn, model.endpoint, ui.item?.mediaId, ui.playing,
                                                            player != null, keptWhileLeaving(leaving, artistPageList),
                                                            backLabel = if (fromSearch) "Back to search" else "Back to artists",
                                                            actions = { actionTrack = it }, back = { closePage() },
                                                            playAll = { shuffle -> artist?.let { playAll(it.tracks, shuffle) } },
                                                            // Opened over this page, so Back returns here.
                                                            openAlbum = { albumOrigin = origin; albumKey = it.key; albumTitle = it.title }) {
                                                            startQueue(artist?.tracks.orEmpty(), it)
                                                        }
                                                    }
                                                }
                                                LibraryPage.Top ->
                                                    // Greeting, chips, then the list; only the list pulls, and
                                                    // the greeting unfolds before a pull begins.
                                                    LibraryTop(all.size, library.view, library::choose,
                                                        model.busy, { model.connect() }, libraryBar, titled = !rail, offline = model.offline) {
                                                        when (library.view) {
                                                            // A new order starts from its top, rather than
                                                            // wherever the previous first row now sits.
                                                            LibraryView.Songs -> Column {
                                                                // Counted like Albums and Artists (#16 QA).
                                                                if (songs.isNotEmpty()) SortBar("${songs.size} ${if (songs.size == 1) "song" else "songs"}",
                                                                    SongOrder.entries, library.songOrder, { it.label }) {
                                                                    library.chooseSongOrder(it); songList = LazyListState()
                                                                }
                                                                // Plays on in the order shown.
                                                                TrackList(songs, model.endpoint, ui.item?.mediaId, player != null, ui.playing, actions = { actionTrack = it },
                                                                    emptyText = "No music yet. Add local music in Tauon, then refresh.",
                                                                    loading = model.busy, state = songList,
                                                                    // A–Z only: in Recently added there are no letters to jump to.
                                                                    sections = if (library.songOrder == SongOrder.Title) {
                                                                        { t: TauonTrack -> sectionLetter(t.title) }
                                                                    } else null) { startQueue(songs, it) }
                                                            }
                                                            LibraryView.Albums -> Column {
                                                                if (!sortedAlbums.isNullOrEmpty()) SortBar(
                                                                    "${sortedAlbums.size} ${if (sortedAlbums.size == 1) "album" else "albums"}",
                                                                    AlbumOrder.entries, library.albumOrder, { it.label }) {
                                                                    library.chooseAlbumOrder(it); albumGrid = LazyGridState()
                                                                }
                                                                AlbumGrid(sortedAlbums, model.busy, model.endpoint, albumGrid,
                                                                    recent = if (Expressive.motion) recent else emptyList()) {
                                                                    albumOrigin = origin; albumKey = it.key; albumTitle = it.title
                                                                }
                                                            }
                                                            LibraryView.Artists -> Column {
                                                                if (!sortedArtists.isNullOrEmpty()) SortBar(
                                                                    "${sortedArtists.size} ${if (sortedArtists.size == 1) "artist" else "artists"}",
                                                                    ArtistOrder.entries, library.artistOrder, { it.label }) {
                                                                    library.chooseArtistOrder(it); artistList = LazyListState()
                                                                }
                                                                ArtistRows(sortedArtists, model.busy, artistList, model.endpoint) {
                                                                    artistOrigin = origin; artistKey = it.key; artistName = it.name
                                                                }
                                                            }
                                                            LibraryView.Playlists -> PlaylistRows(model.playlists, model.busy, playlistList,
                                                                model.tracksByPlaylist, model.endpoint) {
                                                                openOrigin = origin; openId = it
                                                            }
                                                        }
                                                    }
                                                is LibraryPage.Playlist -> {
                                                    val open = keptWhileLeaving(leaving,
                                                        openList?.takeIf { it.id == shown.id })
                                                    if (open != null) Column {
                                                        // One playlist keeps its own compact bar: its name is the
                                                        // heading, and a greeting would be in the way.
                                                        PlaylistBar(open.name, open.count) { closePage() }
                                                        LibraryPane(model.busy, { model.connect() }) {
                                                            val tracks = model.tracksByPlaylist[open.id].orEmpty()
                                                            TrackList(tracks, model.endpoint, ui.item?.mediaId, player != null, ui.playing, actions = { actionTrack = it },
                                                                emptyText = "This playlist is empty. Add local music in Tauon, then refresh.",
                                                                // Play, Shuffle and Download all, as on an album or an artist
                                                                // (#112 for the download).
                                                                header = {
                                                                    item(key = "actions", contentType = "actions") {
                                                                        PlayAllButtons(player != null && tracks.any { it.playable }) { playAll(tracks, it) }
                                                                    }
                                                                    item(key = "download", contentType = "download") { DownloadAll(tracks, model.endpoint) }
                                                                },
                                                                loading = model.busy) { startQueue(tracks, it) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        }
                                    }
                                    }
                                }
                                Tab.Search -> {
                                    val openArtistPage = { a: LibraryArtist -> openFromSearch { artistOrigin = origin; artistKey = a.key; artistName = a.name } }
                                    val openAlbumPage = { a: LibraryAlbum -> openFromSearch { albumOrigin = origin; albumKey = a.key; albumTitle = a.title } }
                                    val keyboard = LocalSoftwareKeyboardController.current
                                    SearchScreen(query, { query = it }, searchOpen, { searchOpen = it }, search.searching,
                                        browseArtists, browseAlbums, model.endpoint, openArtistPage, openAlbumPage) {
                                        TrackList(search.tracks, model.endpoint, ui.item?.mediaId, player != null, ui.playing, actions = { actionTrack = it },
                                            emptyText = searchEmptyText(query, search.searching, search.completed), state = searchList,
                                            header = { searchCollection(foundArtists, foundAlbums, model.endpoint, openArtistPage, openAlbumPage) }) {
                                            // A search finds where to start, not what to play: the song plays on
                                            // through the whole library in the Songs order, so Next and Shuffle
                                            // reach every song rather than only the few that matched.
                                            // The keyboard goes, so the mini player shows what started.
                                            keyboard?.hide()
                                            startQueue(songs, it)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                }
                // A phone on its side: the player sits beside the list rather than under it
                // (mockup B), clear of the status bar, the navigation bar and a side cutout.
                AnimatedVisibility(visible = rail && connected && ui.item != null,
                    enter = slideInHorizontally(motionSpatial()) { it } + fadeIn(motionShort()),
                    exit = slideOutHorizontally(motionSpatial()) { it } + fadeOut(motionShort())) {
                    // Lyrics and Queue are layered over the player, so they open it too; Back from
                    // either then steps down through Now Playing, as it does when they are opened there.
                    fun openPlayer(): Boolean {
                        if (model.endpoint != null && player != null && playback.ui.item != null) playerOpen = true
                        return playerOpen
                    }
                    PlayerPanel(ui, position, revision, player,
                        open = { if (!overlayOpen) openPlayer() },
                        lyrics = { if (openPlayer()) lyricsOpen = true },
                        queue = { if (openPlayer()) queueOpen = true },
                        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom))
                            .padding(top = 8.dp, end = 12.dp, bottom = 8.dp))
                }
            }
            actionTrack?.let { track ->
                // What the page on show already is, it does not offer to go to.
                val here = libraryPage(openList?.id, artistPage, artistKey, albumPage, albumKey).takeIf { tab == Tab.Library }
                SongActionsSheet(track, model.endpoint, canQueue = player != null && track.playable,
                    album = songAlbum(track, albums)?.takeIf { !(here is LibraryPage.Album && here.albumKey == it.key) },
                    artists = songArtists(track, artists).filter { !(here is LibraryPage.Artist && here.artistKey == it.key) },
                    dismiss = { actionTrack = null }, queue = { next -> queueSong(track, next) },
                    goToAlbum = ::goToAlbum, goToArtist = ::goToArtist,
                    download = downloadMark(model.endpoint, track), canDownload = model.endpoint != null && track.playable,
                    toggleDownload = {
                        model.endpoint?.let { endpoint ->
                            if (DownloadMarks.marks[downloadId(endpoint.origin, track.id)] != null)
                                OfflineStore.remove(context, listOf(downloadId(endpoint.origin, track.id)))
                            else OfflineStore.add(context, endpoint, listOf(track))
                        }
                    })
            }
            // Dims the library under the player, so a player being dragged, closed or previewed
            // by Back reads as a sheet over it rather than more of the same surface. It stays
            // put while the sheet moves, follows only the player's own visibility, and is gone
            // entirely once the player has closed.
            // Also shown while a preview is rising, so new touches cannot reach the library under
            // it; a finger already down keeps its own stream, so the drag itself carries on.
            // With Expressive motion it deepens with the player's growth instead (PlayerMorph), so a drag
            // that has barely begun, or has come back down, dims nothing, the mini player included.
            AnimatedVisibility(visible = playerShown || sheet.previewing, enter = fadeIn(motionMedium()),
                exit = fadeOut(motionMedium())) {
                PlayerScrim { morph.scrimFactor() }
            }
            // The player rises from the bottom, where the mini player it grew out of sits, and
            // shrinks while a Back gesture is deciding whether to close it.
            // In the colours of the song's cover, which fade from one song to the next.
            ArtworkTheme(ui.item) {
            // Captured here, where the cover's scheme is in force, for the mini player's face below.
            val tinted = MaterialTheme.colorScheme
            PlayerHost(sheet, open = playerShown, backdrop = ui.item?.mediaMetadata?.artworkUri?.toString(), morph = morph,
                // The mini player as the panel starts: its colour and content, in the cover's colours
                // exactly as the mini player draws them. Under glass that is its translucent tint, over
                // the same blurred library the mini player shows (frostedPanel), thickening to Now
                // Playing's colour as the panel grows.
                miniColor = tinted.surfaceVariant.copy(alpha = barTint), frost = frost, libraryGround = colors.background, face = {
                    MaterialTheme(colorScheme = tinted) {
                        CompositionLocalProvider(LocalContentColor provides tinted.onSurfaceVariant) {
                            // Its cover shows until the flying one takes over, which needs Now
                            // Playing laid out first: without it, the first frame of a drag
                            // showed an empty space where the cover had been.
                            MiniPlayerFace(ui, position, ready = player != null, toggle = {}, next = {},
                                cover = Modifier.graphicsLayer { alpha = if (morph.frame() != null) 0f else 1f })
                        }
                    }
                }, preview = {
                rememberPlayerBackPreview(playerShown) { if (playerGestureCommits(target)) goBack() }
            }) {
                // Only a player that is actually on screen carries the drag: an outgoing one hands
                // nothing down, so its detector goes with it rather than moving what comes next.
                NowPlayingOverlay(ui, position, revision, player, sheet.takeIf { playerShown },
                    // Guarded, so a drag that ends after Lyrics opened over the player, or after
                    // the player has gone, cannot put away whatever took its place.
                    collapse = { if (playerShown) playerOpen = false },
                    queue = { queueOpen = true }, morph = morph) { lyricsOpen = true }
            }
            }
            // The cover flying between the mini player and Now Playing while the player grows or
            // shrinks, over both (PlayerMorph). Only while any of the player is on screen.
            if (playerSheetPresent(playerShown, sheet.onScreen)) MorphingCover(morph, ui.item?.mediaMetadata?.artworkUri?.toString())
            FullScreenOverlay(visible = lyricsShown) {
                LyricsScreen(ui.item) { lyricsOpen = false }
            }
            FullScreenOverlay(visible = queueShown) {
                QueueScreen(player, revision, ui.playing) { queueOpen = false }
            }
        }
    }
}

@Composable
private fun TabIcon(destination: Tab) {
    MuonIcon(when (destination) { Tab.Library -> "library"; Tab.Search -> "search"; Tab.Settings -> "settings" })
}

/** What a Back press acts on, named in the order the screens are stacked. */
internal enum class BackTarget { None, Lyrics, Queue, Player, Tab, Playlist }

internal fun backTarget(connected: Boolean, lyricsShown: Boolean, overlayOpen: Boolean,
    onLibraryTab: Boolean, playlistOpen: Boolean, queueShown: Boolean = false): BackTarget = when {
    !connected -> BackTarget.None
    lyricsShown -> BackTarget.Lyrics
    queueShown -> BackTarget.Queue
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
    target == BackTarget.Lyrics || target == BackTarget.Queue || target == BackTarget.Player

/** Material's modal-sheet scrim opacity (`ScrimTokens.ContainerOpacity` in material3 1.4.0). */
private const val PLAYER_SCRIM_ALPHA = 0.32f

/**
 * Blocks touches to the library it dims, the same way Material's own `Surface` does, and adds
 * nothing a screen reader could focus or activate: closing stays with the collapse button and Back.
 */
@Composable
private fun PlayerScrim(amount: () -> Float) {
    val scrim = MaterialTheme.colorScheme.scrim
    Box(Modifier.fillMaxSize()
        .drawBehind { drawRect(scrim.copy(alpha = PLAYER_SCRIM_ALPHA * amount().coerceIn(0f, 1f))) }
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
        enter = slideInVertically(motionSpatialFull()) { it } + fadeIn(motionShort()),
        exit = slideOutVertically(motionSpatialFull()) { it } + fadeOut(motionShort())) {
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
private fun PlayerHost(sheet: PlayerSheet, open: Boolean, backdrop: String?, morph: PlayerMorph,
    miniColor: Color, frost: Frost?, libraryGround: Color, face: @Composable () -> Unit,
    preview: @Composable () -> PlayerBackPreview?, content: @Composable () -> Unit) {
    if (!playerSheetPresent(open, sheet.onScreen)) return
    // Two layers, each owning its own properties: the sheet moves the surface, the Back preview
    // scales and drifts it, and neither writes what the other reads. On pure black the sheet also
    // outlines its top edge, since nothing else can show where the player ends.
    val colors = MaterialTheme.colorScheme
    val edge = colors.outlineVariant.takeIf { colors.background == Color.Black }
    val ground = colors.background
    // The panel paints its own ground: Now Playing's, or, while it grows out of the mini player, the
    // mini player's [miniColor] turning into it (PlayerMorph), over the mini player's glass when the
    // bars are frosted ([frost]). Read in the draw phase.
    Surface(Modifier.fillMaxSize()
        .playerSheet(sheet, WindowInsets.safeDrawing, edge, morph).playerBackPreview(preview())
        .frostedPanel(frost, libraryGround) { morph.growing() }
        .drawBehind { drawRect(morph.panelColor(miniColor, ground)) },
        color = Color.Transparent, contentColor = colors.onBackground) {
        // The cover's glass (CoverBackdrop.kt) fills the whole sheet, status bar included, and arrives
        // with Now Playing's colour while the panel grows.
        if (showCoverBackdrop(Expressive.blur, colors.background))
            CoverBackdrop(backdrop, Modifier.graphicsLayer { alpha = morph.backdropAlpha() })
        // The mini player's own content, where it was, while the panel grows out of it; beneath Now
        // Playing, fading out as that fades in. It is a picture of the mini player: it takes no touch
        // and says nothing to a screen reader.
        if (Expressive.motion) Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().graphicsLayer { alpha = morph.faceAlpha() }.clearAndSetSemantics {}) {
                face()
                Box(Modifier.matchParentSize().pointerInput(Unit) {})
            }
        }
        // The content gives back the top inset as the sheet drops below the status bar.
        // Measured at the sheet's own top left, before the inset it gives back, so the flying cover
        // can find Now Playing's cover inside it; and faded in while it grows (PlayerMorph).
        Box(Modifier.onGloballyPositioned { morph.body = it }.graphicsLayer { alpha = morph.contentAlpha() }
            .reclaimTopInset(sheet, WindowInsets.safeDrawing).safeDrawingPadding()) {
            Box(if (open) Modifier else Modifier.clearAndSetSemantics {}) { content() }
            if (!open) Box(Modifier.matchParentSize().pointerInput(Unit) {})
        }
    }
}
