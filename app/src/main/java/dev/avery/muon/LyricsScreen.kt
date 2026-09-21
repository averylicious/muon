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

/** Tauon answers with an empty body for a track it holds no lyrics for. */
internal fun storedLyrics(raw: String): String =
    raw.ifBlank { "No lyrics stored for this track in Tauon." }

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
            lyrics = storedLyrics(TauonApi(endpoint).lyrics(id))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { lyrics = friendlyError(e); failure = true }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    // The expanded title is 36sp above a subtitle, so its bar has to grow with the font scale.
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        LargeTopAppBar(
            title = { SongTitle(item) },
            navigationIcon = {
                IconButton(onClick = back,
                    modifier = Modifier.semantics { contentDescription = "Back to Now Playing" }) {
                    MuonIcon("back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background,
                scrolledContainerColor = colors.background),
            expandedHeight = TopAppBarDefaults.LargeAppBarExpandedHeight * fontScale,
            // The overlay hosting this screen already applied the system bar insets.
            windowInsets = WindowInsets(0, 0, 0, 0),
            scrollBehavior = scrollBehavior,
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

/**
 * The song, with its artist beneath it.
 *
 * The bar hands its expanded slot `headlineMedium` and its collapsed slot `titleLarge`; reading
 * the style it was given tells this which slot it is in without watching the scroll position.
 */
@Composable
private fun SongTitle(item: MediaItem?) {
    val collapsed = LocalTextStyle.current.fontSize == MaterialTheme.typography.titleLarge.fontSize
    val title = item?.mediaMetadata?.title?.toString().orEmpty().ifBlank { "Lyrics" }
    val artist = item?.mediaMetadata?.artist?.toString().orEmpty()
    Column {
        Text(title, maxLines = if (collapsed) 1 else 2, overflow = TextOverflow.Ellipsis,
            style = if (collapsed) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.displaySmall)
        if (artist.isNotBlank()) Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = if (collapsed) MaterialTheme.typography.labelLarge
                else MaterialTheme.typography.titleMedium)
    }
}
