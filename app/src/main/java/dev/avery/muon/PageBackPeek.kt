package dev.avery.muon

import androidx.activity.BackEventCompat
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * it shifts `(width / 20) − 8` dp with the finger, with the page Back leads to already showing
 * underneath under a light scrim. Letting go fades the card away and the parent is simply there;
 * cancelling springs the card back to full size.
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

    suspend fun reset() {
        progress.snapTo(0f)
        commit.snapTo(0f)
        active = false
    }
}

/** The page Back leads to from [page], or null where Back leaves the Library (a page opened from Search). */
internal fun libraryBackPage(page: LibraryPage, fromSearch: Boolean): LibraryPage? = when {
    page == LibraryPage.Top -> null
    page is LibraryPage.Album && page.fromArtist != null -> LibraryPage.Artist(page.fromArtist)
    fromSearch -> null
    else -> LibraryPage.Top
}

/** The guidance's progress curve: decelerate, never linear. */
private val PEEK_EASING = CubicBezierEasing(0f, 0f, 0f, 1f)

internal fun peekEased(progress: Float): Float = PEEK_EASING.transform(progress.coerceIn(0f, 1f))

/** The guidance's horizontal shift for a [widthDp]-wide window, in dp, never negative. */
internal fun peekShiftDp(widthDp: Float): Float = maxOf(0f, widthDp / 20f - 8f)

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
    val scale = lerp(1f, 0.9f, p) * lerp(1f, 0.94f, c)
    scaleX = scale
    scaleY = scale
    translationX = sign * (peekShiftDp(size.width / density) * p + 24f * c).dp.toPx()
    alpha = 1f - c
    shape = RoundedCornerShape(lerp(0f, 32f, p).dp)
    clip = true
    shadowElevation = lerp(0f, 8f, p).dp.toPx()
}.background(ground)

/** The page Back leads to, dimmed a little while the card is over it. */
internal fun Modifier.peekParent(peek: BackPeek): Modifier = drawWithContent {
    drawContent()
    val dim = 0.22f * peekEased(peek.progress.value) * (1f - peek.commit.value)
    if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
}
