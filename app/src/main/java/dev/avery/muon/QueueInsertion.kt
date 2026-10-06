package dev.avery.muon

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.UUID

/** Snackbar Undo identifies this insertion, not another occurrence of the same song. */
internal class QueueInsertion(val item: MediaItem, val token: String)
private const val INSERTION_EXTRA = "dev.avery.muon.queueInsertion"

internal fun queueInsertion(item: MediaItem): QueueInsertion {
    val token = UUID.randomUUID().toString()
    val extras = item.mediaMetadata.extras?.let(::Bundle) ?: Bundle()
    extras.putString(INSERTION_EXTRA, token)
    return QueueInsertion(item.buildUpon().setMediaMetadata(
        item.mediaMetadata.buildUpon().setExtras(extras).build()).build(), token)
}

/** Only the exact surviving insertion may be removed; an absent/ambiguous token is a no-op. */
internal fun undoQueueInsertion(player: Player, insertion: QueueInsertion): Boolean {
    var index = -1
    for (i in 0 until player.mediaItemCount) {
        if (player.getMediaItemAt(i).mediaMetadata.extras?.getString(INSERTION_EXTRA) != insertion.token) continue
        if (index != -1) return false
        index = i
    }
    if (index == -1) return false
    player.removeMediaItem(index)
    return true
}
