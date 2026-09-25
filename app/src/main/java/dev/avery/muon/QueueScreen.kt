package dev.avery.muon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

/** One song in the queue, at its position in the player's list, with a key that outlives that position. */
private class QueueEntry(val index: Int, val item: MediaItem, val key: String = "")

/** What the queue holds right now: the playing song and those after it, in playing order. */
private class QueueSnapshot(val current: QueueEntry?, val upNext: List<QueueEntry>, val shuffle: Boolean = false)

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
    val items = order.map { player.getMediaItemAt(it) }
    val keys = occurrenceKeys(items.map { it.mediaId })
    return QueueSnapshot(QueueEntry(current, player.getMediaItemAt(current)),
        order.indices.map { QueueEntry(order[it], items[it], keys[it]) }, shuffle)
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
    // The first [QUEUE_WINDOW] songs, unless asked for all; a removal pulls the next one into view.
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (showAll) snapshot.upNext else snapshot.upNext.take(QUEUE_WINDOW)
    // Reordering follows the list, which is the playing order only with shuffle off (#47).
    val reorderable = editable && !snapshot.shuffle
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // A drag in progress: which row, how far it is from its slot, and the order it has made so far.
    // The player is told once, on release; until then only this local order moves.
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var working by remember { mutableStateOf<List<QueueEntry>?>(null) }
    // A new queue from the player ends any drag that began on the old one.
    LaunchedEffect(snapshot) { if (dragKey == null) working = null }
    val rows = working ?: shown

    /** Swaps the held row past a neighbour once its centre has crossed the neighbour's centre. */
    fun settleSwaps() {
        val key = dragKey ?: return
        val order = working ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val held = visible.firstOrNull { it.key == "next:$key" } ?: return
        val at = order.indexOfFirst { it.key == key }
        val centre = held.offset + dragOffset + held.size / 2f
        val below = order.getOrNull(at + 1)?.let { n -> visible.firstOrNull { it.key == "next:${n.key}" } }
        val above = order.getOrNull(at - 1)?.let { n -> visible.firstOrNull { it.key == "next:${n.key}" } }
        if (dragOffset > 0 && below != null && centre > below.offset + below.size / 2f) {
            working = order.moved(at, at + 1); dragOffset -= below.size
        } else if (dragOffset < 0 && above != null && centre < above.offset + above.size / 2f) {
            working = order.moved(at, at - 1); dragOffset += above.size
        }
    }

    /** Commits a finished drag as one player move, if the queue still matches what was dragged. */
    fun drop() {
        val key = dragKey
        val order = working
        dragKey = null; dragOffset = 0f
        if (key == null || order == null) { working = null; return }
        val from = shown.indexOfFirst { it.key == key }
        val to = order.indexOfFirst { it.key == key }
        val move = queueMove(shown.map { it.index }, from, to)
        val p = player
        if (move != null && p != null && move.first < p.mediaItemCount &&
            p.getMediaItemAt(move.first).mediaId == shown[from].item.mediaId && !p.shuffleModeEnabled) {
            p.moveMediaItem(move.first, move.second)
        }
        working = null
    }

    /** One step up or down, for TalkBack, where dragging is not available. */
    fun step(entry: QueueEntry, by: Int) {
        val p = player ?: return
        val to = entry.index + by
        if (!reorderable || to !in (snapshot.current?.index ?: -1) + 1 until p.mediaItemCount) return
        if (p.getMediaItemAt(entry.index).mediaId == entry.item.mediaId) p.moveMediaItem(entry.index, to)
    }

    // While a row is held near the top or bottom, scroll so it can travel past what is on screen.
    LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        val max = with(density) { 14.dp.toPx() }
        while (dragKey != null) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val held = info.visibleItemsInfo.firstOrNull { it.key == "next:$dragKey" } ?: continue
            val top = held.offset + dragOffset
            val speed = edgeScroll(top, top + held.size, info.viewportStartOffset.toFloat(),
                info.viewportEndOffset.toFloat(), edge, max)
            if (speed != 0f) {
                val moved = listState.scrollBy(speed)
                // The list moved under the finger; keep the row where the finger is.
                dragOffset += moved
                settleSwaps()
            }
        }
    }

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
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
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
                if (editable && snapshot.shuffle && snapshot.upNext.size > 1) Text("Turn off shuffle to reorder",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp))
            }
            if (snapshot.upNext.isEmpty()) item(key = "empty", contentType = "empty") {
                Text("Nothing after this song.", color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
            }
            items(rows, key = { "next:${it.key}" }, contentType = { "next" }) { entry ->
                val state = rememberSwipeToDismissBoxState()
                val held = entry.key == dragKey
                SwipeToDismissBox(
                    state = state,
                    // The held row floats above the others and follows the finger; the rest slide
                    // aside. No swiping while any row is being dragged.
                    modifier = if (held) Modifier.zIndex(1f).graphicsLayer {
                        translationY = dragOffset; shadowElevation = 8.dp.toPx()
                    } else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    gesturesEnabled = editable && dragKey == null,
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
                    val position = rows.indexOf(entry)
                    // The drag detector outlives this composition (it is keyed on the row), so it reaches
                    // the current list and callbacks through these rather than the ones it began with.
                    val startDrag by rememberUpdatedState { dragKey = entry.key; dragOffset = 0f; working = rows }
                    val dragBy by rememberUpdatedState { dy: Float -> dragOffset += dy; settleSwaps() }
                    val endDrag by rememberUpdatedState { drop() }
                    QueueRow(entry.item, playing = false,
                        onClick = { player?.seekToDefaultPosition(entry.index); player?.play() },
                        // Swiping and dragging mean nothing to a screen reader; both stay one action away.
                        remove = if (editable) ({ remove(entry) }) else null,
                        moveUp = if (reorderable && position > 0) ({ step(entry, -1) }) else null,
                        moveDown = if (reorderable && position < snapshot.upNext.size - 1) ({ step(entry, 1) }) else null,
                        handle = if (!reorderable) null else Modifier.pointerInput(entry.key) {
                            detectVerticalDragGestures(
                                onDragStart = { startDrag() },
                                onDragEnd = { endDrag() },
                                // A cancelled drag changes nothing: the rows go back where the player has them.
                                onDragCancel = { dragKey = null; dragOffset = 0f; working = null },
                            ) { change, dy ->
                                change.consume()
                                dragBy(dy)
                            }
                        })
                }
            }
            val hidden = snapshot.upNext.size - shown.size
            if (hidden > 0) item(key = "show-all", contentType = "more") {
                TextButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("Show all ${snapshot.upNext.size} songs")
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
private fun QueueRow(item: MediaItem, playing: Boolean, onClick: (() -> Unit)?, remove: (() -> Unit)? = null,
    moveUp: (() -> Unit)? = null, moveDown: (() -> Unit)? = null, handle: Modifier? = null) {
    val colors = MaterialTheme.colorScheme
    val meta = item.mediaMetadata
    Row(Modifier.fillMaxWidth().background(colors.background).heightIn(min = 64.dp)
        .then(if (onClick != null) Modifier.clickable(onClickLabel = "Play", onClick = onClick) else Modifier)
        .padding(horizontal = 24.dp, vertical = 8.dp)
        .semantics {
            if (playing) stateDescription = "Now playing"
            customActions = listOfNotNull(
                moveUp?.let { CustomAccessibilityAction("Move up") { it(); true } },
                moveDown?.let { CustomAccessibilityAction("Move down") { it(); true } },
                remove?.let { CustomAccessibilityAction("Remove from queue") { it(); true } },
            )
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
        // Only the handle starts a drag, so scrolling and swiping the row keep working as before.
        if (handle != null) Box(handle.padding(start = 4.dp).size(40.dp), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { MuonIcon("drag-handle") }
        }
    }
}
