package dev.avery.muon

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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

private enum class Screen { Library, Search, Playing, Lyrics, Settings }

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
    MuonTheme(darkTheme = darkTheme, dynamicColor = appearance.palette == PaletteChoice.MaterialYou) {
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
        val shownScreen = if (!connected) Screen.Settings else screen
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
                    Screen.Settings -> ConnectionScreen(model, connected, appearance) {
                        player?.stop(); player?.clearMediaItems(); model.disconnect(); selected = null; screen = Screen.Library
                    }
                    Screen.Library -> {
                        val tracks = if (selected == null) all else model.tracksByPlaylist[selected].orEmpty()
                        Column {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
                                Text("MUON  /  YOUR LIBRARY", style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary, letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing)
                                Spacer(Modifier.height(10.dp))
                                Text("Your music,\nnearby.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text("${all.size} tracks · Streaming from Tauon", color = colors.onSurfaceVariant)
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected == null, { selected = null }, label = { Text("All music") })
                                model.playlists.forEach { p -> FilterChip(selected == p.id, { selected = p.id }, label = { Text("${p.name} · ${p.count}") }) }
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${tracks.size} tracks", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = { model.connect() }, enabled = !model.busy) { Text("Refresh") }
                            }
                            TrackList(tracks, model.endpoint, ui.item?.mediaId, player != null,
                                emptyText = "This playlist is empty. Add local music in Tauon, then refresh.") { startQueue(tracks, it) }
                        }
                    }
                    Screen.Search -> {
                        Column {
                            Text("Find your next listen", style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(24.dp))
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
private fun ConnectionScreen(model: LibraryModel, connected: Boolean, appearance: AppearanceSettings,
    disconnect: () -> Unit) {
    val context = LocalContext.current
    var discovered by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var discoveryMessage by remember { mutableStateOf("") }
    val discovery = remember { ServerDiscovery(context, { name, url -> discovered = discovered + (url to name) }, { discoveryMessage = it }) }
    DisposableEffect(discovery) { onDispose { discovery.stop() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("MUON", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text(if (connected) "Your connection" else "Bring your\nlibrary along.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Stream your Tauon collection to this device. Original audio, your playlists, wherever your LAN reaches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (connected) "Tauon desktop · connected" else "Connect to Tauon desktop", style = MaterialTheme.typography.titleMedium)
                if (!connected) Text("Enable remote control in Tauon and restart it. Keep both devices on the same trusted LAN.")
                OutlinedTextField(model.address, { model.address = it }, label = { Text("Server address") },
                    placeholder = { Text("192.168.1.10:7814") }, singleLine = true,
                    enabled = !model.busy && !connected, modifier = Modifier.fillMaxWidth())
                Button(onClick = { model.connect() }, enabled = !model.busy && model.address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (model.busy) "Connecting…" else if (connected) "Refresh connection & library" else "Connect")
                }
                if (model.progress.isNotEmpty()) Text(model.progress, style = MaterialTheme.typography.bodySmall)
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (connected) TextButton(onClick = disconnect) { Text("Disconnect & stop playback") }
            }
        }
        model.error?.let { ErrorCard(it) }
        if (!connected) {
            OutlinedButton(onClick = { discovered = emptyMap(); discovery.start() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Find Tauon on my LAN") }
            if (discoveryMessage.isNotEmpty()) Text(discoveryMessage, style = MaterialTheme.typography.bodySmall)
            discovered.forEach { (url, name) -> OutlinedButton(onClick = { model.address = url }, modifier = Modifier.fillMaxWidth()) { Text("$name\n$url") } }
        }
        AppearanceSection(appearance)
        HorizontalDivider()
        Text("A private connection", style = MaterialTheme.typography.titleMedium)
        Text("Tauon's remote API is for trusted LANs. It has no login or encryption over HTTP. Never expose port 7814 to the Internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Direct original audio · No transcoding\nAndroid playback · Desktop playback stays independent", style = MaterialTheme.typography.bodySmall)
    }
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
    } else LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { _, t ->
            val current = currentId == "${endpoint?.origin}/${t.id}"
            Row(Modifier.fillMaxWidth().clickable(enabled = t.playable && ready) { play(t) }
                .padding(horizontal = 24.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(endpoint?.url("/api1/pic/small/${t.id}"), Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(t.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium)
                    Text(if (t.playable) "${t.artist} · ${t.album}" else "Unavailable for direct streaming · ${t.artist}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(formatTime(t.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        Text("Lyrics", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
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
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val s = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x*s, y*s)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, point(x,y), point(x2,y2), 2*s)
        fun triangle(reverse: Boolean = false) {
            val p = Path().apply { if (reverse) { moveTo(16*s,5*s); lineTo(6*s,12*s); lineTo(16*s,19*s) }
                else { moveTo(8*s,5*s); lineTo(18*s,12*s); lineTo(8*s,19*s) }; close() }
            drawPath(p,color)
        }
        when(kind) {
            "play" -> triangle()
            "pause" -> { line(8f,5f,8f,19f); line(16f,5f,16f,19f) }
            "next" -> { triangle(); line(20f,5f,20f,19f) }
            "previous" -> { triangle(true); line(4f,5f,4f,19f) }
            "search" -> { drawCircle(color,7*s,point(10f,10f),style=Stroke(2*s)); line(15f,15f,21f,21f) }
            "library" -> { line(4f,4f,4f,20f); line(9f,4f,9f,20f); line(15f,4f,20f,20f) }
            "music" -> { line(10f,4f,10f,17f); line(10f,4f,20f,2f); line(20f,2f,20f,15f); drawCircle(color,3*s,point(7f,18f)); drawCircle(color,3*s,point(17f,16f)) }
            "shuffle" -> { line(4f,7f,8f,7f); line(8f,7f,16f,17f); line(16f,17f,20f,17f)
                line(17f,14f,20f,17f); line(17f,20f,20f,17f); line(4f,17f,8f,17f); line(8f,17f,16f,7f); line(16f,7f,20f,7f)
                line(17f,4f,20f,7f); line(17f,10f,20f,7f) }
            "repeat", "repeat-one" -> { line(6f,7f,18f,7f); line(15f,4f,18f,7f); line(15f,10f,18f,7f)
                line(18f,17f,6f,17f); line(9f,14f,6f,17f); line(9f,20f,6f,17f)
                if (kind == "repeat-one") { line(12f,9.5f,12f,14.5f); line(10.6f,11f,12f,9.5f) } }
            "volume" -> { val speaker = Path().apply { moveTo(4*s,10*s); lineTo(8*s,10*s); lineTo(13*s,6*s); lineTo(13*s,18*s); lineTo(8*s,14*s); lineTo(4*s,14*s); close() }
                drawPath(speaker, color); drawArc(color, -50f, 100f, false, topLeft = point(10f,7f), size = androidx.compose.ui.geometry.Size(9*s,10*s), style = Stroke(2*s)) }
            else -> { line(3f,6f,21f,6f); line(3f,12f,21f,12f); line(3f,18f,21f,18f)
                drawCircle(color,3*s,point(8f,6f)); drawCircle(color,3*s,point(16f,12f)); drawCircle(color,3*s,point(10f,18f)) }
        }
    }
}
