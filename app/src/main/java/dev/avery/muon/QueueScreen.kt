package dev.avery.muon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.launch

/** One song in the queue, at its position in the player's list. */
private class QueueEntry(val index: Int, val item: MediaItem)

/** What the queue holds right now: the playing song and those after it, in playing order. */
private class QueueSnapshot(val current: QueueEntry?, val upNext: List<QueueEntry>)

private fun queueSnapshot(player: Player): QueueSnapshot {
    val count = player.mediaItemCount
    val current = player.currentMediaItemIndex
    if (count == 0 || current !in 0 until count) return QueueSnapshot(null, emptyList())
    val timeline = player.currentTimeline
    val shuffle = player.shuffleModeEnabled
    // Repeat is left out on purpose: *Next up* is what follows, once, not the loop back round.
    val order = upNextOrder(current, count) { i ->
        if (i >= timeline.windowCount) C.INDEX_UNSET else timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
    }
    return QueueSnapshot(QueueEntry(current, player.getMediaItemAt(current)),
        order.map { QueueEntry(it, player.getMediaItemAt(it)) })
}

/**
 * The queue (#47): *Now playing*, then *Next up* in the order the player will play it, following
 * shuffle. Tap a song to play it; swipe it away to remove it, with Undo. Reordering by drag is a
 * later slice.
 *
 * Read from the controller whenever [revision] moves, which happens on every queue, order and mode
 * change, so the list never shows a queue the player no longer has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueScreen(player: MediaController?, revision: () -> Int, back: () -> Unit) {
    val rev = revision()
    val snapshot = remember(player, rev) { player?.let(::queueSnapshot) ?: QueueSnapshot(null, emptyList()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val editable = player?.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) == true

    /** Removes the song if its position still holds it, and says whether it did. */
    fun remove(entry: QueueEntry): Boolean {
        val p = player ?: return false
        // The list may be a frame behind the player: act only if that position still holds that song.
        if (entry.index !in 0 until p.mediaItemCount || p.getMediaItemAt(entry.index).mediaId != entry.item.mediaId) return false
        p.removeMediaItem(entry.index)
        val restore = restoreUrl(entry.item.mediaId)?.let { entry.item.buildUpon().setUri(it).build() }
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Removed “${entry.item.mediaMetadata.title ?: "song"}”",
                actionLabel = if (restore != null) "Undo" else null, duration = SnackbarDuration.Short)
            // Back where it was in the list. With shuffle on, the player chooses where it falls in
            // the shuffled order, as it does for any song added to a shuffled queue.
            if (result == SnackbarResult.ActionPerformed && restore != null)
                p.addMediaItem(entry.index.coerceAtMost(p.mediaItemCount), restore)
        }
        return true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { QueueTitle() },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.semantics { contentDescription = "Back to Now Playing" }) {
                        MuonIcon("back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background,
                    scrolledContainerColor = colors.surfaceContainer),
                // The overlay hosting this screen already applied the system bar insets.
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "now-label", contentType = "label") { SectionLabel("Now playing") }
            snapshot.current?.let { current ->
                item(key = "now:${current.index}:${current.item.mediaId}", contentType = "now") {
                    QueueRow(current.item, playing = true, onClick = null)
                }
            }
            item(key = "next-label", contentType = "label") {
                val length = remember(snapshot) { queueLength(snapshot.upNext.map { it.item.mediaMetadata.durationMs }) }
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.Bottom) {
                    Text("Next up", style = MaterialTheme.typography.titleSmall, color = colors.primary,
                        modifier = Modifier.weight(1f))
                    if (snapshot.upNext.isNotEmpty()) Text(queueSummary(snapshot.upNext.size, length),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (snapshot.upNext.isEmpty()) item(key = "empty", contentType = "empty") {
                Text("Nothing after this song.", color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
            }
            items(snapshot.upNext, key = { "next:${it.index}:${it.item.mediaId}" }, contentType = { "next" }) { entry ->
                val state = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(
                    state = state,
                    gesturesEnabled = editable,
                    // A refused removal (the queue changed under the finger) puts the row back.
                    onDismiss = { if (!remove(entry)) scope.launch { state.reset() } },
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(colors.errorContainer).padding(horizontal = 24.dp),
                            contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd)
                                Alignment.CenterStart else Alignment.CenterEnd) {
                            CompositionLocalProvider(LocalContentColor provides colors.onErrorContainer) {
                                MuonIcon("delete")
                            }
                        }
                    },
                ) {
                    QueueRow(entry.item, playing = false,
                        onClick = { player?.seekToDefaultPosition(entry.index); player?.play() },
                        // Swiping means nothing to a screen reader; removing stays one action away.
                        remove = if (editable) ({ remove(entry) }) else null)
                }
            }
        }
    }
}

/** "Queue", large when expanded and in the bar's size once collapsed, as the other screens do. */
@Composable
private fun QueueTitle() {
    val collapsed = LocalTextStyle.current.fontSize == MaterialTheme.typography.titleLarge.fontSize
    Text("Queue", maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = if (collapsed) MaterialTheme.typography.barTitle else MaterialTheme.typography.screenTitle)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp))
}

@Composable
private fun QueueRow(item: MediaItem, playing: Boolean, onClick: (() -> Unit)?, remove: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val meta = item.mediaMetadata
    Row(Modifier.fillMaxWidth().background(colors.background).heightIn(min = 64.dp)
        .then(if (onClick != null) Modifier.clickable(onClickLabel = "Play", onClick = onClick) else Modifier)
        .padding(horizontal = 24.dp, vertical = 8.dp)
        .semantics {
            if (playing) stateDescription = "Now playing"
            if (remove != null) customActions = listOf(CustomAccessibilityAction("Remove from queue") { remove(); true })
        },
        verticalAlignment = Alignment.CenterVertically) {
        Artwork(meta.artworkUri?.toString(), Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(meta.title?.toString().orEmpty().ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) colors.primary else colors.onSurface,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium)
            val artist = meta.artist?.toString().orEmpty()
            if (artist.isNotBlank()) Text(artist, style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Tauon's reported length (#62), for display only; unknown lengths are left blank.
        meta.durationMs?.takeIf { it > 0 }?.let {
            Text(formatTime(it), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 44.dp))
        }
    }
}
