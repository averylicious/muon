@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.MediaItem

/** Logical queue bounds, not a byte-exact Binder/process heap estimate. Every saved record stays kept. */
internal data class SavedQueueLimits(val items: Int = 2_048, val textBytes: Long = 4L * 1024 * 1024)
internal class SavedQueueLimit : IllegalArgumentException("Too many saved copies or too much metadata to queue at once")
internal data class SavedQueuePlan(val items: List<MediaItem>, val startIndex: Int)

/**
 * Full complete-copy order or an explicit refusal: never silently queue a prefix/window (#253).
 * Validate count and UTF-8 text before building any MediaItems/Bundles. The bounded retained entry
 * references are a preparation list, not a second complete-library filter in Compose. Caller applies
 * the plan only after success, so refusal leaves current playback untouched and can offer a smaller
 * explicit selection. Paging/hydration must later provide this ordered iterable off the UI thread.
 */
internal fun prepareSavedQueue(entries: Iterable<SavedEntry>, selected: SavedRef,
    limits: SavedQueueLimits = SavedQueueLimits()): SavedQueuePlan? {
    require(limits.items > 0 && limits.textBytes >= 0)
    val kept = ArrayList<SavedEntry>()
    var left = limits.textBytes
    var start = -1
    for (entry in entries) {
        if (!entry.complete) continue
        if (kept.size >= limits.items) throw SavedQueueLimit()
        // ID and URI carry the locator twice; the occurrence UUID is 36 ASCII bytes. Field/key/Bundle
        // and player/source overhead is not estimated here; its cardinality is limited separately.
        val fields = listOf(entry.ref.handle, entry.ref.handle, entry.title(), entry.subtitle(),
            entry.displaySong?.album?.takeIf { it.isNotBlank() }.orEmpty(),
            if (entry.ownCover) savedArtUrl(entry.ref.requestId) else "")
        val characters = fields.sumOf { it.length.toLong() } + 36L
        if (characters > left) throw SavedQueueLimit() // Before allocating UTF-8 encodings.
        val bytes = fields.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } + 36L
        if (bytes > left) throw SavedQueueLimit()
        left -= bytes
        if (start < 0 && entry.ref == selected) start = kept.size
        kept += entry
    }
    if (start < 0) return null
    return SavedQueuePlan(kept.map { it.mediaItem() }, start)
}
