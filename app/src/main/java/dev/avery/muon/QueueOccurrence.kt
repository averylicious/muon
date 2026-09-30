package dev.avery.muon

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.UUID

private const val QUEUE_OCCURRENCE_EXTRA = "dev.avery.muon.queueOccurrence"

/** Fresh for each queue construction/insertion; survives controller serialization and source updates. */
internal fun queueOccurrence(item: MediaItem): MediaItem {
    val extras = item.mediaMetadata.extras?.let(::Bundle) ?: Bundle()
    extras.putString(QUEUE_OCCURRENCE_EXTRA, UUID.randomUUID().toString())
    return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

/** Not a media/cache/song ID or an authentication token: only temporary playlist occurrence identity. */
internal fun queueOccurrenceKey(item: MediaItem): String? =
    item.mediaMetadata.extras?.getString(QUEUE_OCCURRENCE_EXTRA)?.takeIf {
        runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
    }

/** Ambiguous legacy/cloned occurrences cannot establish ownership for a deferred Undo. */
internal fun queueOccurrences(player: Player): List<Pair<String, String>>? {
    val keys = ArrayList<Pair<String, String>>(player.mediaItemCount)
    val seen = HashSet<String>()
    for (i in 0 until player.mediaItemCount) {
        val item = player.getMediaItemAt(i)
        val key = queueOccurrenceKey(item) ?: return null
        if (!seen.add(key)) return null
        keys += item.mediaId to key
    }
    return keys
}
