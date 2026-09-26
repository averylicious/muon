package dev.avery.muon

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun TrackList(tracks: List<TauonTrack>, endpoint: ServerEndpoint?, currentId: String?, ready: Boolean,
    playing: Boolean, emptyText: String, loading: Boolean = false, state: LazyListState = rememberLazyListState(),
    sections: ((TauonTrack) -> String)? = null, header: (LazyListScope.() -> Unit)? = null,
    actions: ((TauonTrack) -> Unit)? = null, play: (TauonTrack) -> Unit) {
    if (tracks.isEmpty() && loading) PlaceholderRows()
    // A list of one full-height item rather than a plain box: an empty library is exactly when a
    // refresh is wanted, and a pull gesture needs something scrollable to pull.
    else if (tracks.isEmpty()) LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillParentMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        val keys = remember(tracks) { trackKeys(tracks) }
        // Only the real list takes [state]: a moment of loading or emptiness must not clamp a
        // position the caller is keeping.
        // With [sections], the list is alphabetical and its thumb can be grabbed to jump through it.
        val indicator = rememberScrollIndicator(state)
        val grabbable = sections != null
        Box {
            LazyColumn(Modifier.scrollIndicator(indicator, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                width = if (grabbable) SCROLLER_WIDTH else INDICATOR_WIDTH,
                minLength = if (grabbable) SCROLLER_MIN_LENGTH else INDICATOR_MIN_LENGTH),
                state = state, contentPadding = PaddingValues(bottom = 12.dp)) {
                header?.invoke(this)
                itemsIndexed(tracks, key = { i, _ -> keys[i] }, contentType = { _, _ -> "track" }) { _, t ->
                    TrackRow(t, endpoint, currentId == "${endpoint?.origin}/${t.id}", playing, ready,
                        Modifier.animateItem(placementSpec = motionMedium()), actions = actions?.let { { it(t) } }) { play(t) }
                }
            }
            if (sections != null) AlphabetScroller(indicator, tracks.size) { i ->
                tracks.getOrNull(i)?.let(sections).orEmpty()
            }
        }
    }
}

/**
 * One song: cover, title, a line under it, and length. The current song sits on a tonal surface with
 * the equalizer over its cover. [subtitle] defaults to the credits and album.
 */
@Composable
internal fun TrackRow(t: TauonTrack, endpoint: ServerEndpoint?, current: Boolean, playing: Boolean, ready: Boolean,
    modifier: Modifier = Modifier, subtitle: String = trackSubtitle(t.artist, t.album, t.playable),
    actions: (() -> Unit)? = null, play: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp)
        .padding(horizontal = 12.dp)
        // The current track sits on its own tonal surface, with the equalizer over its cover, so it
        // is marked by something appearing and moving rather than only by a change of hue.
        .clip(RoundedCornerShape(16.dp))
        .then(if (current) Modifier.background(colors.secondaryContainer) else Modifier)
        // A long press opens the song's actions (#46).
        .combinedClickable(enabled = t.playable && ready, onLongClickLabel = if (actions != null) "Song actions" else null,
            onLongClick = actions, onClick = play)
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .then(if (current) Modifier.semantics { stateDescription = "Now playing" } else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Artwork(endpoint?.url("/api1/pic/small/${t.id}"), Modifier.fillMaxSize())
            if (current) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                NowPlayingBars(playing, color = Color.White)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(t.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) colors.onSecondaryContainer else colors.onSurface,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium)
            // An untagged file has nothing to say here, so the line is left out rather than
            // printed as a stray separator.
            if (subtitle.isNotEmpty()) Text(subtitle,
                color = if (current) colors.onSecondaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        // A minimum width keeps the durations on one right edge; a long duration or a large font
        // scale grows the column instead of clipping, taking the space from the title beside it.
        Text(formatTime(t.durationMs), style = MaterialTheme.typography.labelSmall,
            color = if (current) colors.onSecondaryContainer else colors.onSurfaceVariant, textAlign = TextAlign.End,
            maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 44.dp))
        DownloadBadge(downloadMark(endpoint, t))
    }
}

/**
 * Shown while the first library load runs. Without it the screen reads as an empty library until
 * every playlist has been fetched, which is the wrong message while it is still working.
 */
@Composable
private fun PlaceholderRows() {
    val colour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Column(Modifier.fillMaxWidth()) {
        repeat(8) { index ->
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(colour))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    // Uneven widths so it reads as a list of titles rather than as a broken grid.
                    Box(Modifier.fillMaxWidth(if (index % 3 == 0) 0.7f else 0.5f).height(12.dp)
                        .clip(RoundedCornerShape(6.dp)).background(colour))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.35f else 0.45f).height(10.dp)
                        .clip(RoundedCornerShape(5.dp)).background(colour))
                }
            }
        }
    }
}

/**
 * The line under a track's title.
 *
 * Only what the file actually carries: a tag that is missing or blank is left out, and so is the
 * separator that would have introduced it, rather than showing a dot with nothing either side of
 * it. Nothing is invented to fill the gap — a file with no tags simply has no second line.
 *
 * A track the server will not stream says so first, and keeps its artist for recognition; its
 * album is left out, as it always has been, because the reason it cannot play is the more useful
 * half of a single line.
 */
internal fun trackSubtitle(artist: String, album: String, playable: Boolean): String =
    listOfNotNull(
        if (playable) null else "Unavailable for direct streaming",
        displayCredits(artist).ifBlank { null },
        if (playable) album.trim().ifBlank { null } else null,
    ).joinToString(" · ")

internal fun formatTime(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
