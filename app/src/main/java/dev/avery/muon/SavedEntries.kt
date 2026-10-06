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
    val coverage: SavedCoverage, val bytes: Long, val ownCover: Boolean) {
    /** Whether every byte is held and its download, if any, finished: what can be played. */
    val complete: Boolean get() = coverage == SavedCoverage.Full && (state == null || state == Download.STATE_COMPLETED)
    /** Muon's own invariant for a download row: its cache key is its request ID (see [ownedDownload]). */
    val removable: Boolean get() = ref.source == SavedSource.Played || ownedDownload(ref.requestId, ref.key)
}

/**
 * Every download row Muon writes uses its request ID as its cache key (legacy rows "origin/id", new ones
 * "saved/<uuid>"), and request IDs are unique within an index, so no other row on that shelf can claim
 * the key. A row that breaks this, which Muon did not make, is not removed: its bytes may be shared.
 */
internal fun ownedDownload(requestId: String, key: String?): Boolean = key != null && key == requestId

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
internal fun savedInventory(shelf: SavedShelf, downloads: List<Download>, cache: Cache, played: Boolean,
    ownsCover: (String) -> Boolean): List<SavedEntry> {
    val entries = ArrayList<SavedEntry>()
    for (download in downloads) {
        if (download.state == Download.STATE_REMOVING) continue
        val request = download.request
        // Media3 falls back to the address when a request has no key; Muon's own requests always have one.
        val key = request.customCacheKey ?: request.uri.toString()
        val ref = SavedRef.download(shelf, request.id, key) ?: continue
        val (coverage, bytes) = savedCoverage(cache, key)
        entries += SavedEntry(ref, decodeSong(request.data), savedOrigin(request.uri.toString()), download.state,
            coverage, bytes, ownsCover(request.id))
    }
    if (played) for (key in cache.keys.sorted()) {
        if (!key.startsWith(PLAYED_PREFIX)) continue
        val ref = SavedRef.played(key) ?: continue
        val (coverage, bytes) = savedCoverage(cache, key)
        if (coverage != SavedCoverage.Full) continue
        val metadata = cache.getContentMetadata(key)
        val from = metadata.get(SAVED_FROM_METADATA, null as String?) ?: key.removePrefix(PLAYED_PREFIX)
        entries += SavedEntry(ref, metadata.get(SONG_METADATA, null as ByteArray?)?.let(::decodeSong),
            savedOrigin(from), null, coverage, bytes, ownCover = false)
    }
    return entries
}

/** Saved copies in a stable reading order: by title, unknown ones last, then by handle. */
internal fun sortSaved(entries: List<SavedEntry>): List<SavedEntry> = entries.sortedWith(
    compareBy<SavedEntry>({ it.song == null }, { it.song?.title?.lowercase().orEmpty() }, { it.ref.handle }))

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

internal fun SavedEntry.title(): String = song?.title?.takeIf { it.isNotBlank() } ?: "Unknown saved song"

internal fun SavedEntry.subtitle(): String = listOfNotNull(
    song?.artist?.takeIf { it.isNotBlank() }?.let(::displayCredits),
    UNVERIFIED + " " + if (ref.source == SavedSource.Played) "played copy" else "saved copy",
    if (ref.shelf == SavedShelf.Card) "SD card" else null,
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
        .setAlbumTitle(song?.album?.takeIf { it.isNotBlank() })
        .setDurationMs(song?.durationMs?.takeIf { it > 0 })
        .apply { if (ownCover) setArtworkUri(Uri.parse(savedArtUrl(ref.requestId))) }
        .build())
    .build().let(::queueOccurrence)
