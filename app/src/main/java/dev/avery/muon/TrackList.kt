package dev.avery.muon

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
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
    else if (tracks.isEmpty()) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalUnderBars.current)) {
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
        // Under glass bars the list runs on behind them; its indicator and letter scroller stop above.
        val under = LocalUnderBars.current
        Box {
            LazyColumn(Modifier.scrollIndicator(indicator, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                width = if (grabbable) SCROLLER_WIDTH else INDICATOR_WIDTH,
                minLength = if (grabbable) SCROLLER_MIN_LENGTH else INDICATOR_MIN_LENGTH, bottomInset = under),
                state = state, contentPadding = PaddingValues(bottom = 12.dp + under)) {
                header?.invoke(this)
                itemsIndexed(tracks, key = { i, _ -> keys[i] }, contentType = { _, _ -> "track" }) { i, t ->
                    TrackRow(t, endpoint, currentId == "${endpoint?.origin}/${t.id}", playing, ready,
                        Modifier.animateItem(placementSpec = motionSpatial()), actions = actions?.let { { it(t) } },
                        place = groupPlace(i, tracks.size)) { play(t) }
                }
            }
            if (sections != null) Box(Modifier.matchParentSize().padding(bottom = under)) {
                AlphabetScroller(indicator, tracks.size) { i ->
                    tracks.getOrNull(i)?.let(sections).orEmpty()
                }
            }
        }
    }
}

/**
 * One song: cover, title, a line under it, and length. The current song sits on a tonal surface with
 * the equalizer over its cover. [subtitle] defaults to the credits and album. With Expressive motion
 * and a [place], the song is a row of its list's joined group (the Refined mockup), like On this device.
 */
@Composable
internal fun TrackRow(t: TauonTrack, endpoint: ServerEndpoint?, current: Boolean, playing: Boolean, ready: Boolean,
    modifier: Modifier = Modifier, subtitle: String = trackSubtitle(t.artist, t.album, t.playable),
    actions: (() -> Unit)? = null, place: GroupPlace? = null, play: () -> Unit) {
    if (Expressive.motion) {
        val group = place?.let { groupShape(it, current) }
        // The item's own modifier (its placement animation) goes on the outermost layout, the swipe's,
        // and so does the gap above a joined row, so the swipe's action fills only the row itself.
        val outer = if (place == null || place.startsGroup) modifier else modifier.padding(top = GROUP_GAP)
        SwipeToQueue(t, t.playable && ready, outer, shape = group ?: RoundedCornerShape(16.dp),
            inset = if (group != null) GROUP_INSET else 12.dp) {
            ExpressiveTrackRow(t, endpoint, current, playing, ready, Modifier, subtitle, actions, group, play)
        }
        return
    }
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
        .padding(start = 12.dp, end = if (actions != null) 0.dp else 12.dp, top = 8.dp, bottom = 8.dp)
        .then(if (current) Modifier.semantics { stateDescription = "Now playing" } else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        TrackCover(t, endpoint, current, playing)
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
        // No saved-copy badge on a live song (#213): a copy kept under its number may be another song.
        actions?.let { SongMenuButton(t.title, t.artist, if (current) colors.onSecondaryContainer else colors.onSurfaceVariant, it) }
    }
}

/**
 * The ⋮ at the end of a song row, in every list that has a song panel: it opens the same panel as a
 * long press, so the panel is one tap away as well as one hold (the user's request, 2026-10-23).
 */
@Composable
internal fun SongMenuButton(title: String, artist: String, tint: Color, open: () -> Unit) {
    val who = displayCredits(artist)
    IconButton(onClick = open, colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = "More options for ${title.ifBlank { "Untitled" }}" + if (who.isNotBlank()) ", $who" else ""
        }) { MuonIcon("more") }
}

/** A song's cover, with the equalizer over it while it is the current song. */
@Composable
private fun TrackCover(t: TauonTrack, endpoint: ServerEndpoint?, current: Boolean, playing: Boolean) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Artwork(endpoint?.url("/api1/pic/medium/${t.id}"), Modifier.fillMaxSize())
        if (current) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            NowPlayingBars(playing, color = Color.White)
        }
    }
}

/**
 * [TrackRow] as Material 3 Expressive's list item (the Canary experiment): the same cover, lines,
 * length and download mark, and the current song on the same tonal surface, but the row's corners
 * morph while it is pressed. It uses the plain click overload, which adds no semantics of its own,
 * so a song is still announced as a song ("Now playing" on the current one), not as a radio button.
 * A row that cannot play keeps its colours, as the hand-built row did.
 */
@Composable
private fun ExpressiveTrackRow(t: TauonTrack, endpoint: ServerEndpoint?, current: Boolean, playing: Boolean,
    ready: Boolean, modifier: Modifier, subtitle: String, actions: (() -> Unit)?, group: Shape?, play: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // A joined row has its own tonal ground, as On this device's rows do; a lone row stays clear.
    val rest = if (group != null) colors.surfaceContainerLow else Color.Transparent
    val ground by animateColorAsState(if (current) colors.secondaryContainer else rest, motionMedium(), label = "row ground")
    val ink = if (current) colors.onSecondaryContainer else colors.onSurface
    val quiet = if (current) colors.onSecondaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant
    val rowModifier = modifier.fillMaxWidth().padding(horizontal = if (group != null) GROUP_INSET else 12.dp)
        .then(if (current) Modifier.semantics { stateDescription = "Now playing" } else Modifier)
    val padding = PaddingValues(start = 12.dp, end = if (actions != null) 0.dp else 12.dp, top = 8.dp, bottom = 8.dp)
    val leading: @Composable () -> Unit = { TrackCover(t, endpoint, current, playing) }
    // An untagged file has nothing to say here, so the line is left out. A joined row that is the
    // current song adds "Playing" or "Paused" under it, in words as well as the cover's bars (the
    // Refined mockup), changing in place.
    val status = group != null && current
    val supporting: (@Composable () -> Unit)? = if (subtitle.isEmpty() && !status) null else {
        {
            Column {
                if (subtitle.isNotEmpty()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                if (status) AnimatedContent(playing, transitionSpec = { fadeIn(motionMedium()) togetherWith fadeOut(motionShort()) },
                    label = "playing state") { on ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        MuonIcon(if (on) "pause" else "play", Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (on) "Playing" else "Paused", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
    val trailing: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(t.durationMs), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End,
                maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 44.dp))
            actions?.let { SongMenuButton(t.title, t.artist, quiet, it) }
        }
    }
    val title: @Composable () -> Unit = {
        Text(t.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium)
    }
    // A long press opens the song's actions (#46).
    val longLabel = if (actions != null) "Song actions" else null
    if (group != null) SegmentedListItem(onClick = play, shapes = ListItemDefaults.shapes(shape = group), modifier = rowModifier,
        enabled = t.playable && ready, onLongClick = actions, onLongClickLabel = longLabel,
        colors = ListItemDefaults.segmentedColors(containerColor = ground, contentColor = ink,
            supportingContentColor = quiet, trailingContentColor = quiet,
            disabledContainerColor = ground, disabledContentColor = ink,
            disabledSupportingContentColor = quiet, disabledTrailingContentColor = quiet,
            disabledLeadingContentColor = ink),
        contentPadding = padding, leadingContent = leading, supportingContent = supporting, trailingContent = trailing,
        content = title)
    else ListItem(onClick = play, enabled = t.playable && ready, onLongClick = actions, onLongClickLabel = longLabel,
        modifier = rowModifier,
        shapes = ListItemDefaults.shapes(shape = RoundedCornerShape(16.dp)),
        colors = ListItemDefaults.colors(containerColor = ground, contentColor = ink,
            supportingContentColor = quiet, trailingContentColor = quiet,
            disabledContainerColor = ground, disabledContentColor = ink,
            disabledSupportingContentColor = quiet, disabledTrailingContentColor = quiet,
            disabledLeadingContentColor = ink),
        contentPadding = padding, leadingContent = leading, supportingContent = supporting, trailingContent = trailing,
        content = title)
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
