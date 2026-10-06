@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.Download
import java.util.UUID

/**
 * Saved copies (#213; docs/handoffs/2026-10-07-saved-access-implementation.md). A copy kept on the phone
 * or the card is named by where its bytes are (shelf, download row or played copy, request ID and cache
 * key), never by a Tauon track number, which the server may reuse for a different song. Nothing here
 * proves a copy is the song Tauon now has under any number: equal tags, IDs and keys are not audio
 * identity, so every saved copy is shown as Unverified and the live library always streams.
 */
internal enum class SavedShelf(val token: String) { Phone("phone"), Card("card") }

internal enum class SavedSource(val token: String) { Download("download"), Played("played") }

internal const val SAVED_SCHEME = "muon-saved"
private const val SAVED_VERSION = "1"
/** Bounds on what a handle may carry, so a forged or corrupted one is refused before any lookup. */
internal const val SAVED_HANDLE_MAX = 4096
private const val SAVED_FIELD_MAX = 1024

/** The prefix of request IDs and cache keys Muon makes for new saves: each one a fresh, unused name. */
internal const val NEW_SAVE_PREFIX = "saved/"

/** Where a new copy was saved from ("origin/track"), kept beside a played copy; provenance, not identity. */
internal const val SAVED_FROM_METADATA = "muon-saved-from"

/**
 * One saved copy's locator. The handle is its media ID and URI while queued: `muon-saved:1:<shelf>:
 * <source>:<request>:<key>`, request and key in unpadded URL-safe Base64. It locates bytes; it is not
 * authentication, and admission still checks that the row or copy it names exists.
 */
internal class SavedRef private constructor(val shelf: SavedShelf, val source: SavedSource,
    val requestId: String, val key: String) {
    val handle: String = listOf(SAVED_SCHEME, SAVED_VERSION, shelf.token, source.token,
        encode(requestId), encode(key)).joinToString(":")

    override fun equals(other: Any?) = other is SavedRef && other.handle == handle
    override fun hashCode() = handle.hashCode()
    override fun toString() = handle

    companion object {
        /** A download row's locator; null when it cannot be named within the bounds. */
        fun download(shelf: SavedShelf, requestId: String, key: String): SavedRef? =
            of(shelf, SavedSource.Download, requestId, key)

        /** A played copy's locator; its key must be a played-copy key. */
        fun played(key: String): SavedRef? = of(SavedShelf.Phone, SavedSource.Played, "", key)

        private fun of(shelf: SavedShelf, source: SavedSource, requestId: String, key: String): SavedRef? {
            if (key.isEmpty() || utf8(key) > SAVED_FIELD_MAX || utf8(requestId) > SAVED_FIELD_MAX) return null
            val valid = when (source) {
                SavedSource.Download -> requestId.isNotEmpty() && !key.startsWith(PLAYED_PREFIX)
                // Played copies live only in the phone's cache, never in an index.
                SavedSource.Played -> shelf == SavedShelf.Phone && requestId.isEmpty() && key.startsWith(PLAYED_PREFIX)
            }
            return if (valid) SavedRef(shelf, source, requestId, key) else null
        }

        /** Strict: version 1 only, bounded, and exactly as Muon writes it; anything else is null. */
        fun parse(handle: String?): SavedRef? {
            if (handle == null || handle.length > SAVED_HANDLE_MAX) return null
            val parts = handle.split(':')
            if (parts.size != 6 || parts[0] != SAVED_SCHEME || parts[1] != SAVED_VERSION) return null
            val shelf = SavedShelf.entries.firstOrNull { it.token == parts[2] } ?: return null
            val source = SavedSource.entries.firstOrNull { it.token == parts[3] } ?: return null
            val requestId = decode(parts[4]) ?: return null
            val key = decode(parts[5]) ?: return null
            // Canonical only: a different spelling of the same fields is not accepted as the same handle.
            return of(shelf, source, requestId, key)?.takeIf { it.handle == handle }
        }

        private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8).size

        private fun encode(text: String): String =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

        private fun decode(text: String): String? = try {
            if (text.length > SAVED_FIELD_MAX * 2) null
            else String(java.util.Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) { null }
    }
}

/** Whether a media ID or URI is a saved copy's handle rather than a live Tauon song. */
internal fun isSavedHandle(text: String?): Boolean = text?.startsWith("$SAVED_SCHEME:") == true

/** How much of a copy's bytes the cache holds now. A snapshot only; nothing is decoded or verified. */
internal enum class SavedCoverage { Full, Partial, Missing, UnknownLength }

/**
 * One saved copy as listed. [song] is its stored tags, for display only, null when they are missing or
 * unreadable; [from] is the address it was saved from, also only for display. [state] is the download
 * row's state, null for a played copy.
 */
internal data class SavedEntry(val ref: SavedRef, val song: TauonTrack?, val from: String?, val state: Int?,
    val coverage: SavedCoverage, val bytes: Long, val ownCover: Boolean,
    /** Whether Remove may delete its bytes now: they are claimed by it alone ([soleOwner], [PlayedClaims]). */
    val removable: Boolean, val stoppedAfterRestart: Boolean = false) {
    /** Whether every byte is held and its download, if any, finished: what can be played. */
    val complete: Boolean get() = coverage == SavedCoverage.Full && (state == null || state == Download.STATE_COMPLETED || stoppedAfterRestart)
    // Old index/cache metadata predates incoming tag limits. Keep its record, but never put unsafe
    // text into Compose/Media3 IPC. Evaluated once while inventory is projected off the main thread.
    val displaySong: TauonTrack? = song?.takeIf { runCatching { requireTrackMetadataBudget(it) }.isSuccess }
    val metadataTooLarge: Boolean get() = song != null && displaySong == null
}

/** The cache key a row's bytes are under: its own key, or Media3's fallback to its address. */
internal fun keyOf(download: Download): String = download.request.customCacheKey ?: download.request.uri.toString()

/**
 * Whether removing [requestId]'s row from a shelf whose index holds [rows] (every row, in every state)
 * deletes only bytes that row alone claims (#213). Media3 removes a row's bytes by its cache key, whoever
 * else names that key, so this requires the row's key to be its own request ID, outside the played-copy
 * namespace, and named by no other row there. Older or unknown rows that alias a key, or use another
 * row's ID or a played-copy key, fail this and are kept.
 *
 * A census is a snapshot. It stays true until the removal runs because Muon has only two writers of
 * index rows, and neither can add another row naming this key: a new save uses a fresh `saved/<uuid>`
 * request ID and key found in no row of either index and no cache ([newSaveId] in [OfflineStore.add]), and a
 * move hands over only a row whose key is its own ID and that no other row on the target names
 * ([movable]), so a second row naming the key would have to carry the same ID, which merges into this one.
 */
internal fun soleOwner(rows: List<Download>, requestId: String): Boolean {
    val row = rows.singleOrNull { it.request.id == requestId } ?: return false
    val key = row.request.customCacheKey ?: return false
    if (key != requestId || key.startsWith(PLAYED_PREFIX)) return false
    return rows.count { keyOf(it) == key } == 1
}

/** Same sole-owner rule for one inventory, without rescanning all rows for every listed copy. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun soleOwners(rows: List<Download>): Set<String> {
    // Include every state and even rows that cannot be listed: they still claim their effective key.
    val ids = rows.groupingBy { it.request.id }.eachCount()
    val keys = rows.groupingBy(::keyOf).eachCount()
    return rows.mapNotNullTo(HashSet()) { row ->
        val id = row.request.id
        val key = row.request.customCacheKey
        id.takeIf { key == id && !id.startsWith(PLAYED_PREFIX) && ids[id] == 1 && keys[id] == 1 }
    }
}

/**
 * Whether a move may hand [download] from a shelf whose index holds [sourceRows] to one whose index holds
 * [targetRows] without making a second row name its key, or rebinding a row already there: it must solely
 * own its key on the source (so its leftover can go once the move completes) and the target may hold no
 * row naming that key, except an exactly equal request (including address and saved metadata).
 */
internal fun movable(download: Download, sourceRows: List<Download>, targetRows: List<Download>): Boolean {
    val id = download.request.id
    if (!soleOwner(sourceRows, id)) return false
    val there = targetRows.filter { keyOf(it) == id || it.request.id == id }
    return there.isEmpty() || (there.size == 1 && there[0].request == download.request)
}

/**
 * The played-copy keys that any row of the phone's index names, read once when the store opens. Media3
 * removes those bytes with their row, so the played cache must never remove them itself: not by eviction,
 * Clear, Remove or a failed copy's cleanup (#213). No Muon writer makes a row with a played-copy key (new
 * saves use `saved/`, a move refuses such a row), so this set can only shrink after it is read, and holding
 * on to it keeps every such key protected. Until it is read, no played copy is removed at all.
 */
internal class PlayedClaims private constructor(@Volatile private var claimed: Set<String>?) {
    constructor() : this(null)

    /** Whether the phone index has been read. */
    val known: Boolean get() = claimed != null

    fun ready(keys: Set<String>) { claimed = keys }

    /** Whether the played cache may remove [key]: the index has been read and no row names it. */
    fun removable(key: String): Boolean = claimed?.let { key !in it } ?: false

    companion object {
        /** For fixtures with no phone index rows naming played keys. */
        fun none() = PlayedClaims(emptySet())

        /** The played-copy keys named by [rows]. */
        fun keysIn(rows: List<Download>): Set<String> = rows.map(::keyOf).filterTo(HashSet()) { it.startsWith(PLAYED_PREFIX) }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun savedCoverage(cache: Cache, key: String): Pair<SavedCoverage, Long> {
    val spans = cache.getCachedSpans(key)
    val bytes = spans.sumOf { it.length }
    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
    val coverage = when {
        spans.isEmpty() -> SavedCoverage.Missing
        length == C.LENGTH_UNSET.toLong() || length <= 0 -> SavedCoverage.UnknownLength
        cache.isCached(key, 0, length) -> SavedCoverage.Full
        else -> SavedCoverage.Partial
    }
    return coverage to bytes
}

/** "http://host:port" from a download's request address or a "origin/track" provenance; display only. */
internal fun savedOrigin(address: String?): String? = address?.let {
    runCatching { ServerEndpoint.parse(Uri.parse(it).let { uri -> "${uri.scheme}://${uri.encodedAuthority}" }).origin }.getOrNull()
}

/**
 * Lists one shelf's download rows and, for the phone, its played copies, without changing them. Rows
 * being removed are left out; unknown or unreadable tags are kept as entries with no song. Played copies
 * are listed only when complete: a partial one is a copy still being made, or one given up on.
 * Reads the index and the cache's in-memory state: call it off the main thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun savedInventory(shelf: SavedShelf, downloads: List<Download>, cache: Cache, played: PlayedClaims?,
    ownsCover: (String) -> Boolean): List<SavedEntry> {
    val entries = ArrayList<SavedEntry>()
    val removableIds = soleOwners(downloads)
    for (download in downloads) {
        if (download.state == Download.STATE_REMOVING) continue
        val request = download.request
        val key = keyOf(download)
        val ref = SavedRef.download(shelf, request.id, key) ?: continue
        val (coverage, bytes) = savedCoverage(cache, key)
        // Only a new save's own cover is shown, and only for the row it was fetched for (see DownloadArt).
        val newSave = request.id.startsWith(NEW_SAVE_PREFIX) && request.customCacheKey == request.id
        entries += SavedEntry(ref, decodeSong(request.data), savedOrigin(request.uri.toString()), download.state,
            coverage, bytes, newSave && ownsCover(request.id), request.id in removableIds,
            stoppedAfterRestart = download.state == Download.STATE_STOPPED && download.stopReason == RETAINED_STOP_REASON)
    }
    // Played copies live only in the phone's cache; [played] is null for any other shelf.
    if (played != null) for (key in cache.keys.sorted()) {
        if (!key.startsWith(PLAYED_PREFIX)) continue
        val ref = SavedRef.played(key) ?: continue
        val (coverage, bytes) = savedCoverage(cache, key)
        if (coverage != SavedCoverage.Full) continue
        val metadata = cache.getContentMetadata(key)
        val from = metadata.get(SAVED_FROM_METADATA, null as String?) ?: key.removePrefix(PLAYED_PREFIX)
        entries += SavedEntry(ref, metadata.get(SONG_METADATA, null as ByteArray?)?.let(::decodeSong),
            savedOrigin(from), null, coverage, bytes, ownCover = false, removable = played.removable(key))
    }
    return entries
}

/** Saved copies in a stable reading order: by title, unknown ones last, then by handle. */
internal fun sortSaved(entries: List<SavedEntry>): List<SavedEntry> = entries.sortedWith(
    compareBy<SavedEntry>({ it.displaySong == null }, { it.displaySong?.title?.lowercase().orEmpty() }, { it.ref.handle }))

/** A fresh request ID and key for a new save that no row and no cached resource already uses. */
internal fun newSaveId(taken: (String) -> Boolean): String {
    repeat(8) {
        val id = NEW_SAVE_PREFIX + UUID.randomUUID()
        if (!taken(id)) return id
    }
    error("No unused saved-copy name found")
}

/** The address an entry-owned cover is drawn from; resolved only from that entry's own file. */
internal const val SAVED_ART_SCHEME = "muon-saved-art"

internal fun savedArtUrl(requestId: String): String = "$SAVED_ART_SCHEME:" +
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(requestId.toByteArray(Charsets.UTF_8))

internal fun savedArtRequest(url: String): String? {
    if (!url.startsWith("$SAVED_ART_SCHEME:") || url.length > SAVED_HANDLE_MAX) return null
    return try {
        String(java.util.Base64.getUrlDecoder().decode(url.removePrefix("$SAVED_ART_SCHEME:")), Charsets.UTF_8)
            .takeIf { savedArtUrl(it) == url }
    } catch (_: IllegalArgumentException) { null }
}

/** The words that mark a saved copy wherever it is shown, so the warning never depends on colour. */
internal const val UNVERIFIED = "Unverified"

internal fun SavedEntry.title(): String = displaySong?.title?.takeIf { it.isNotBlank() }
    ?: if (metadataTooLarge) "Saved song (metadata too large)" else "Unknown saved song"

internal fun SavedEntry.subtitle(): String = listOfNotNull(
    displaySong?.artist?.takeIf { it.isNotBlank() }?.let(::displayCredits),
    UNVERIFIED + " " + if (ref.source == SavedSource.Played) "played copy" else "saved copy",
    if (ref.shelf == SavedShelf.Card) "SD card" else null,
    if (metadataTooLarge) "Metadata too large to display" else null,
).joinToString(" · ")

/**
 * The queue item for a saved copy: its handle as media ID and URI, so the player reads only that copy,
 * from the cache, with no network. No song record travels with it, so it is never copied again as a
 * played song; its normalization is learned under its own handle.
 */
internal fun SavedEntry.mediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(ref.handle)
    .setUri(ref.handle)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title()).setArtist(subtitle())
        .setAlbumTitle(displaySong?.album?.takeIf { it.isNotBlank() })
        .setDurationMs(displaySong?.durationMs?.takeIf { it > 0 })
        .apply { if (ownCover) setArtworkUri(Uri.parse(savedArtUrl(ref.requestId))) }
        .build())
    .build().let(::queueOccurrence)
