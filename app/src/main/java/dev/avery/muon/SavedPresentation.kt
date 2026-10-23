@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * One displayed page of the On this device screen (saved copies), split by where each copy came from.
 * Only this page: the catalog still pages all copies together in its own order, so on a multi-page
 * catalog the sections and their counts describe the page shown, never the whole catalog. Each row
 * is still exactly one listed copy; nothing is merged, sorted across pages or dropped.
 */
internal class SavedPageSections(val downloads: List<SavedEntry>, val listening: List<SavedEntry>)

internal fun savedPageSections(entries: List<SavedEntry>): SavedPageSections =
    entries.partition { it.ref.source == SavedSource.Download }.let { (downloads, listening) ->
        SavedPageSections(downloads, listening)
    }

/** Whether more than one page exists, so sections and counts must be labelled as this page's. */
internal fun savedPaged(count: Long, offset: Long): Boolean = offset > 0 || count > SAVED_PAGE_SIZE

/**
 * Kept from listening starts collapsed, but never hides the only rows on a page or the copy that is
 * playing, so collapsing can't make a page look empty or lose the current song.
 */
internal fun savedListeningShown(chosen: Boolean, sections: SavedPageSections, current: String?): Boolean =
    chosen || sections.downloads.isEmpty() || (current != null && sections.listening.any { it.ref.handle == current })

/**
 * Whether Update storage format may be offered for exactly [entry]: only a confirmed true from the
 * existing fresh check, run off the main thread. Anything else (a missing card, no owned storage, an
 * incomplete or listening copy, or a failed check) is false, never optimistic. Cancellation propagates.
 * The update dialog checks again before anything starts.
 */
internal suspend fun storageUpdateConfirmed(entry: SavedEntry, cardMissing: Boolean, check: ((String) -> Boolean)?,
    io: CoroutineContext = Dispatchers.IO): Boolean {
    if (cardMissing || check == null || !entry.complete || !entry.ref.migrationLocator()) return false
    return try { withContext(io) { check(entry.ref.key) } }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

/** The artist line for a row: the copy's own stored credits, or a plain fallback. */
internal fun SavedEntry.artistLine(): String = when {
    metadataTooLarge -> "Details too large to show"
    else -> displaySong?.artist?.takeIf { it.isNotBlank() }?.let(::displayCredits) ?: "Unknown artist"
}

/** A row's one status, in words (an icon goes with it), or null when the row needs none. */
internal fun SavedEntry.statusText(current: Boolean): String? = when {
    stoppedAfterRestart -> if (complete) "Kept after restart" else "Incomplete · save it again from the live song"
    state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING -> "Waiting to save"
    state == Download.STATE_DOWNLOADING -> "Saving…"
    state == Download.STATE_FAILED -> "Saving failed · can't play"
    !complete -> "Incomplete · can't play"
    current -> "Now playing"
    ref.shelf == SavedShelf.Card -> "SD card"
    else -> null
}
