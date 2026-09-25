package dev.avery.muon

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/**
 * Which page the Library tab shows, by identity alone. The page's data is read live where it is
 * drawn: a page that carried its data would count as a new page whenever a refresh changed it, and
 * replay its entrance.
 */
internal sealed interface LibraryPage {
    val key: String

    data object Top : LibraryPage { override val key = "top" }
    data class Playlist(val id: String) : LibraryPage { override val key = "playlist:$id" }
    data class Artist(val artistKey: String) : LibraryPage { override val key = "artist:$artistKey" }
}

/** The page on show, with the same precedence the Library tab has always used: a playlist first. */
internal fun libraryPage(openPlaylist: String?, artistPage: Boolean, artistKey: String?): LibraryPage = when {
    openPlaylist != null -> LibraryPage.Playlist(openPlaylist)
    artistPage && artistKey != null -> LibraryPage.Artist(artistKey)
    else -> LibraryPage.Top
}

/** How one page replaces another: deeper, back up, or across to a page at the same depth. */
internal enum class LibraryMotion { Forward, Back, Across }

internal fun libraryMotion(from: LibraryPage, to: LibraryPage): LibraryMotion {
    val depth = { page: LibraryPage -> if (page == LibraryPage.Top) 0 else 1 }
    return when {
        depth(to) > depth(from) -> LibraryMotion.Forward
        depth(to) < depth(from) -> LibraryMotion.Back
        else -> LibraryMotion.Across
    }
}

/**
 * How far a page slides while it fades: Material's shared-axis distance. A fixed distance rather than
 * a fraction of the width, so it reads the same on a phone and a tablet, and small, because the fade
 * carries the change and the slide only says which way it went.
 */
internal val LIBRARY_PAGE_SHIFT: Dp = 30.dp

/**
 * The slide's offset, in the direction the container would slide a whole page ([full]), but only
 * [shift] of the way, and never further than the whole page.
 */
internal fun pageShift(full: Int, shift: Int): Int = full.sign * minOf(shift, abs(full))

/**
 * A detail page arrives from the end and leaves back towards it, so it reads as a step below the list
 * it came from; a page at the same depth only cross-fades. Start and End follow the layout direction.
 * Like every animation it follows the system animation scale, and finishes at once when animations
 * are off.
 */
internal fun AnimatedContentTransitionScope<LibraryPage>.libraryPageTransform(shift: Int): ContentTransform {
    val towards = when (libraryMotion(initialState, targetState)) {
        LibraryMotion.Forward -> SlideDirection.Start
        LibraryMotion.Back -> SlideDirection.End
        LibraryMotion.Across -> return fadeIn(motionMedium()) togetherWith fadeOut(motionShort())
    }
    return (slideIntoContainer(towards, motionMedium()) { pageShift(it, shift) } + fadeIn(motionMedium()))
        .togetherWith(slideOutOfContainer(towards, motionMedium()) { pageShift(it, shift) } + fadeOut(motionShort()))
}

/**
 * A page that is leaving takes no touches and is hidden from TalkBack. Otherwise a quick second tap
 * could open another artist from a list already on its way out, or play from a page already closed.
 */
internal fun Modifier.leaving(leaving: Boolean): Modifier = if (!leaving) this else
    clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

private class Kept<T>(var value: T)

/**
 * [value] while the page is on show, and the last such value once it is leaving, so a closing page
 * keeps what it was showing until it has gone. Never used while the page is on show, so a live page
 * waiting for new data cannot show old data in the meantime.
 */
@Composable
internal fun <T> keptWhileLeaving(leaving: Boolean, value: T): T {
    val kept = remember { Kept(value) }
    if (!leaving) kept.value = value
    return kept.value
}
