package dev.avery.muon

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
 * Keyed on the track, so a song changing underneath — from the queue moving on, or from a swipe —
 * starts its own words at the top with the title expanded, rather than inheriting a scroll
 * position that meant something about different words.
 */
@Composable
internal fun LyricsScreen(item: MediaItem?, back: () -> Unit) {
    key(item?.mediaId) { Lyrics(item, back) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Lyrics(item: MediaItem?, back: () -> Unit) {
    var lyrics by remember { mutableStateOf("Loading lyrics…") }
    var failure by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
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
    val barColors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background,
        scrolledContainerColor = colors.background)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val header = lyricsHeaderHeight(maxHeight.value, LocalDensity.current.fontScale)
        Column(Modifier.fillMaxSize()
            .then(if (header != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)) {
            if (header != null) LargeTopAppBar(
                title = { SongTitle(item) },
                navigationIcon = backButton,
                colors = barColors,
                expandedHeight = header.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = scrollBehavior,
            ) else TopAppBar(
                // Too short a window for a large title and a useful amount of song. The same title
                // and artist stay, in the compact form the large bar collapses to anyway, and every
                // pixel saved goes to the words.
                title = { SongTitle(item) },
                navigationIcon = backButton,
                colors = barColors,
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            Column(Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (failure) ErrorCard(lyrics, "Retry") { attempt++ }
                // Left aligned and large enough to read at arm's length, and selectable so a line can
                // be copied out.
                else SelectionContainer {
                    Text(lyrics, style = MaterialTheme.typography.titleLarge,
                        lineHeight = MaterialTheme.typography.headlineMedium.lineHeight)
                }
                Text("Stored lyrics from Tauon · Not time-synchronised",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
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
