package dev.avery.muon

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Screen { Library, Search, Playing, Lyrics, Settings, Connect }

/**
 * Everything about playback that changes only on a player event. The moving position is kept out
 * of this value deliberately: it is compared for equality on every event, so screens that do not
 * show a clock are not recomposed while a track plays.
 */
@Immutable
private data class PlaybackUi(val item: MediaItem? = null, val playing: Boolean = false,
    val duration: Long = 0, val seekable: Boolean = false,
    val buffering: Boolean = false, val error: String? = null, val previous: Boolean = false,
    val next: Boolean = false, val shuffle: Boolean = false,
    @Player.RepeatMode val repeatMode: Int = Player.REPEAT_MODE_OFF)

/** Playback state split so that the ticking position invalidates only the widgets that draw it. */
@Stable
private class PlaybackState {
    var ui by mutableStateOf(PlaybackUi())
    var position by mutableLongStateOf(0L)
}

@Composable
private fun rememberPlayback(player: MediaController?): PlaybackState {
    val state = remember { PlaybackState() }
    DisposableEffect(player) {
        fun update() {
            state.ui = if (player == null) PlaybackUi() else PlaybackUi(player.currentMediaItem, player.isPlaying,
                player.duration.coerceAtLeast(0),
                player.isCurrentMediaItemSeekable, player.playbackState == Player.STATE_BUFFERING,
                player.playerError?.let { "${it.errorCodeName}: ${it.cause?.let(::friendlyError) ?: it.message}" },
                player.hasPreviousMediaItem(), player.hasNextMediaItem(), player.shuffleModeEnabled,
                player.repeatMode)
            state.position = player?.currentPosition?.coerceAtLeast(0) ?: 0L
        }
        val listener = object : Player.Listener { override fun onEvents(p: Player, events: Player.Events) { update() } }
        player?.addListener(listener); update()
        onDispose { player?.removeListener(listener) }
    }
    val playing = state.ui.playing
    LaunchedEffect(player, playing) {
        if (player == null || !playing) return@LaunchedEffect
        while (true) {
            state.position = player.currentPosition.coerceAtLeast(0)
            delay(POSITION_TICK_MS)
        }
    }
    return state
}

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
        val results by key(all) {
            produceState(emptyList<TauonTrack>(), query) {
                if (query.isBlank()) { value = emptyList(); return@produceState }
                delay(SEARCH_DEBOUNCE_MS)
                value = withContext(Dispatchers.Default) { searchTracks(all, query) }
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
                if (ui.item != null && shownScreen != Screen.Playing) {
                    MiniPlayer(ui, position, player != null, { screen = Screen.Playing }, {
                        if (ui.playing) player?.pause() else player?.play()
                    })
                }
                if (connected) NavigationBar(containerColor = colors.background, tonalElevation = 0.dp) {
                    listOf(Screen.Library, Screen.Search, Screen.Playing, Screen.Settings).forEach { destination ->
                        NavigationBarItem(selected = shownScreen == destination || (destination == Screen.Playing && shownScreen == Screen.Lyrics),
                            onClick = { screen = destination },
                            icon = { MuonIcon(when (destination) {
                                Screen.Library -> "library"; Screen.Search -> "search"; Screen.Playing -> "music"; else -> "settings"
                            }) }, label = { Text(if (destination == Screen.Playing) "Playing" else destination.name) })
                    }
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (controllerError != null) ErrorCard(controllerError)
                if (connected && model.error != null && shownScreen != Screen.Settings) {
                    ErrorCard(model.error!!, "Retry") { model.connect() }
                }
                if (model.busy && connected) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (shownScreen) {
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
                                emptyText = "This playlist is empty. Add local music in Tauon, then refresh.") { startQueue(tracks, it) }
                        }
                    }
                    Screen.Search -> {
                        Column {
                            Text("Find your next listen", style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.padding(24.dp))
                            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                                placeholder = { Text("Songs, artists, albums") }, leadingIcon = { MuonIcon("search") },
                                singleLine = true, shape = RoundedCornerShape(18.dp),
                                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
                            Text("Search across loaded Tauon playlists", color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                            TrackList(results, model.endpoint, ui.item?.mediaId, player != null,
                                emptyText = if (query.isBlank()) "Your collection, one search away." else "No matching tracks in the loaded playlists.") { startQueue(results, it) }
                        }
                    }
                    Screen.Playing -> NowPlaying(ui, position, player) { screen = Screen.Lyrics }
                    Screen.Lyrics -> LyricsScreen(ui.item) { screen = Screen.Playing }
                }
            }
        }
    }
}

@Composable
private fun ConnectScreen(model: LibraryModel) {
    val context = LocalContext.current
    var discovered by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var discoveryMessage by remember { mutableStateOf("") }
    val discovery = remember { ServerDiscovery(context, { name, url -> discovered = discovered + (url to name) }, { discoveryMessage = it }) }
    DisposableEffect(discovery) { onDispose { discovery.stop() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("MUON", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text("Bring your\nlibrary along.", style = MaterialTheme.typography.headlineLarge)
        Text("Stream your Tauon collection to this device. Original audio, your playlists, wherever your LAN reaches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsCard("Connect to Tauon desktop") {
            Text("Enable remote control in Tauon and restart it. Keep both devices on the same trusted LAN.")
            OutlinedTextField(model.address, { model.address = it }, label = { Text("Server address") },
                placeholder = { Text("192.168.1.10:7814") }, singleLine = true,
                enabled = !model.busy, modifier = Modifier.fillMaxWidth())
            Button(onClick = { model.connect() }, enabled = !model.busy && model.address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (model.busy) "Connecting…" else "Connect")
            }
            if (model.progress.isNotEmpty()) Text(model.progress, style = MaterialTheme.typography.bodySmall)
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        model.error?.let { ErrorCard(it) }
        SettingsCard("Find Tauon on my LAN") {
            Text("Discovery needs Tauon to advertise itself. Typing the address always works.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { discovered = emptyMap(); discovery.start() }, enabled = !model.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Scan this network") }
            if (discoveryMessage.isNotEmpty()) Text(discoveryMessage, style = MaterialTheme.typography.bodySmall)
            discovered.forEach { (url, name) -> DiscoveredServer(name, url) { model.address = url } }
        }
        PrivacyNote()
    }
}

@Composable
private fun SettingsScreen(model: LibraryModel, appearance: AppearanceSettings, disconnect: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("MUON", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        SettingsCard("Tauon desktop · connected") {
            Text(model.address, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (model.progress.isNotEmpty()) Text(model.progress, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { model.connect() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (model.busy) "Refreshing…" else "Refresh connection & library")
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = disconnect) { Text("Disconnect & stop playback") }
        }
        model.error?.let { ErrorCard(it) }
        AppearanceSection(appearance)
        PrivacyNote()
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DiscoveredServer(name: String, url: String, use: () -> Unit) {
    // A found server is a list entry, not a button with two lines of text crammed into it.
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = use)
        .padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Use", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PrivacyNote() {
    HorizontalDivider()
    Text("A private connection", style = MaterialTheme.typography.titleMedium)
    Text("Tauon's remote API is for trusted LANs. It has no login or encryption over HTTP. Never expose port 7814 to the Internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Direct original audio · No transcoding\nAndroid playback · Desktop playback stays independent", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AppearanceSection(appearance: AppearanceSettings) {
    val dynamicAvailable = dynamicColorAvailable(Build.VERSION.SDK_INT)
    // Show what will actually render: a device without dynamic colour cannot honour Material You.
    val shown = effectivePalette(appearance.palette, dynamicAvailable)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Text("Light and dark still follow your system setting. This chooses where the colours come from.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            PaletteChoice.entries.forEach { choice ->
                PaletteOption(choice, shown == choice,
                    enabled = choice != PaletteChoice.MaterialYou || dynamicAvailable) { appearance.choose(choice) }
            }
            if (!dynamicAvailable) Text("Material You needs Android 12 or newer, so this device uses the Muon palette.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .toggleable(value = appearance.amoled, role = Role.Switch) { appearance.chooseAmoled(it) }
                .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Pure black", style = MaterialTheme.typography.bodyLarge)
                    Text("Backgrounds go fully black on OLED screens. Accents are unchanged, and this only applies while your system is in dark mode.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = appearance.amoled, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun PaletteOption(choice: PaletteChoice, selected: Boolean, enabled: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = select)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // A radio mark, not a tint, so the choice is readable without relying on colour.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(paletteLabel(choice), style = MaterialTheme.typography.bodyLarge)
            Text(paletteDescription(choice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrackList(tracks: List<TauonTrack>, endpoint: ServerEndpoint?, currentId: String?, ready: Boolean,
    emptyText: String, play: (TauonTrack) -> Unit) {
    if (tracks.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val keys = remember(tracks) { trackKeys(tracks) }
        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(tracks, key = { i, _ -> keys[i] }, contentType = { _, _ -> "track" }) { _, t ->
                TrackRow(t, endpoint, currentId == "${endpoint?.origin}/${t.id}", ready) { play(t) }
            }
        }
    }
}

@Composable
private fun TrackRow(t: TauonTrack, endpoint: ServerEndpoint?, current: Boolean, ready: Boolean, play: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .clickable(enabled = t.playable && ready, onClick = play)
        .padding(horizontal = 24.dp, vertical = 8.dp)
        .then(if (current) Modifier.semantics { stateDescription = "Now playing" } else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        // The current track is marked by something appearing, not only by a change of hue. The
        // marker reserves its width either way so every row starts on the same line.
        Box(Modifier.width(3.dp).height(32.dp)
            .then(if (current) Modifier.background(MaterialTheme.colorScheme.primary,
                RoundedCornerShape(2.dp)) else Modifier))
        Spacer(Modifier.width(9.dp))
        Artwork(endpoint?.url("/api1/pic/small/${t.id}"), Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(t.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium)
            Text(if (t.playable) "${t.artist} · ${t.album}" else "Unavailable for direct streaming · ${t.artist}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
        }
        // A minimum width keeps the durations on one right edge; a long duration or a large font
        // scale grows the column instead of clipping, taking the space from the title beside it.
        Text(formatTime(t.durationMs), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
            maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 44.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryBar(busy: Boolean, refresh: () -> Unit) {
    TopAppBar(title = { Text("Library", style = MaterialTheme.typography.headlineSmall) },
        actions = { TextButton(onClick = refresh, enabled = !busy) { Text("Refresh") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        // The scaffold already applies the status bar inset to this content.
        windowInsets = WindowInsets(0, 0, 0, 0))
}

@Composable
private fun PlaylistChips(playlists: List<TauonPlaylist>, total: Int, selected: String?, select: (String?) -> Unit) {
    // Deliberately plain: an earlier edge fade used an offscreen compositing layer and a DstOut
    // blend, which the user reported as a scroll regression. The chips overflow past the padding
    // instead, which costs nothing to draw.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryChip("All music · $total", selected == null) { select(null) }
        playlists.forEach { p -> LibraryChip("${p.name} · ${p.count}", selected == p.id) { select(p.id) } }
    }
}

@Composable
private fun LibraryChip(label: String, selected: Boolean, select: () -> Unit) {
    FilterChip(selected, select, label = { Text(label, maxLines = 1) },
        // A check mark, so the selected chip is not distinguished by its fill colour alone.
        leadingIcon = if (selected) { { MuonIcon("check", Modifier.size(18.dp)) } } else null)
}

@Composable
private fun MiniPlayer(p: PlaybackUi, position: () -> Long, ready: Boolean, open: () -> Unit, toggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().clickable(onClick = open)) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(p.item?.mediaMetadata?.artworkUri?.toString(), Modifier.size(44.dp).clip(RoundedCornerShape(9.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(p.item?.mediaMetadata?.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (p.error != null) "Playback interrupted · tap to retry" else if (p.buffering) "Buffering…" else p.item?.mediaMetadata?.artist?.toString().orEmpty(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Control(if (p.playing) "pause" else "play", if (p.playing) "Pause" else "Play", ready, toggle)
            }
            LinearProgressIndicator(progress = { progressFraction(position(), p.duration) },
                modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
private fun NowPlaying(p: PlaybackUi, position: () -> Long, player: MediaController?, lyrics: () -> Unit) {
    if (p.item == null) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text("Choose a track from your library to start listening.") }
        return
    }
    var showVolume by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val narrow = maxWidth < 360.dp
        // Keep the compact portrait design, but allow every control to remain reachable in
        // landscape, split screen, large text, or when an error needs additional space.
        val scrollable = maxHeight < 600.dp * fontScale || narrow || p.error != null
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize()
            .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PLAYING ON THIS DEVICE", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium)
            Box((if (scrollable) Modifier.height(160.dp) else Modifier.weight(1f)).fillMaxWidth(),
                contentAlignment = Alignment.Center) {
                Artwork(p.item.mediaMetadata.artworkUri?.toString(),
                    Modifier.widthIn(max = 400.dp).aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
            }
            Column(Modifier.fillMaxWidth()) {
                Text(p.item.mediaMetadata.title?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p.item.mediaMetadata.artist?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.item.mediaMetadata.albumTitle?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (p.error != null) ErrorCard(p.error, "Retry stream") { player?.prepare(); player?.play() }
            if (p.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            SeekControls(p.item.mediaId, position, p.duration, p.seekable, player)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                if (!narrow) ShuffleControl(p, player)
                Control("previous", "Previous track", p.previous && player != null) { player?.seekToPreviousMediaItem() }
                FilledIconButton(onClick = { if (p.playing) player?.pause() else player?.play() }, enabled = player != null,
                    modifier = Modifier.size(72.dp).semantics { contentDescription = if (p.playing) "Pause" else "Play" }) {
                    MuonIcon(if (p.playing) "pause" else "play", Modifier.size(32.dp))
                }
                Control("next", "Next track", p.next && player != null) { player?.seekToNextMediaItem() }
                if (!narrow) RepeatControl(p, player)
            }
            if (narrow) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ShuffleControl(p, player)
                RepeatControl(p, player)
            }
            // Wrapping preserves readable labels at large font/display sizes.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = lyrics) { Text("Lyrics") }
                TextButton(onClick = { showVolume = true }) {
                    MuonIcon("volume", Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Volume")
                }
            }
        }
    }
    if (showVolume) MediaVolumeDialog { showVolume = false }
}

@Composable
private fun ShuffleControl(p: PlaybackUi, player: MediaController?) {
    ToggleControl("shuffle", "Shuffle", if (p.shuffle) "On" else "Off", p.shuffle, player != null) {
        player?.shuffleModeEnabled = !p.shuffle
    }
}

@Composable
private fun RepeatControl(p: PlaybackUi, player: MediaController?) {
    ToggleControl(repeatModeIcon(p.repeatMode), "Repeat", repeatModeName(p.repeatMode),
        p.repeatMode != Player.REPEAT_MODE_OFF, player != null) {
        player?.repeatMode = nextRepeatMode(p.repeatMode)
    }
}

@Composable
private fun SeekControls(mediaId: String, position: () -> Long, duration: Long, seekable: Boolean, player: MediaController?) {
    var scrub by remember(mediaId) { mutableStateOf<Float?>(null) }
    val range = duration.coerceAtLeast(1).toFloat()
    val elapsed = scrub ?: position().toFloat().coerceIn(0f, range)
    Column {
        Slider(value = elapsed, onValueChange = { scrub = it }, valueRange = 0f..range,
            onValueChangeFinished = { scrub?.let { player?.seekTo(it.toLong()) }; scrub = null },
            enabled = seekable && player != null, modifier = Modifier.semantics { contentDescription = "Seek position" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(elapsed.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MediaVolumeDialog(dismiss: () -> Unit) {
    val volume = rememberMediaVolumeController()
    val state = volume.state
    AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
        icon = { MuonIcon("volume") },
        title = { Text("Media volume") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.fixed) Text(
                    text = "Volume is fixed by this device.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                MediaVolumeSlider(state, volume::setVolume)
                volume.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}

@Composable
private fun LyricsScreen(item: MediaItem?, back: () -> Unit) {
    var lyrics by remember(item?.mediaId) { mutableStateOf("Loading lyrics…") }
    var failure by remember(item?.mediaId) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(item?.mediaId, attempt) {
        failure = false
        if (item == null) { lyrics = "Choose a track to see its lyrics."; return@LaunchedEffect }
        lyrics = "Loading lyrics…"
        try {
            val id = item.mediaId.substringAfterLast('/').toLong()
            val endpoint = ServerEndpoint.parse(item.mediaId.substringBeforeLast('/'))
            lyrics = TauonApi(endpoint).lyrics(id).ifBlank { "No lyrics stored for this track in Tauon." }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { lyrics = friendlyError(e); failure = true }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(onClick = back) { Text("‹ Now Playing") }
        Text("Lyrics", style = MaterialTheme.typography.headlineLarge)
        Text(item?.mediaMetadata?.title?.toString().orEmpty(), color = MaterialTheme.colorScheme.primary)
        if (failure) ErrorCard(lyrics, "Retry") { attempt++ }
        else SelectionContainer { Text(lyrics, style = MaterialTheme.typography.titleLarge, lineHeight = MaterialTheme.typography.headlineMedium.lineHeight) }
        Text("Stored lyrics from Tauon · Not time-synchronized", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorCard(message: String, action: String? = null, retry: () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            if (action != null) TextButton(onClick = retry) { Text(action, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}
private fun formatTime(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"

@Composable
private fun Control(kind: String, label: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) { MuonIcon(kind) }
}
@Composable
private fun ToggleControl(kind: String, label: String, state: String, active: Boolean, enabled: Boolean, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.semantics { contentDescription = label; stateDescription = state }) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            MuonIcon(kind)
            if (active) Box(Modifier.align(Alignment.BottomCenter).size(4.dp)
                .background(LocalContentColor.current, CircleShape))
        }
    }
}
@Composable
private fun MuonIcon(kind: String, modifier: Modifier = Modifier) {
    Icon(painterResource(iconRes(kind)), contentDescription = null, modifier = modifier.size(24.dp))
}
