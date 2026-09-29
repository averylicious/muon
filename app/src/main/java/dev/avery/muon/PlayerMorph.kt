package dev.avery.muon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/** The mini player's top corners, which the growing player starts from. */
internal val MINI_PLAYER_CORNER: Dp = 16.dp
/** The mini player's cover corners, and Now Playing's, which the flying cover morphs between. */
internal val MINI_COVER_CORNER: Dp = 10.dp
internal val NOW_PLAYING_COVER_CORNER: Dp = 24.dp

/** How much of the opening it takes the panel's colour to turn from the mini player's to Now Playing's. */
private const val MORPH_COLOR_SPAN = 0.35f
/** How much of the opening it takes the mini player's own content to fade from the panel. */
private const val MORPH_FACE_FADE = 0.25f
/** When, during the opening, Now Playing's controls fade in, as fractions of the way open. */
private const val MORPH_CONTENT_FROM = 0.2f
private const val MORPH_CONTENT_SPAN = 0.5f

/**
 * How far the panel's colour, and the blurred cover behind Now Playing, have come from the mini
 * player's when the player is [open] of the way open: eased at both ends, so neither end jumps.
 */
internal fun morphColorProgress(open: Float): Float {
    val x = (open / MORPH_COLOR_SPAN).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** The mini player's own content in the growing panel, fading out as Now Playing fades in. */
internal fun morphFaceAlpha(open: Float): Float = (1f - open / MORPH_FACE_FADE).coerceIn(0f, 1f)

/**
 * How far the flying cover has come when the player is [open] of the way open: eased out, so it
 * leaves the thumbnail promptly and reaches its place in Now Playing ahead of the content around it,
 * rather than trailing low over the title while the player grows.
 */
internal fun morphFlight(open: Float): Float {
    val left = 1f - open.coerceIn(0f, 1f)
    return 1f - left * left
}

/**
 * Where the flying cover is: its place in the panel, measured from the panel's top edge, moving from
 * the thumbnail's [from] to Now Playing's [to], drawn at the panel's current [edge]. Measured from the
 * edge, the cover rises with the panel from the first pixel. Measured from the screen, it headed
 * straight for where Now Playing's cover would end up, which starts far below the little panel, so
 * early in a drag it sank and was clipped by the panel's bottom instead of rising.
 */
internal fun morphCoverRect(from: Rect, to: Rect, edge: Float, open: Float): Rect =
    lerp(from, to, morphFlight(open)).translate(0f, edge)

/** Now Playing's controls' opacity when the player is [open] of the way open. */
internal fun morphContentAlpha(open: Float): Float = ((open - MORPH_CONTENT_FROM) / MORPH_CONTENT_SPAN).coerceIn(0f, 1f)

/**
 * How far the player's top edge travels when it grows out of the mini player: from the mini player's
 * top down to where an open player's edge rests, which is the top of the screen less the inset its
 * content gives back ([playerSheetEdgeDrop]). Null when the mini player is not where it can start from.
 */
internal fun morphTravel(miniTop: Float, topInset: Float): Float? = (miniTop - topInset).takeIf { it > 0f }

/**
 * The mini player growing into Now Playing, and shrinking back into it (the Canary experiment, with
 * Expressive motion on): Material's container transform. The player no longer rises from below the
 * screen as a separate sheet: the mini player itself becomes the player. The panel starts as the mini
 * player's own rectangle, in its colour and showing its content (the real mini player steps aside
 * meanwhile), and grows to the whole screen. As it grows its colour turns into Now Playing's, the mini
 * player's content fades out and Now Playing's fades in, and the cover flies from the thumbnail to its
 * place in Now Playing. There is never a second panel fading in over the first.
 *
 * The sheet's one position ([PlayerSheet.position]) still drives everything, so opening, closing, the
 * drag up from the mini player and the drag down from the player's bar all morph, and following a
 * finger still recomposes nothing: every part reads the position in the draw phase. This only measures
 * where things are. Sideways, where the player is a panel rather than a mini player, and with
 * Expressive motion off, the player rises from below the screen as before.
 */
internal class PlayerMorph(private val sheet: PlayerSheet) {
    /** The app's root, which every position here is measured in. */
    var root: LayoutCoordinates? = null
    /** The mini player, and its cover. */
    var mini: LayoutCoordinates? = null
    var miniCover: LayoutCoordinates? = null
    /** The player's content at its own top left, inside the sheet's layer, and Now Playing's cover in it. */
    var body: LayoutCoordinates? = null
    var cover: LayoutCoordinates? = null
    /** Now Playing's cover's size, which the flying cover is laid out at and scaled from. */
    var coverSize by mutableStateOf(IntSize.Zero)
    /** The top system inset, which the player's content gives back as its edge leaves the status bar. */
    var topInset = 0f

    private fun LayoutCoordinates?.live(): LayoutCoordinates? = this?.takeIf { it.isAttached }

    /** How far the player's edge travels, if it grows out of the mini player; null if it rises from below. */
    fun travel(): Float? {
        if (!Expressive.motion) return null
        val root = root.live() ?: return null
        val mini = mini.live() ?: return null
        return morphTravel(root.localPositionOf(mini, Offset.Zero).y, topInset)
    }

    /** Where the growing player's bottom is when it is [open] of the way open, or null if it is not growing. */
    fun bottom(open: Float): Float? {
        if (travel() == null) return null
        val root = root.live() ?: return null
        val mini = mini.live() ?: return null
        return lerp(root.localBoundingBoxOf(mini, clipBounds = false).bottom, root.size.height.toFloat(), open)
    }

    /** How far open the player is while it grows out of the mini player, or null if it is not doing that. */
    private fun growth(): Float? {
        val position = sheet.position.value
        if (position <= 0f || travel() == null) return null
        return 1f - position
    }

    /**
     * Whether the panel grown from the mini player is on screen, so the mini player itself should step
     * aside: the panel is the mini player until it has grown. Only once the panel has been laid out,
     * so there is never a frame with neither.
     */
    fun covering(): Boolean = sheet.position.value < 1f && travel() != null && body.live() != null

    /** The panel's colour: the mini player's [from] turning into Now Playing's [to] as it grows. */
    fun panelColor(from: Color, to: Color): Color {
        val open = growth() ?: return to
        return androidx.compose.ui.graphics.lerp(from, to, morphColorProgress(open))
    }

    /** Whether the panel is growing out of the mini player, rather than resting open or sliding. */
    fun growing(): Boolean = growth() != null

    /** The mini player's own content in the panel: shown only while the panel is growing out of it. */
    fun faceAlpha(): Float = growth()?.let(::morphFaceAlpha) ?: 0f

    /** The blurred cover behind Now Playing, arriving with Now Playing's colour. */
    fun backdropAlpha(): Float = growth()?.let(::morphColorProgress) ?: 1f

    /**
     * How much of the dimming shows: all of it while the player rises from below as before, and in
     * proportion to how far it has grown when it grows out of the mini player, so a drag that has
     * barely begun, or has come back down, dims nothing.
     */
    fun scrimFactor(): Float {
        if (travel() == null) return 1f
        return 1f - sheet.position.value
    }

    /** Now Playing's controls' opacity: fading in while the player grows, and fully shown otherwise. */
    fun contentAlpha(): Float {
        val position = sheet.position.value
        if (position <= 0f || travel() == null) return 1f
        return morphContentAlpha(1f - position)
    }

    /**
     * Where the flying cover is now, and the growing player's top and bottom, which it is clipped to.
     * Null whenever nothing is flying: the player is fully open or closed, it rises from below, or
     * either cover is not on screen to fly between.
     */
    fun frame(): MorphFrame? {
        val position = sheet.position.value
        if (position <= 0f || position >= 1f) return null
        val travel = travel() ?: return null
        val root = root.live() ?: return null
        val mini = mini.live() ?: return null
        val miniCover = miniCover.live() ?: return null
        val body = body.live() ?: return null
        val cover = cover.live() ?: return null
        val open = 1f - position
        // The player's content moves with its layer's translation, which is worked out here exactly as
        // the layer itself works it out (Modifier.playerSheet), so the cover lands where it is drawn.
        val edge = playerSheetEdgeDrop(position * travel, topInset)
        val miniBox = root.localBoundingBoxOf(mini, clipBounds = false)
        // Both covers measured from the panel's top edge: the thumbnail from the mini player's top,
        // where the edge starts, and Now Playing's from the top of the player's content.
        val from = root.localBoundingBoxOf(miniCover, clipBounds = false).translate(0f, -miniBox.top)
        val to = body.localBoundingBoxOf(cover, clipBounds = false)
        val bottom = lerp(miniBox.bottom, root.size.height.toFloat(), open)
        return MorphFrame(morphCoverRect(from, to, edge, open), open, edge, bottom)
    }

    /** Hides the mini player's own cover while the flying one stands in for it. */
    fun miniCoverModifier(): Modifier = Modifier.onGloballyPositioned { miniCover = it }
        .graphicsLayer { alpha = if (frame() != null) 0f else 1f }

    /** Hides Now Playing's own cover while the flying one stands in for it, and measures it. */
    fun coverModifier(): Modifier = Modifier.onGloballyPositioned { cover = it; coverSize = it.size }
        .graphicsLayer { alpha = if (frame() != null) 0f else 1f }
}

/** The flying cover's [cover] rectangle, how far [open] the player is, and its [top] and [bottom] edges. */
internal class MorphFrame(val cover: Rect, val open: Float, val top: Float, val bottom: Float)

/**
 * The cover flying between the mini player and Now Playing, drawn above the player and clipped to it,
 * so it never shows outside the growing rectangle. Laid out at Now Playing's cover size, so its picture
 * is the large one already in memory, and scaled down towards the thumbnail; its corners are scaled
 * back up so they morph from the thumbnail's to Now Playing's.
 */
@Composable
internal fun MorphingCover(morph: PlayerMorph, url: String?) {
    val laidOut = morph.coverSize
    if (url == null || laidOut.width <= 0 || laidOut.height <= 0) return
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().clearAndSetSemantics {}.drawWithContent {
        val frame = morph.frame() ?: return@drawWithContent
        clipRect(top = frame.top, bottom = frame.bottom) { this@drawWithContent.drawContent() }
    }) {
        Artwork(url, Modifier.size(with(density) { laidOut.width.toDp() }, with(density) { laidOut.height.toDp() })
            .graphicsLayer {
                val frame = morph.frame()
                if (frame == null) { alpha = 0f; return@graphicsLayer }
                val scale = frame.cover.width / laidOut.width
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = frame.cover.left
                translationY = frame.cover.top
                scaleX = scale
                scaleY = frame.cover.height / laidOut.height
                val corner = lerp(MINI_COVER_CORNER.toPx(), NOW_PLAYING_COVER_CORNER.toPx(), frame.open)
                shape = RoundedCornerShape(corner / scale.coerceAtLeast(0.01f))
                clip = true
            })
    }
}

/**
 * The growing player's outline: the top [height] pixels of its layer, with rounded top corners. Its
 * bottom is square, like the mini player's, which sits on the navigation bar.
 */
internal class TopRoundedClip(private val corner: Float, private val height: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(topRoundedRect(size, corner, height))
}

/** [TopRoundedClip]'s rectangle: within the layer, and with corners no larger than half its height. */
internal fun topRoundedRect(size: Size, corner: Float, height: Float): RoundRect {
    val bottom = height.coerceIn(0f, size.height)
    val radius = CornerRadius(corner.coerceIn(0f, bottom / 2f))
    return RoundRect(0f, 0f, size.width, bottom, topLeftCornerRadius = radius, topRightCornerRadius = radius)
}
