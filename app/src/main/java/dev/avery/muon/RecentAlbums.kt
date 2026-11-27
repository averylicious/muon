package dev.avery.muon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** How many albums Jump back in remembers. */
internal const val RECENT_ALBUMS = 10

/** Jump back in needs a few albums to be worth its room; with fewer the grid starts at the top. */
internal const val RECENT_ALBUMS_SHOWN_FROM = 3

/** [recent] with [key] moved to the front, most recently played first, at most [RECENT_ALBUMS]. */
internal fun playedAlbum(recent: List<String>, key: String): List<String> =
    (listOf(key) + recent.filter { it != key }).take(RECENT_ALBUMS)

/** The track a player item names, from the `<origin>/<id>` media ID Muon gives it; null for another server's. */
internal fun trackIdOf(mediaId: String?, origin: String?): Long? {
    if (mediaId == null || origin == null || !mediaId.startsWith("$origin/")) return null
    return mediaId.substring(origin.length + 1).toLongOrNull()
}

/** The remembered albums still in the library, most recent first. */
internal fun recentAlbums(keys: List<String>, albums: List<LibraryAlbum>): List<LibraryAlbum> {
    val byKey = albums.associateBy { it.key }
    return keys.mapNotNull(byKey::get)
}

/** How much of a carousel cover's label shows: none until the cover is past halfway to its largest. */
internal fun carouselLabelAlpha(size: Float, minSize: Float, maxSize: Float): Float {
    if (maxSize <= minSize) return 1f
    val grown = (size - minSize) / (maxSize - minSize)
    return ((grown - 0.5f) * 2f).coerceIn(0f, 1f)
}

/**
 * The Canary experiment's Jump back in: the albums played lately, most recent first, as Material 3's
 * multi-browse carousel across the top of the Albums grid. A large cover or two with smaller ones
 * shrinking towards the edge, each growing as it scrolls into focus; tapping one opens the album.
 * The covers do not carry the shared-element key, which the same album's tile in the grid below
 * already holds.
 */
internal fun LazyGridScope.jumpBackIn(albums: List<LibraryAlbum>, endpoint: ServerEndpoint?, open: (LibraryAlbum) -> Unit) {
    item(key = "jump-back-in", span = { GridItemSpan(maxLineSpan) }, contentType = "jump-back-in") {
        Column(Modifier.padding(bottom = 8.dp)) {
            Text("Jump back in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 12.dp).semantics { heading() })
            val count by rememberUpdatedState(albums.size)
            val state = rememberCarouselState { count }
            HorizontalMultiBrowseCarousel(state, preferredItemWidth = 176.dp,
                modifier = Modifier.fillMaxWidth().height(176.dp).padding(horizontal = 8.dp), itemSpacing = 8.dp) { i ->
                val album = albums.getOrNull(i) ?: return@HorizontalMultiBrowseCarousel
                Box(Modifier.fillMaxHeight().maskClip(RoundedCornerShape(24.dp))
                    .clickable(onClickLabel = "Open album") { open(album) }
                    .semantics { contentDescription = "${albumLabel(album.title)}, ${artistLabel(displayCredits(album.artist))}" }) {
                    Artwork(albumArt(endpoint, album), Modifier.fillMaxSize())
                    // The album's name over a large cover (the Refined mockup): it fades out as the cover
                    // narrows towards the edge, and keeps to the part of the cover the mask leaves on show.
                    val info = carouselItemDrawInfo
                    fun shown(): Float = carouselLabelAlpha(info.size, info.minSize, info.maxSize)
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .graphicsLayer { alpha = shown() }
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                        .clearAndSetSemantics {}) {
                        Text(albumLabel(album.title), color = Color.White, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer { translationX = info.maskRect.left }
                                .padding(start = 14.dp, end = 14.dp, top = 24.dp, bottom = 12.dp))
                    }
                }
            }
        }
    }
}
