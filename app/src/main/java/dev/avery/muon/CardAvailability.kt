package dev.avery.muon

import android.os.Environment
import java.io.File

/**
 * Card availability containment (#179, slice S1; docs/audits/2026-10-01-card-availability-containment.md).
 *
 * A card shelf whose storage is observably gone is **unavailable**: new decisions skip it, and it is not
 * treated as empty, cleared, or quietly replaced by the phone. These decisions do not release, recreate
 * or delete its cache, download index or manager. That is all they establish: they do not guarantee its
 * songs survive. The card can disappear during a cache operation already under way, whose loss of the
 * cache's mapping is not prevented here; a different card at the same path is not told apart; and
 * nothing here notices a card being inserted or swapped while Muon runs. Each check is a snapshot,
 * not a lock.
 */
internal interface ShelfState {
    /** Whether this shelf's storage is there to read and write. The phone's always is. */
    fun available(): Boolean
    /** Whether its download index records [id] as finished. Reads the index only, never the cache. */
    fun completed(id: String): Boolean
    /** Whether its download index has any record of [id], finished or not. */
    fun holds(id: String): Boolean
}

/**
 * Whether [folder], Muon's own folder on the card a shelf was made for, is present: a directory on a
 * mounted volume. The same mounted-state call [cardFolder] uses to pick the card. Read only: nothing is
 * created to find out, since a folder made on an empty mount point would prove nothing. A read-only
 * mount counts as unavailable, because the cache writes there.
 */
internal fun cardPresent(folder: File): Boolean = runCatching {
    Environment.getExternalStorageState(folder) == Environment.MEDIA_MOUNTED && folder.isDirectory
}.getOrDefault(false)

/** The shelves whose finished downloads are listed and resumed now. */
internal fun <S : ShelfState> availableShelves(shelves: List<S>): List<S> = shelves.filter { it.available() }

/** Where a new download goes. Choosing the card never falls back to the phone without saying so. */
internal enum class DownloadTarget { Phone, Card, CardUnavailable }

internal fun downloadTarget(card: ShelfState?, onCard: Boolean): DownloadTarget = when {
    !onCard -> DownloadTarget.Phone
    card != null && card.available() -> DownloadTarget.Card
    else -> DownloadTarget.CardUnavailable
}

/**
 * Once [id] finishes on [finished], the other copies a move leaves behind, to remove. Nothing is removed
 * unless [finished] is available too: a completion reported for a card that has gone must not take away
 * the phone's copy, perhaps the only real one. No remove is sent to an unavailable shelf either.
 */
internal fun <S : ShelfState> leftoverCopies(finished: S, shelves: List<S>, id: String): List<S> =
    if (!finished.available()) emptyList()
    else shelves.filter { it !== finished && it.available() && it.completed(id) }

/** Whether a move between [from] and [to] may start or carry on to its next song. */
internal fun canMove(from: ShelfState?, to: ShelfState?): Boolean =
    from != null && to != null && from.available() && to.available()

/**
 * Hands a song copied from [from] to [to]'s manager, unless either became unavailable between the copy
 * and this callback. Once handed over, [to]'s completion removes [from]'s copy (see [leftoverCopies]),
 * so a gone source must stop it as much as a gone destination. Returns whether it was sent. A snapshot:
 * either can still go just after.
 */
internal fun deliverMovedCopy(from: ShelfState, to: ShelfState, send: () -> Unit): Boolean {
    if (!canMove(from, to)) return false
    send()
    return true
}
