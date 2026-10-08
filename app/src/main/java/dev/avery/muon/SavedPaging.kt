package dev.avery.muon

import android.content.Context
import java.io.IOException

internal data class SavedPage(val snapshot: SavedCatalogSnapshot, val offset: Long, val entries: List<SavedEntry>)
internal data class SavedReload(val page: SavedPage, val playable: Long, val origin: String?)
internal sealed interface SavedPlaybackResult {
    data class Ready(val plan: SavedQueuePlan) : SavedPlaybackResult
    data class TooLarge(val entry: SavedEntry) : SavedPlaybackResult
}

/** Hydrated display pages only: four × fifty rows, not a ceiling on the saved library. */
internal class SavedPageCache(private val capacity: Int = 4) {
    init { require(capacity > 0) }
    private val pages = LinkedHashMap<Long, SavedPage>(capacity, .75f, true)
    private var snapshot: SavedCatalogSnapshot? = null
    val retainedRows: Int get() = pages.values.sumOf { it.entries.size }
    fun clear() { pages.clear(); snapshot = null }
    fun get(expected: SavedCatalogSnapshot, offset: Long): SavedPage? =
        pages[offset]?.takeIf { snapshot == expected }
    fun put(page: SavedPage) {
        require(page.entries.size <= SAVED_PAGE_SIZE)
        if (snapshot != page.snapshot) { clear(); snapshot = page.snapshot }
        pages[page.offset] = page
        while (pages.size > capacity) pages.remove(pages.keys.first())
    }
}

internal fun savedPageOffset(requested: Long, count: Long): Long {
    require(requested >= 0 && count >= 0)
    val last = if (count == 0L) 0L else ((count - 1) / SAVED_PAGE_SIZE) * SAVED_PAGE_SIZE
    return minOf((requested / SAVED_PAGE_SIZE) * SAVED_PAGE_SIZE, last)
}

/** Worker-confined repository. Catalog connections have operation-scoped lifetimes; no UI IO.
 * Full raw rows/owner and cache-name snapshots still have their existing separate #253 costs.
 */
internal class SavedPaging(private val context: Context,
    private val project: ((SavedRef) -> Boolean, () -> Unit, (SavedEntry) -> Unit) -> Unit = { include, check, emit ->
        OfflineStore.projectSavedEntries(context, include, check, emit)
    }) {
    fun reload(offset: Long, checkpoint: () -> Unit): SavedReload = SavedCatalog.open(context).use { catalog ->
        var playable = 0L
        // Preserve existing most-common known origin choice; this compact origin census is not a
        // whole-process bound and remains part of the independent cardinality workstream.
        val origins = HashMap<String, Long>()
        val snapshot = catalog.rebuildFrom({ emit -> project({ true }, checkpoint) { entry ->
            checkpoint()
            if (entry.complete) playable++
            entry.from?.let { origins[it] = (origins[it] ?: 0) + 1 }
            emit(entry)
        } }, { checkpoint(); false })
        val page = hydrate(catalog, snapshot, savedPageOffset(offset, snapshot.count), checkpoint)
        val maximum = origins.values.maxOrNull()
        val candidates = origins.filterValues { it == maximum }.keys
        // Old savedLibrary chose the first known origin in sorted display order on a count tie.
        // Resolve ties in bounded pages rather than letting HashMap/native-index order choose it.
        var origin = candidates.singleOrNull()
        if (candidates.size > 1) {
            var next = 0L
            while (origin == null && next < snapshot.count) {
                val read = if (next == page.offset) page else hydrate(catalog, snapshot, next, checkpoint)
                origin = read.entries.firstOrNull { it.from in candidates }?.from
                if (read.entries.isEmpty()) throw SavedCatalogStale()
                next += read.entries.size
            }
        }
        SavedReload(page, playable, origin)
    }

    fun page(expected: SavedCatalogSnapshot, offset: Long, checkpoint: () -> Unit): SavedPage =
        SavedCatalog.open(context).use { hydrate(it, expected, savedPageOffset(offset, expected.count), checkpoint) }

    private fun hydrate(catalog: SavedCatalog, expected: SavedCatalogSnapshot, offset: Long,
        checkpoint: () -> Unit): SavedPage {
        checkpoint()
        val refs = catalog.page(expected, offset)
        val wanted = refs.toHashSet()
        val entries = HashMap<SavedRef, SavedEntry>(refs.size)
        project({ it in wanted }, checkpoint) { entry ->
            checkpoint()
            if (entries.put(entry.ref, entry) != null) throw SavedCatalogStale()
        }
        checkpoint()
        if (catalog.snapshot() != expected) throw SavedCatalogStale()
        // Never substitute a rebound key or silently drop missing/unavailable copies from a page.
        return SavedPage(expected, offset, refs.map { entries[it] ?: throw SavedCatalogStale() })
    }

    fun playback(expected: SavedCatalogSnapshot, selected: SavedRef, single: Boolean,
        checkpoint: () -> Unit): SavedPlaybackResult = SavedCatalog.open(context).use { catalog ->
        checkpoint()
        if (catalog.snapshot() != expected) throw SavedCatalogStale()
        if (single) {
            var entry: SavedEntry? = null
            project({ it == selected }, checkpoint) {
                if (entry != null) throw SavedCatalogStale()
                entry = it
            }
            checkpoint()
            if (catalog.snapshot() != expected) throw SavedCatalogStale()
            val plan = entry?.let { prepareSavedQueue(listOf(it), selected) } ?: throw SavedCatalogStale()
            return@use SavedPlaybackResult.Ready(plan)
        }
        val ordered = sequence {
            var offset = 0L
            while (offset < expected.count) {
                val page = hydrate(catalog, expected, offset, checkpoint)
                for (entry in page.entries) { checkpoint(); yield(entry) }
                offset += page.entries.size
                if (page.entries.isEmpty()) throw IOException("Saved catalog page is missing; refresh your copies")
            }
        }
        try {
            val plan = prepareSavedQueue(ordered.asIterable(), selected) ?: throw SavedCatalogStale()
            checkpoint()
            if (catalog.snapshot() != expected) throw SavedCatalogStale()
            SavedPlaybackResult.Ready(plan)
        } catch (_: SavedQueueLimit) {
            // Explicit offer must refer to a current playable copy, not a stale clicked row.
            var entry: SavedEntry? = null
            project({ it == selected }, checkpoint) { if (entry != null) throw SavedCatalogStale(); entry = it }
            checkpoint()
            if (catalog.snapshot() != expected) throw SavedCatalogStale()
            SavedPlaybackResult.TooLarge(entry?.takeIf { it.complete } ?: throw SavedCatalogStale())
        }
    }
}
