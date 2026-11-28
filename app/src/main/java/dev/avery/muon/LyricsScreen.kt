package dev.avery.muon

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException

/** Material's large bar asks for this much height when expanded, before any font scaling. */
internal const val LYRICS_LARGE_HEADER = 152f

/** Below this much room for the words, a large header is taking space the song needs. */
internal const val LYRICS_MINIMUM_BODY = 200f

/**
 * How tall the expanded header may be, or `null` when the window cannot afford one.
 *
 * The large header grows with the font scale, and so does the space a useful stretch of lyrics
 * needs, so both sides of the comparison scale together. A tall portrait window affords the big
 * title; a short landscape or split-screen one, especially at large text sizes, does not, and
 * spending two thirds of it on a title the reader already knows would leave a few lines of song.
 *
 * The scale is used as the system reports it, with no ceiling: the text really is that big, and
 * reserving space for smaller text than is drawn would defeat the point of asking.
 */
internal fun lyricsHeaderHeight(windowHeight: Float, fontScale: Float): Float? {
    val scale = fontScale.coerceAtLeast(1f)
    val header = LYRICS_LARGE_HEADER * scale
    return header.takeIf { windowHeight - it >= LYRICS_MINIMUM_BODY * scale }
}

/**
 * Lyrics for the playing track.
 *
 * Keyed on the ID and queued song labels: Tauon can reuse a live ID while the queue still holds
 * an older item. Changed labels start fresh words, scroll and header state; identical duplicate
 * songs keep their place. Labels are a display revision, not proof of the server's audio identity.
 */
@Composable
internal fun LyricsScreen(item: MediaItem?, embedded: Boolean = false, position: () -> Long = { 0L },
    seek: (Long) -> Unit = {}, back: () -> Unit) {
    key(item?.mediaId, item?.mediaMetadata?.title?.toString(),
        item?.mediaMetadata?.artist?.toString(), item?.mediaMetadata?.albumTitle?.toString()) {
        Lyrics(item, embedded, position, seek, back)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Lyrics(item: MediaItem?, embedded: Boolean, position: () -> Long, seek: (Long) -> Unit, back: () -> Unit) {
    var lyrics by remember { mutableStateOf("Loading lyrics…") }
    var failure by remember { mutableStateOf(false) }
    // Whether there is real text to show; placeholders are set smaller and carry no footnote.
    var found by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    // Synced lines from LRCLIB, when the user has turned online lyrics on and it has some (#797).
    var synced by remember { mutableStateOf<List<LyricLine>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val prefs = rememberLyricsPreferences()
    val context = LocalContext.current
    LaunchedEffect(attempt, prefs.online) {
        failure = false; found = false; synced = null; loaded = false
        if (item == null) { lyrics = "Choose a track to see its lyrics."; return@LaunchedEffect }
        // A saved copy is not asked of Tauon by any track number: that number may now be another song (#213).
        if (isSavedHandle(item.mediaId)) { lyrics = "Lyrics aren't shown for saved copies."; return@LaunchedEffect }
        lyrics = "Loading lyrics…"
        // Online first when it is on (#797), Tauon's text when LRCLIB has nothing; a failed lookup is
        // not the user's problem while Tauon may still have words, so it falls through quietly.
        if (prefs.online) synced = try { onlineLyrics(context, item) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (synced != null) { loaded = true; return@LaunchedEffect }
        try {
            val id = item.mediaId.substringAfterLast('/').toLong()
            val endpoint = ServerEndpoint.parse(item.mediaId.substringBeforeLast('/'))
            val text = TauonApi(endpoint).lyrics(id)
            found = text.isNotBlank()
            lyrics = text.ifBlank { "No lyrics stored for this track in Tauon." }
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { lyrics = friendlyError(e); failure = true }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val colors = MaterialTheme.colorScheme
    val backButton: @Composable () -> Unit = {
        IconButton(onClick = back,
            modifier = Modifier.semantics { contentDescription = "Back to Now Playing" }) {
            MuonIcon("back")
        }
    }
    // The overlay hosting this screen already applied the system bar insets, so neither bar
    // below adds its own.
    // Scrolled, the bar takes Material's container colour, so lyrics pass under a surface instead of
    // vanishing behind the title's bare text (#121).
    val barColors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background,
        scrolledContainerColor = colors.surfaceContainer)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Embedded in Now Playing (its Lyrics view), the player's own header names the song.
        val header = if (embedded) null else lyricsHeaderHeight(maxHeight.value, LocalDensity.current.fontScale)
        Column(Modifier.fillMaxSize()
            .then(if (header != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)) {
            if (header != null) LargeTopAppBar(
                title = { SongTitle(item) },
                navigationIcon = backButton,
                colors = barColors,
                expandedHeight = header.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = scrollBehavior,
            ) else if (!embedded) TopAppBar(
                // Too short a window for a large title and a useful amount of song. The same title
                // and artist stay, in the compact form the large bar collapses to anyway, and every
                // pixel saved goes to the words.
                title = { SongTitle(item) },
                navigationIcon = backButton,
                colors = barColors,
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            val lines = synced
            if (lines != null) SyncedLines(lines, position, seek, top = if (embedded) PLAYER_VIEW_EDGE else 0.dp)
            // On the bar title's 16 dp keyline, so the words line up under the song's name.
            else Column(Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = if (embedded) PLAYER_VIEW_EDGE else 0.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // Where plain or missing lyrics are, the one place synced ones are offered (#797): no
                // dialog, and gone for good once closed or used.
                if (loaded && !prefs.online && !prefs.hintDismissed) SyncedLyricsHint(turnOn = { prefs.chooseOnline(true) }, close = prefs::dismissHint)
                if (failure) ErrorCard(lyrics, "Retry", modifier = Modifier) { attempt++ }
                // Left aligned and large enough to read at arm's length, and selectable so a line can
                // be copied out.
                else if (!found) Text(lyrics, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                else SelectionContainer {
                    Text(lyrics, style = MaterialTheme.typography.titleLarge,
                        lineHeight = MaterialTheme.typography.headlineMedium.lineHeight)
                }
                // Only about lyrics that are there.
                if (found) Text("Stored lyrics from Tauon · Not time-synchronised",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Synced lines for [item] from the cache, or else from LRCLIB, which it then caches; null when there are none. */
private suspend fun onlineLyrics(context: android.content.Context, item: MediaItem): List<LyricLine>? {
    val meta = item.mediaMetadata
    val title = meta.title?.toString().orEmpty()
    val artist = meta.artist?.toString().orEmpty()
    if (title.isBlank() || artist.isBlank()) return null
    val durationS = ((meta.durationMs ?: 0L) / 1000).toInt()
    val key = syncedLyricsKey(title, artist, durationS)
    val cache = SyncedLyricsCache(context)
    val lrc = withContext(Dispatchers.IO) { cache.read(key) }
        ?: Lrclib.synced(title, artist, meta.albumTitle?.toString().orEmpty(), durationS)
            ?.also { withContext(Dispatchers.IO) { cache.write(key, it) } }
    return lrc?.let(::parseLrc)
}

/** After a scroll by hand, the lines stop following the song for this long, so a reader is not pulled away. */
private const val FOLLOW_PAUSE_MS = 4_000L

/**
 * Lyrics in Focus (#797): the sung line sharp, lines blurring the further they are from it (with the
 * Blur switch on; Android 12+), the list keeping two lines above it, and any line a tap away from
 * playing from there.
 */
@Composable
private fun SyncedLines(lines: List<LyricLine>, position: () -> Long, seek: (Long) -> Unit, top: Dp) {
    val now by remember(lines) { derivedStateOf { currentLine(lines, position()) } }
    val list = rememberLazyListState()
    var heldUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) heldUntil = SystemClock.uptimeMillis() + FOLLOW_PAUSE_MS }
    }
    LaunchedEffect(now) {
        // Item 0 is the credit line, so a lyric's item is one past its index; two lines stay above it.
        if (now >= 0 && SystemClock.uptimeMillis() >= heldUntil) list.animateScrollToItem((now + 1 - 2).coerceAtLeast(0))
    }
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = top, bottom = 240.dp)) {
        item(key = "credit") {
            Text("Synced lyrics from LRCLIB", style = MaterialTheme.typography.labelMedium, color = colors.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
        itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
            val distance = abs(i - now)
            val blur = if (Expressive.blur && i != now && now >= 0) minOf(6f, (if (i < now) 1.2f else 0.4f) + distance * 1.1f) else 0f
            Text(line.text.ifBlank { "♪" }, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    i == now -> colors.onSurface
                    i < now -> colors.onSurfaceVariant.copy(alpha = 0.55f)
                    else -> colors.onSurfaceVariant
                },
                modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Play from here") { seek(line.atMs) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .blur(blur.dp, BlurredEdgeTreatment.Unbounded))
        }
    }
}

/** The one-line offer of synced lyrics under plain or missing ones (#797). */
@Composable
private fun SyncedLyricsHint(turnOn: () -> Unit, close: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = colors.secondaryContainer.copy(alpha = 0.9f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("These lyrics aren't synced", style = MaterialTheme.typography.titleSmall, color = colors.onSecondaryContainer)
                Text("Find synced lyrics online, from LRCLIB", style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
            }
            Button(onClick = turnOn) { Text("Turn on") }
            IconButton(onClick = close, modifier = Modifier.semantics { contentDescription = "Close" }) { MuonIcon("close") }
        }
    }
}

/**
 * The song, with its artist beneath it.
 *
 * The bar hands its expanded slot `headlineMedium` and its collapsed slot `titleLarge`; reading
 * the style it was given tells this which slot it is in without watching the scroll position.
 * A compact bar hands it `titleLarge` too, so the short-window header needs no separate case.
 */
@Composable
private fun SongTitle(item: MediaItem?) {
    val collapsed = LocalTextStyle.current.fontSize == MaterialTheme.typography.titleLarge.fontSize
    val title = item?.mediaMetadata?.title?.toString().orEmpty().ifBlank { "Lyrics" }
    val artist = item?.mediaMetadata?.artist?.toString().orEmpty()
    Column {
        Text(title, maxLines = if (collapsed) 1 else 2, overflow = TextOverflow.Ellipsis,
            style = if (collapsed) MaterialTheme.typography.barTitle
                else MaterialTheme.typography.screenTitle)
        if (artist.isNotBlank()) Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = if (collapsed) MaterialTheme.typography.labelLarge
                else MaterialTheme.typography.titleMedium)
    }
}
