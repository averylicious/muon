package dev.avery.muon

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalDragOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun MiniPlayer(p: PlaybackUi, position: () -> Long, ready: Boolean, active: Boolean,
    sheet: PlayerSheet?, open: () -> Unit, toggle: () -> Unit, next: () -> Unit) {
    val canOpen by rememberUpdatedState(active)
    // A drag hands the player over when it ends, which can be a long time after it began, so the
    // action is read then rather than captured when the gesture detector was set up.
    val current by rememberUpdatedState(open)
    // Attached to the navigation bar rather than floating above it: it was a card wedged against
    // the bottom chrome, so it now shares an edge with it and only rounds its top corners.
    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
            // Tap still opens, with its own label, and the controls inside still take their own
            // taps: a drag only becomes a drag once it has passed the touch slop that the detector
            // applies before it reports anything.
            .clickable(enabled = active, onClickLabel = "Open Now Playing", onClick = open)
            // The mini player itself stays put: the whole player rises under the finger instead,
            // through the sheet that owns its only vertical position. The keys do not change
            // during a preview — the player is still logically closed — so the stream that began
            // the drag is never torn down while it is carrying the player.
            .pointerInput(active, sheet) {
                if (!active || sheet == null) return@pointerInput
                var travel = 0f
                var baseline = 1f
                var startedAt = -1
                // The gesture's touch-down, slop crossing, later moves and lift, so a brief flick is
                // measured on what the platform delivers rather than read as motionless.
                val trace = FlickTrace()
                fun owned() = playerPreviewOwned(sheet.previewing, startedAt, sheet.generation)
                // False if the change belongs to another finger: the helpers hand a gesture over when
                // the first finger lifts, and another finger's position is not this one's travel.
                fun record(change: PointerInputChange): Boolean {
                    val finger = change.id.value
                    change.historical.forEach { if (!trace.sample(finger, it.uptimeMillis, it.position.y)) return false }
                    return trace.sample(finger, change.uptimeMillis, change.position.y)
                }
                fun follow(amount: Float) {
                    travel += amount
                    if (startedAt < 0 && playerPreviewMayBegin(travel)) {
                        // Wherever the sheet already is — usually closed, but perhaps still settling
                        // away from a moment ago — is where the finger takes it from.
                        baseline = sheet.position.value
                        startedAt = sheet.beginPreview()
                    }
                    // Refused once Back or a new presentation has ended this preview, even though
                    // the finger is still down.
                    if (owned()) sheet.moveTo(playerSheetDragged(baseline, travel, sheet.height))
                }
                fun release(upMillis: Long) {
                    // Judged on the whole gesture and the lift's own event time. Only the release
                    // uses it: the sheet still follows the post-slop travel above.
                    val velocity = trace.releaseVelocity(upMillis)
                    val opens = playerPreviewOpens(sheet.previewing, startedAt, sheet.generation,
                        trace.travel, sheetReleaseDistance(sheet.height, MINI_DRAG_OPEN.toPx()), canOpen,
                        velocity, SheetFlick(SHEET_FLICK_VELOCITY.toPx(), SHEET_FLICK_TRAVEL.toPx()))
                    val mine = owned()
                    // Sets off at the same measured speed towards where the release decided; the
                    // presentation keeps that settle, or replaces it if the open is refused.
                    if (mine) sheet.settleTo(if (opens) 0f else 1f, sheetFractionVelocity(velocity, sheet.height))
                    // Opening and ending the preview together lets the presentation carry the sheet
                    // on up from here; any other ending lets it put the sheet away.
                    if (opens) current()
                    if (mine) sheet.endPreview()
                }
                try {
                    // The same structure as detectVerticalDragGestures, which hides the touch-down,
                    // the batched points and the lift event this needs.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        trace.down(down.id.value, down.uptimeMillis, down.position.y)
                        var overSlop = 0f
                        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            overSlop = over
                        } ?: return@awaitEachGesture
                        // Slop crossed, but in either direction: nothing begins until the travel is
                        // upwards, so a drag down from rest neither dims the library nor hides it.
                        travel = 0f
                        startedAt = -1
                        // The finger that touched down lifted before the slop, and another one
                        // crossed it: not this gesture. Nothing has begun yet, so nothing to undo.
                        if (!record(drag)) return@awaitEachGesture
                        follow(overSlop)
                        while (true) {
                            val change = awaitVerticalDragOrCancellation(drag.id)
                            // Cancelled or consumed elsewhere, or handed over to another finger:
                            // end the preview this gesture owns, and commit nothing.
                            if (change == null || !record(change)) {
                                if (owned()) sheet.endPreview()
                                break
                            }
                            if (change.changedToUpIgnoreConsumed()) {
                                release(change.uptimeMillis)
                                break
                            }
                            follow(change.positionChange().y)
                            change.consume()
                        }
                    }
                } finally {
                    // Torn down mid-preview — the controller or queue went, or the mini player
                    // left: end the preview this detector still owns, and the presentation puts
                    // the sheet away rather than leaving it part-way.
                    if (owned()) sheet.endPreview()
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
                // The primary action is filled in the primary colour, like Now Playing's, so it reads as
                // the control rather than blending into the mini player's own tonal surface (#123).
                FilledIconButton(onClick = toggle, enabled = ready,
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
