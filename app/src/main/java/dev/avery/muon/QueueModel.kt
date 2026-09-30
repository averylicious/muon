package dev.avery.muon

/**
 * The positions of the songs after [current], in the order the player will actually play them.
 * [next] is the player's own answer for what follows a position — `Timeline.getNextWindowIndex` with
 * repeat off — so with shuffle on this follows the shuffled order, not the list's (#47). Stops at the
 * end, at anything out of range, and at a loop, so a malformed order can never hang the screen.
 */
internal fun upNextOrder(current: Int, count: Int, next: (Int) -> Int): List<Int> {
    if (current !in 0 until count) return emptyList()
    val order = ArrayList<Int>()
    val seen = HashSet<Int>().apply { add(current) }
    var index = next(current)
    while (index in 0 until count && seen.add(index)) {
        order += index
        index = next(index)
    }
    return order
}

/** How long a run of songs lasts, and whether every song's length was known. */
internal data class QueueLength(val millis: Long, val complete: Boolean)

/** Unknown lengths (null, zero or negative, as #62 leaves them) are counted as missing, not as zero. */
internal fun queueLength(durations: List<Long?>): QueueLength {
    var total = 0L
    var complete = true
    for (duration in durations) {
        if (duration == null || duration <= 0) { complete = false; continue }
        if (duration > Long.MAX_VALUE - total) {
            total = Long.MAX_VALUE
            complete = false // The representable total is only a lower bound.
        } else total += duration
    }
    return QueueLength(total, complete)
}

/**
 * The line beside *Next up*: "10 songs, 34 minutes". When some lengths are unknown it says "at least",
 * rather than presenting a partial total as exact; when none are known it gives only the count.
 */
internal fun queueSummary(count: Int, length: QueueLength): String {
    val songs = "$count ${if (count == 1) "song" else "songs"}"
    if (count == 0 || length.millis <= 0) return songs
    // Divide before rounding: adding 30 seconds can overflow even for one accepted Long tag.
    val minutes = (length.millis / 60_000 + if (length.millis % 60_000 >= 30_000) 1 else 0).coerceAtLeast(1)
    val time = if (minutes < 60) "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
        else "${minutes / 60} ${if (minutes / 60 == 1L) "hour" else "hours"}" +
            (if (minutes % 60 > 0) " ${minutes % 60} min" else "")
    return if (length.complete) "$songs, $time" else "$songs, at least $time"
}

/**
 * The stream address for a queue item that is being put back, rebuilt from its `origin/id` media ID.
 * An item read back from the player may come without its address, and the playback service accepts
 * only Tauon file addresses it can validate, so Undo supplies the same address `mediaItem` would.
 * Null when the ID is not one Muon made.
 */
internal fun restoreUrl(mediaId: String): String? {
    val id = mediaId.substringAfterLast('/', "").toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val endpoint = runCatching { ServerEndpoint.parse(mediaId.substringBeforeLast('/')) }.getOrNull() ?: return null
    return if (endpoint.origin + "/$id" == mediaId) endpoint.url("/api1/file/$id") else null
}

/**
 * How many *Next up* songs are shown before "Show all". A long queue (a whole library, shuffled) is
 * hundreds of songs; a window keeps it scannable, and removing a song pulls the next one up into view,
 * as streaming apps do. The count and total beside *Next up* still cover the whole queue.
 */
internal const val QUEUE_WINDOW = 50

/**
 * A stable key for each queue row: the media ID and which occurrence of it this is, in playing order.
 * Unlike the list position, it survives songs being removed or moved elsewhere in the queue, so those
 * rows keep their state (a swipe in progress, their place on screen) instead of being rebuilt. A song
 * queued twice still gets two distinct keys.
 */
internal fun occurrenceKeys(mediaIds: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return mediaIds.map { id ->
        val n = seen.merge(id, 1, Int::plus)!!
        "$id#$n"
    }
}

/** This list with the element at [from] moved to [to], everything between shifting by one. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * The player move that puts the song shown at list position [from] at position [to], given the
 * player index each shown position held when the drag began. Moving by the destination's original
 * index is exactly right when those indices are consecutive, which they are with shuffle off; the
 * screen does not offer reordering with shuffle on, where list and playing order differ. Null for no move.
 */
internal fun queueMove(indices: List<Int>, from: Int, to: Int): Pair<Int, Int>? {
    if (from !in indices.indices || to !in indices.indices || from == to) return null
    return indices[from] to indices[to]
}

/**
 * How far to scroll this frame while a dragged row is held near an edge of the list: nothing in the
 * middle, faster the deeper the row reaches into the [edge] band, up to [max] pixels per frame.
 * [top] and [bottom] are the dragged row's edges; [start] and [end] are the list's visible bounds.
 */
internal fun edgeScroll(top: Float, bottom: Float, start: Float, end: Float, edge: Float, max: Float): Float = when {
    edge <= 0f -> 0f
    top < start + edge -> -max * ((start + edge - top) / edge).coerceIn(0f, 1f)
    bottom > end - edge -> max * ((bottom - (end - edge)) / edge).coerceIn(0f, 1f)
    else -> 0f
}
