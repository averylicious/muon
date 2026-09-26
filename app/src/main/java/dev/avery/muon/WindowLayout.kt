package dev.avery.muon

/** Material's compact height class ends here; below it a window has no height to spare. */
internal const val SHORT_WINDOW_HEIGHT = 480f

/**
 * Whether a window is laid out sideways: short, and wider than it is tall, as a phone in landscape.
 *
 * Height is what runs out there, so what normally stacks is set side by side instead: the tabs move
 * to a rail at the start, and Now Playing puts its cover beside its controls. A short window that is
 * not wide, such as a small split-screen pane, has no room beside anything either, and keeps the
 * stacked layout, which scrolls.
 */
internal fun sidewaysLayout(widthDp: Float, heightDp: Float): Boolean =
    heightDp < SHORT_WINDOW_HEIGHT && widthDp > heightDp
