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
internal fun queueLength(durations: List<Long?>): QueueLength = QueueLength(
    durations.sumOf { it?.takeIf { d -> d > 0 } ?: 0L },
    durations.all { it != null && it > 0 })

/**
 * The line beside *Next up*: "10 songs, 34 minutes". When some lengths are unknown it says "at least",
 * rather than presenting a partial total as exact; when none are known it gives only the count.
 */
internal fun queueSummary(count: Int, length: QueueLength): String {
    val songs = "$count ${if (count == 1) "song" else "songs"}"
    if (count == 0 || length.millis <= 0) return songs
    val minutes = ((length.millis + 30_000) / 60_000).coerceAtLeast(1)
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
