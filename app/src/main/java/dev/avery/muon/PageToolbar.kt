package dev.avery.muon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.blur.BlurRadiusSpec
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Room a page's list leaves at its end for [PageToolbar], so its last song can scroll clear of it. */
internal val PAGE_TOOLBAR_ROOM: Dp = 88.dp

/**
 * Whether a page's Play and Shuffle have scrolled away: the list is past the item at [actionsIndex],
 * the row that holds them, so a toolbar carrying them again is wanted.
 */
internal fun pageActionsGone(firstVisibleIndex: Int, actionsIndex: Int): Boolean = firstVisibleIndex > actionsIndex

/** Over the backdrop blur, enough of the toolbar's colour to read its icons on any cover. */
private const val TOOLBAR_GLASS_TINT = 0.6f
private val TOOLBAR_BLUR = 24.dp

/**
 * The floating toolbar for an album's or an artist's page: once Play and Shuffle have scrolled off the
 * top, Material 3 Expressive's `HorizontalFloatingToolbar` rises above the bars with them again, with
 * Back and a return to the top, which also left with the header. It sinks away when the header's own
 * buttons come back. Only when the songs can be played.
 *
 * Its surface follows Bar surface ([glassLook]): glass over a live backdrop blur on Android 17 QPR2,
 * see-through when Tinted, opaque otherwise. Play sits inside the pill (the Gen 4 mockup), so the blur
 * fills exactly the pill's shape.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BoxScope.PageToolbar(state: LazyListState, actionsIndex: Int, enabled: Boolean,
    backLabel: String, back: () -> Unit, playAll: (shuffle: Boolean) -> Unit) {
    val gone by remember(state, actionsIndex) {
        derivedStateOf { pageActionsGone(state.firstVisibleItemIndex, actionsIndex) }
    }
    val scope = rememberCoroutineScope()
    val look = glassLook(LocalBarSurface.current, backdropOnThisPhone)
    // Down with the floating bars as they tuck, so no empty room opens between them and the toolbar.
    val drop = LocalBarsDrop.current
    AnimatedVisibility(gone && enabled,
        Modifier.align(Alignment.BottomCenter).padding(bottom = LocalUnderBars.current + 16.dp).offset { IntOffset(0, drop.px) },
        enter = fadeIn(motionShort()) + scaleIn(motionSpatial(), initialScale = 0.8f) +
            slideInVertically(motionSpatial()) { it / 2 },
        exit = fadeOut(motionShort()) + scaleOut(motionShort(), targetScale = 0.8f) +
            slideOutVertically(motionShort()) { it / 2 },
        label = "page toolbar") {
        val colors = MaterialTheme.colorScheme
        val tint = when (look) {
            GlassLook.Glass -> TOOLBAR_GLASS_TINT
            GlassLook.Tinted -> TINTED_TINT
            GlassLook.Solid -> 1f
        }
        // A see-through toolbar's shadow would show through it as a dark smudge.
        HorizontalFloatingToolbar(expanded = true,
            modifier = Modifier.backdropBlur(BlurRadiusSpec.uniform(TOOLBAR_BLUR), FloatingToolbarDefaults.ContainerShape, enabled = look == GlassLook.Glass),
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(toolbarContainerColor = colors.primaryContainer.copy(alpha = tint)),
            expandedShadowElevation = if (look == GlassLook.Solid) FloatingToolbarDefaults.ContainerExpandedElevationWithFab else 0.dp,
            trailingContent = {
                // Play in the primary colour every other Play button in Muon wears; Material's vibrant
                // button is tertiary, which on the Pixel read as a mustard stranger beside it.
                FilledIconButton(onClick = { playAll(false) }, shape = RoundedCornerShape(16.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                    modifier = Modifier.padding(start = 4.dp).size(48.dp).semantics { contentDescription = "Play" }) { MuonIcon("play") }
            }) {
            IconButton(onClick = back, modifier = Modifier.semantics { contentDescription = backLabel }) { MuonIcon("back") }
            IconButton(onClick = { scope.launch { state.animateScrollToItem(0) } },
                modifier = Modifier.semantics { contentDescription = "Back to top" }) {
                MuonIcon("collapse", Modifier.rotate(180f))
            }
            IconButton(onClick = { playAll(true) }, modifier = Modifier.semantics { contentDescription = "Shuffle" }) {
                MuonIcon("shuffle")
            }
        }
    }
}
