package dev.avery.muon

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where a row sits in a joined group (the Refined mockup): the ends of a group are well rounded, the
 * joins between rows only slightly, and the current row becomes its own rounded pill. Used by On this
 * device and, with Expressive motion, by every song list.
 */
internal enum class GroupPlace { Only, First, Middle, Last }

internal fun groupPlace(index: Int, size: Int): GroupPlace = when {
    size <= 1 -> GroupPlace.Only
    index == 0 -> GroupPlace.First
    index == size - 1 -> GroupPlace.Last
    else -> GroupPlace.Middle
}

internal val GroupPlace.startsGroup: Boolean get() = this == GroupPlace.First || this == GroupPlace.Only
internal val GroupPlace.endsGroup: Boolean get() = this == GroupPlace.Last || this == GroupPlace.Only

internal val GROUP_END: Dp = 22.dp
internal val GROUP_JOIN: Dp = 6.dp
/** Between rows of a group: Material's segmented list gap. */
internal val GROUP_GAP: Dp = 2.dp
/** A group's side margin. */
internal val GROUP_INSET: Dp = 10.dp

/** The row's shape, its corners easing as it moves within the group or becomes current. */
@Composable
internal fun groupShape(place: GroupPlace, current: Boolean): RoundedCornerShape {
    fun corner(end: Boolean) = if (current || end) GROUP_END else GROUP_JOIN
    val top by animateDpAsState(corner(place.startsGroup), motionSpatial(), label = "row top")
    val bottom by animateDpAsState(corner(place.endsGroup), motionSpatial(), label = "row bottom")
    return RoundedCornerShape(top, top, bottom, bottom)
}
