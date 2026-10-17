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
    val preparation=SavedQueuePreparation(limits)
    var order=0L
    for(entry in entries) {
        preparation.add(entry,order)
        if(order==Long.MAX_VALUE) throw SavedQueueLimit()
        order++
    }
    return preparation.build(selected)
}

/** Incremental admission, not incremental player publication. No MediaItems before the entire
 * accepted stream passes count/text checks. At most the existing queue budget is retained, with
 * one scalar ordering position per entry; native projection order need not equal catalog order. */
internal class SavedQueuePreparation(private val limits:SavedQueueLimits=SavedQueueLimits()) {
    init {require(limits.items>0 && limits.textBytes>=0)}
    private data class Kept(val order:Long,val entry:SavedEntry)
    private val kept=ArrayList<Kept>()
    private var left=limits.textBytes
    fun add(entry:SavedEntry,order:Long) {
        require(order>=0)
        if(!entry.complete) return
        if(kept.size>=limits.items) throw SavedQueueLimit()
        // ID and URI carry the locator twice; occurrence UUID is36 ASCII bytes. Other player,
        // field/key/Bundle/source overhead is not estimated here; cardinality is bounded.
        val fields=listOf(entry.ref.handle,entry.ref.handle,entry.title(),entry.subtitle(),
            entry.displaySong?.album?.takeIf {it.isNotBlank()}.orEmpty(),
            if(entry.ownCover) savedArtUrl(entry.ref.requestId) else "")
        val characters=fields.sumOf {it.length.toLong()}+36L
        if(characters>left) throw SavedQueueLimit()
        val bytes=fields.sumOf {it.toByteArray(Charsets.UTF_8).size.toLong()}+36L
        if(bytes>left) throw SavedQueueLimit()
        left-=bytes;kept+=Kept(order,entry)
    }
    fun build(selected:SavedRef):SavedQueuePlan? {
        kept.sortBy {it.order}
        val start=kept.indexOfFirst {it.entry.ref==selected}
        if(start<0) return null
        return SavedQueuePlan(kept.map {it.entry.mediaItem()},start)
    }
}
