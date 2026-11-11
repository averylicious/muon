package dev.avery.muon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.activity.BackEventCompat
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * Predictive Back for Library pages (issue #658), following Android's predictive back design guidance
 * for a detached surface: while Back is held, the page on show shrinks to 90%, its corners round and
 * it shifts `(width / 20) − 8` dp with the finger, and a little up or down with it, with the page
 * Back leads to already showing underneath under a light scrim. Letting go fades the card away and
 * the parent is simply there; cancelling settles the card back to full size.
 *
 * The guidance: https://developer.android.com/design/ui/mobile/guides/patterns/predictive-back
 */
@Stable
internal class BackPeek {
    /** A Back gesture is previewing, or the preview is still finishing (commit or cancel). */
    var active by mutableStateOf(false)
    /** The gesture's progress, 0 to 1, as the system reports it. */
    val progress = Animatable(0f)
    /** The commit: 0 while held, 1 once the card has gone. */
    val commit = Animatable(0f)
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
    /** How far the finger has moved up or down since the gesture's first event, in pixels. */
    var drag by mutableFloatStateOf(0f)
    private var startY = Float.NaN
    /**
     * Back leaves for a screen that is not composed underneath (Search): the page on show is the
     * card, over the app's own ground, and stays hidden once the card has gone.
     */
    var alone by mutableStateOf(false)

    // A cancelled gesture repairs its page in the composition scope. That job must lose ownership
    // when a new gesture or logical navigation starts, even if the new Back has no progress events.
    private var generation = 0L
    private var restoration: Job? = null

    fun interrupt() {
        generation++
        restoration?.cancel()
        restoration = null
    }

    /**
     * Takes ownership for a new gesture. It does not show the card: [follow] does, when the gesture
     * turns out to have a finger behind it.
     */
    fun begin(alone: Boolean = false): Long {
        interrupt()
        this.alone = alone
        return generation
    }

    /**
     * Follows one gesture until it is let go, and returns whether there is a card to finish with.
     *
     * The card appears with the first progress event, not when the gesture starts. A button or
     * hardware Back completes with no events at all (activity-compose 1.11.0 closes the flow
     * without sending any), and then gets the page's ordinary transition, its slide and its cover
     * flying home, like the arrow in the app, rather than a card fading in place. A gesture that
     * takes over a cancelled one still settling carries on from that card.
     *
     * [show] puts what is underneath in place. It runs once, after [active] is set, because a
     * seeking transition waits for the composition that has to see it.
     */
    suspend fun follow(events: Flow<BackEventCompat>, show: suspend () -> Unit = {}): Boolean {
        startY = Float.NaN
        var shown = active
        if (shown) { commit.snapTo(0f); show() }
        events.collect { event ->
            if (!shown) {
                shown = true
                drag = 0f
                active = true
                commit.snapTo(0f)
                show()
            }
            edge = event.swipeEdge
            if (startY.isNaN()) startY = event.touchY
            drag = event.touchY - startY
            progress.snapTo(event.progress)
        }
        return shown
    }

    fun owns(token: Long): Boolean = generation == token

    fun restore(scope: CoroutineScope, token: Long, action: suspend () -> Unit) {
        if (!owns(token)) return
        restoration = scope.launch { if (owns(token)) action() }
    }

    suspend fun reset() {
        progress.snapTo(0f)
        commit.snapTo(0f)
        drag = 0f
        active = false
        alone = false
    }
}

/** The page Back leads to from [page], or null where Back leaves the Library (a page opened from Search). */
internal fun libraryBackPage(page: LibraryPage, fromSearch: Boolean): LibraryPage? = when {
    page == LibraryPage.Top -> null
    page is LibraryPage.Album && page.fromArtist != null -> LibraryPage.Artist(page.fromArtist)
    fromSearch -> null
    else -> LibraryPage.Top
}

/**
 * Whether Back from [page] leaves the Library for Search. Search is another tab and is not composed
 * under the Library, so there is no page to show underneath: the card shrinks over the app's ground.
 */
internal fun libraryBackLeavesForSearch(page: LibraryPage, fromSearch: Boolean): Boolean =
    fromSearch && page != LibraryPage.Top && libraryBackPage(page, fromSearch) == null

/** The guidance's progress curve ([PageBackEasing]), over a progress kept in range. */
internal fun peekEased(progress: Float): Float = PageBackEasing.transform(progress.coerceIn(0f, 1f))

/**
 * The guidance's shift for a window [widthDp] wide, in dp, never negative: what the shrunken card
 * leaves free at each side, less an 8 dp margin. The same rule bounds the vertical shift, from the
 * height.
 */
internal fun peekShiftDp(widthDp: Float): Float = maxOf(0f, widthDp / 20f - 8f)

/**
 * How far the card moves up or down with the finger, in dp: [backFollow]'s share of the height's own
 * [peekShiftDp], growing with the gesture's [eased] progress so the card never comes nearer the top
 * or bottom than its 8 dp margin, and settling back to nothing when the gesture is cancelled.
 */
internal fun peekLiftDp(heightDp: Float, follow: Float, eased: Float): Float =
    peekShiftDp(heightDp) * follow.coerceIn(-1f, 1f) * eased.coerceIn(0f, 1f)

/**
 * While previewing, the parent appears at once underneath and the page on show stays above it. The
 * leaving page needs no exit of its own: a seeking transition keeps both pages composed until it
 * finishes, and [peekCard] moves the leaving one.
 */
internal fun peekTransform(): ContentTransform =
    ContentTransform(EnterTransition.None, ExitTransition.None, targetContentZIndex = -1f, sizeTransform = null)

/**
 * The page on show as a card following the finger, then fading on commit. Library pages draw no
 * ground of their own (the scaffold does), so the card gets [ground] inside its rounded clip; without
 * it the page underneath showed through and only the header seemed to shrink (the user's recordings
 * on the POCO and the Pixel 8, .1376).
 */
internal fun Modifier.peekCard(peek: BackPeek, ground: Color): Modifier = graphicsLayer {
    val p = peekEased(peek.progress.value)
    val c = peek.commit.value
    val sign = if (peek.edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    val scale = lerp(1f, PAGE_BACK_SCALE, p) * lerp(1f, 0.94f, c)
    scaleX = scale
    scaleY = scale
    translationX = sign * (peekShiftDp(size.width / density) * p + 24f * c).dp.toPx()
    translationY = peekLiftDp(size.height / density, backFollow(peek.drag, size.height), p).dp.toPx()
    alpha = 1f - c
    shape = RoundedCornerShape(PAGE_BACK_CORNER * p)
    clip = true
    shadowElevation = lerp(0f, 8f, p).dp.toPx()
}.background(ground)

private fun peekDim(peek: BackPeek): Float = PAGE_BACK_DIM * peekEased(peek.progress.value) * (1f - peek.commit.value)

/** The page Back leads to, dimmed a little while the card is over it. */
internal fun Modifier.peekParent(peek: BackPeek): Modifier = drawWithContent {
    drawContent()
    val dim = peekDim(peek)
    if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
}

/**
 * The ground behind a card that has no page underneath ([BackPeek.alone]), dimmed as a page would
 * be, so the card still reads against a ground of its own colour. Put before [peekCard], so it is
 * drawn outside the card's layer and stays the full size.
 */
internal fun Modifier.peekBackdrop(peek: BackPeek): Modifier = drawBehind {
    val dim = peekDim(peek)
    if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
}
