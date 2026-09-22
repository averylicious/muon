package dev.avery.muon

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun MiniPlayer(p: PlaybackUi, position: () -> Long, ready: Boolean, active: Boolean, open: () -> Unit,
    toggle: () -> Unit, next: () -> Unit) {
    val scope = rememberCoroutineScope()
    // A new eligibility window gets its own lift: cleanup from an outgoing detector must
    // never move the next presentation of this mini player.
    val lift = remember(active) { Animatable(0f) }
    val canOpen by rememberUpdatedState(active)
    // A drag hands the player over when it ends, which can be a long time after it began, so the
    // action is read then rather than captured when the gesture detector was set up.
    val current by rememberUpdatedState(open)
    // Attached to the navigation bar rather than floating above it: it was a card wedged against
    // the bottom chrome, so it now shares an edge with it and only rounds its top corners.
    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
            // Drawn from the animation, so following a finger never recomposes the mini player.
            .graphicsLayer { translationY = lift.value }
            // Tap still opens, with its own label, and the controls inside still take their own
            // taps: a drag only becomes a drag once it has passed the touch slop that the detector
            // applies before it reports anything.
            .clickable(enabled = active, onClickLabel = "Open Now Playing", onClick = open)
            .pointerInput(active, lift) {
                if (!active) return@pointerInput
                var drag = 0f
                var settle: Job? = null
                fun restore() {
                    settle?.cancel()
                    settle = scope.launch { lift.animateTo(0f, motionShort()) }
                }
                try {
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f; settle?.cancel() },
                        onVerticalDrag = { _, amount ->
                            drag += amount
                            val offset = miniDragOffset(drag, MINI_DRAG_LIFT.toPx())
                            // Cancel even if the prior snap is only queued, and capture this
                            // event's offset instead of reading a later accumulated distance.
                            settle?.cancel()
                            settle = scope.launch { lift.snapTo(offset) }
                        },
                        onDragCancel = { restore() },
                        onDragEnd = {
                            val opens = miniDragOpens(drag, MINI_DRAG_OPEN.toPx(), canOpen)
                            restore()
                            if (opens) current()
                        })
                } finally {
                    // Pointer-input cancellation cannot run its own animation. The composition
                    // scope survives detector restart; disposal cancels it automatically.
                    restore()
                }
            }) {
        Column {
            LinearProgressIndicator(progress = { progressFraction(position(), p.duration) },
                modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(p.item?.mediaMetadata?.artworkUri?.toString(), Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(p.item?.mediaMetadata?.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (p.error != null) "Playback interrupted · tap to retry" else if (p.buffering) "Buffering…" else p.item?.mediaMetadata?.artist?.toString().orEmpty(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The primary action is filled so it reads as the control rather than as decoration.
                FilledTonalIconButton(onClick = toggle, enabled = ready,
                    modifier = Modifier.semantics { contentDescription = if (p.playing) "Pause" else "Play" }) {
                    Crossfade(p.playing, animationSpec = motionShort(), label = "mini play/pause") { playing ->
                        MuonIcon(if (playing) "pause" else "play", Modifier.size(20.dp))
                    }
                }
                Control("next", "Next track", p.next && ready, next)
            }
        }
    }
}
