package dev.avery.muon

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Request

/**
 * Where downloads are kept: the phone's own storage, or a removable SD card (#112, mockup 01). Each has
 * its own cache, its own download index and its own service. A card that is unavailable is skipped by
 * new decisions (#179 S1, [ShelfState]); whether its downloads survive removal is not established.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class Shelf(val cache: SimpleCache, val manager: DownloadManager, val service: Class<out DownloadService>,
    private val present: () -> Boolean = { true }) : ShelfState {
    /** Reads this shelf's copies; anything not on it streams, and nothing streamed is written. */
    val source: CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Transport.client)).setCacheWriteDataSinkFactory(null)

    /** Reads one saved copy and nothing else (#213): no upstream and no sink, so a missing byte fails. */
    val savedSource: CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)

    /** Checked at each decision (#179 S1); see [cardPresent] for the card. */
    override fun available(): Boolean = runCatching(present).getOrDefault(false)

    override fun completed(id: String): Boolean =
        runCatching { manager.downloadIndex.getDownload(id)?.state == Download.STATE_COMPLETED }.getOrDefault(false)

    override fun holds(id: String): Boolean =
        runCatching { manager.downloadIndex.getDownload(id) != null }.getOrDefault(false)
}

/** A mounted removable SD card's folder for Muon, if there is one. Muon needs no permission for it. */
internal fun cardFolder(context: Context): File? = context.getExternalFilesDirs(null).drop(1).firstOrNull {
    it != null && runCatching { Environment.isExternalStorageRemovable(it) &&
        Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }.getOrDefault(false)
}

/**
 * The downloaded songs and the machinery that keeps them (#112): a Media3 [DownloadManager] over a
 * [SimpleCache] in the app's own storage, and another on the SD card when one is in, shared by the UI
 * and the playback service in this process. Created on the main thread, where the managers report.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object OfflineStore {
    class Store(val phone: Shelf, val art: DownloadArt, val played: PlayedSongEvictor,
        val prefs: android.content.SharedPreferences, val database: StandaloneDatabaseProvider,
        val record: (Download) -> Unit, val removed: (Download) -> Unit,
        /** Where a new save's own cover is fetched; fixtures leave it doing nothing. */
        val artwork: java.util.concurrent.Executor = java.util.concurrent.Executor { }) {
        /** The phone's cache, which also holds the played-song copies. */
        val cache: SimpleCache get() = phone.cache
        @Volatile var card: Shelf? = null
        /** The app's folder on [card], for its name and free space; null without a card. */
        @Volatile var cardFolder: File? = null
        val shelves: List<Shelf> get() = listOfNotNull(phone, card)
    }

    /** Set while the library shows only what is on the phone; the player then also plays cached songs. */
    @Volatile var offline = false

    /** Copies played songs one at a time, behind playback. */
    private val copier = Executors.newSingleThreadExecutor()
    private val copyDeadlines = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val playedWork = PlayedCopyWork(copier, copyDeadlines)

    @Volatile private var store: Store? = null

    /** The store, if something has already made it; never makes one. */
    fun current(): Store? = store

    @Synchronized
    fun get(context: Context): Store = store ?: create(context.applicationContext).also { store = it }

    private fun shelf(context: Context, folder: File, evictor: androidx.media3.datasource.cache.CacheEvictor,
        database: StandaloneDatabaseProvider, index: String, service: Class<out DownloadService>,
        present: () -> Boolean = { true }): Shelf {
        val cache = SimpleCache(folder, evictor, database)
        val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Transport.client))
        val manager = DownloadManager(context, DefaultDownloadIndex(database, index),
            DefaultDownloaderFactory(factory, Executors.newFixedThreadPool(2)))
        manager.maxParallelDownloads = 2
        return Shelf(cache, manager, service, present)
    }

    private fun create(context: Context): Store {
        val database = StandaloneDatabaseProvider(context)
        val prefs = context.getSharedPreferences("storage", Context.MODE_PRIVATE)
        val limit = prefs.getLong("cacheLimit", DEFAULT_CACHE_LIMIT)
        val main = Handler(Looper.getMainLooper())
        PlayedCacheState.limit = limit
        // Downloads stay until removed: only played-song copies are ever evicted, oldest first.
        val played = PlayedSongEvictor(limit) { used -> main.post { PlayedCacheState.used = used } }
        val phone = shelf(context, File(context.filesDir, "downloads"), played, database, "", MuonDownloadService::class.java)
        val art = DownloadArt(File(context.filesDir, "downloads-art"))
        // Covers are fetched one at a time, beside the downloads rather than in their way.
        val artwork = Executors.newSingleThreadExecutor()
        val sizes = DownloadByteTotals()
        fun record(download: Download) {
            val id = download.request.id
            val mark = when (download.state) {
                Download.STATE_COMPLETED -> DownloadMark.Done
                Download.STATE_QUEUED, Download.STATE_RESTARTING -> DownloadMark.Queued
                Download.STATE_DOWNLOADING -> DownloadMark.Downloading
                else -> null
            }
            // While a song moves between shelves, one shelf is still queuing or removing it while the
            // other holds it complete: it stays downloaded throughout.
            if (mark != DownloadMark.Done && listOfNotNull(phone, store?.card).any { it.completed(id) }) return
            if (mark == null) DownloadMarks.marks.remove(id) else DownloadMarks.marks[id] = mark
            if (mark == DownloadMark.Done) sizes.put(id, download.bytesDownloaded) else sizes.remove(id)
            // No cover is fetched here for an older download (#213): one fetched now by its track number
            // could be another song's. A new save fetches its own when it is asked for (see [add]).
            DownloadMarks.bytes = sizes.total
        }
        fun removed(download: Download) {
            // Moved rather than removed: the song is still kept, on the other shelf.
            if (listOfNotNull(phone, store?.card).any { it.completed(download.request.id) }) return
            // Only a cover this entry owns goes with it. An older download's cover was kept per track
            // number and may belong to other copies, so it is left where it is.
            artwork.execute { art.removeEntry(download.request.id) }
            DownloadMarks.marks.remove(download.request.id)
            sizes.remove(download.request.id)
            DownloadMarks.bytes = sizes.total
        }
        val made = Store(phone, art, played, prefs, database, ::record, ::removed, artwork)
        watch(context, phone, made, main)
        cardFolder(context)?.let { folder ->
            // The card found now; later its availability is only ever this folder's, never another card's.
            val downloads = File(folder, "downloads")
            runCatching {
                // The app's own folder on the card, made by getExternalFilesDirs above. Not the cache
                // folder, which the cache creates on its own thread a moment later.
                shelf(context, downloads, NoOpCacheEvictor(), database, "card", MuonCardDownloadService::class.java) {
                    cardPresent(folder)
                }
            }.getOrNull()?.let { card -> made.card = card; made.cardFolder = folder; watch(context, card, made, main) }
        }
        return made
    }

    /**
     * Reports one shelf's downloads to the UI: those already there, then every change. A song is kept on
     * one shelf only, so once it finishes on this one, a copy left on the other (a move) is removed.
     */
    private fun watch(context: Context, shelf: Shelf, store: Store, main: Handler) {
        // Live callbacks and publication run on the application/main looper. An index snapshot can
        // already be obsolete when posted; retain newer events until that one bootstrap finishes.
        var changed: MutableSet<String>? = HashSet()
        shelf.manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(m: DownloadManager, download: Download, finalException: Exception?) {
                val id = download.request.id
                changed?.add(id)
                store.record(download)
                if (download.state != Download.STATE_COMPLETED) return
                // An unavailable card keeps its copy (#179 S1): removing it would act on missing files.
                leftoverCopies(shelf, store.shelves, id)
                    .forEach { DownloadService.sendRemoveDownload(context, it.service, id, false) }
            }
            override fun onDownloadRemoved(m: DownloadManager, download: Download) {
                changed?.add(download.request.id)
                store.removed(download)
            }
        })
        // One-shot: shut down once the scan is submitted. An orderly shutdown still lets it run and post.
        val bootstrap = Executors.newSingleThreadExecutor()
        try {
            bootstrap.execute {
                val known = ArrayList<Download>()
                runCatching { shelf.manager.downloadIndex.getDownloads().use { while (it.moveToNext()) known += it.download } }
                main.post {
                    try { known.filterNot { it.request.id in changed.orEmpty() }.forEach(store.record) }
                    finally { changed = null } // No lifetime-long tombstones for removed/changed songs.
                }
            }
        } finally { bootstrap.shutdown() }
    }

    /** Where new downloads go: the card when chosen and available, the phone when the card isn't chosen. */
    private fun target(store: Store): DownloadTarget = downloadTarget(store.card, store.prefs.getBoolean("onCard", false))

    /** Whether the card Muon opened with is there now; false with none (#179 S1). */
    fun cardAvailable(context: Context): Boolean = get(context).card?.available() == true

    /** Says why a request was refused, rather than leaving it to look as if nothing happened. */
    private fun notice(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() }
    }

    /** Whether new downloads go to the SD card; only offered while one is in. */
    fun storeOnCard(context: Context): Boolean = get(context).prefs.getBoolean("onCard", false)

    fun setStoreOnCard(context: Context, onCard: Boolean) { get(context).prefs.edit().putBoolean("onCard", onCard).apply() }

    /**
     * The player's data source: a live song streams, and nothing streamed is written; a saved copy plays
     * from its own shelf with no network at all (#213, [routeOfflineRequest]).
     */
    @Suppress("UNUSED_PARAMETER")
    fun playbackSource(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { OfflineDataSource { spec -> get(context).let { routeOfflineRequest(spec, it.phone, it.card) } } }

    /**
     * Keeps an Opus copy of a live song that has started playing, as a new played copy under its own
     * fresh key (#213), unless a complete one saved from the same address with the same details is
     * already kept. An older copy under the song's number is never resumed or reused. Runs behind
     * playback; a copy that does not finish is removed, so nothing partial is left behind under its key.
     */
    fun copyPlayed(context: Context, id: String, song: ByteArray?) {
        if (song == null || isSavedHandle(id)) return
        val store = get(context)
        playedWork.copy(id) { owner ->
            runCatching {
                if (owner.isCancelled || hasPlayedCopyFrom(store.cache, id, song)) return@runCatching
                val call = AtomicReference<Call?>()
                val writer = AtomicReference<CacheWriter?>()
                owner.onCancel { writer.get()?.cancel(); call.get()?.cancel() }
                val origin = id.substringBeforeLast('/')
                val number = id.substringAfterLast('/')
                val url = ServerEndpoint.parse(origin).url("/api1/fileopus/$number")
                val key = playedKey(newSaveId { candidate ->
                    playedKey(candidate) in store.cache.keys
                })
                store.cache.applyContentMetadataMutations(key, ContentMetadataMutations()
                    .set(SONG_METADATA, song).set(SAVED_FROM_METADATA, id))
                val upstream = OkHttpDataSource.Factory(object : Call.Factory {
                    override fun newCall(request: Request): Call {
                        val made = Transport.client.newCall(request)
                        call.set(made)
                        if (owner.isCancelled) made.cancel()
                        return made
                    }
                })
                val source = CacheDataSource.Factory().setCache(store.cache)
                    .setUpstreamDataSourceFactory(upstream).createDataSourceForDownloading()
                try {
                    copyPlayedWithinLimit(source, DataSpec.Builder().setUri(Uri.parse(url)).setKey(key).build(),
                        onWriterCreated = { copy ->
                            writer.set(copy)
                            if (owner.isCancelled) copy.cancel()
                        }) { store.prefs.getLong("cacheLimit", DEFAULT_CACHE_LIMIT) }
                } catch (failure: Throwable) {
                    // The key was made for this copy alone, on this worker, so nothing else owns it.
                    runCatching { store.cache.removeResource(key) }
                    throw failure
                }
            }
        }
    }

    /** Whether a complete new-style played copy saved from [id] with these same details is kept. */
    private fun hasPlayedCopyFrom(cache: androidx.media3.datasource.cache.Cache, id: String, song: ByteArray): Boolean =
        runCatching {
            cache.keys.any { key ->
                if (!key.startsWith(playedKey(NEW_SAVE_PREFIX))) return@any false
                val metadata = cache.getContentMetadata(key)
                metadata.get(SAVED_FROM_METADATA, null as String?) == id &&
                    metadata.get(SONG_METADATA, null as ByteArray?)?.contentEquals(song) == true &&
                    savedCoverage(cache, key).first == SavedCoverage.Full
            }
        }.getOrDefault(false)

    /** A new cache limit, kept for next time; lowering it makes room at once. */
    fun setCacheLimit(context: Context, limit: Long) {
        val store = get(context)
        store.prefs.edit().putLong("cacheLimit", limit).apply()
        PlayedCacheState.limit = limit
        playedWork.resize { store.played.resize(limit) }
    }

    /** Empties the played-song cache; downloads stay. */
    fun clearPlayed(context: Context) {
        val cache = get(context).cache
        playedWork.clear { runCatching { cache.keys.filter { it.startsWith(PLAYED_PREFIX) }.forEach(cache::removeResource) } }
    }

    /** Runs saved-copy requests and their checks off the main thread, one at a time. */
    private val saver = Executors.newSingleThreadExecutor()

    /**
     * Saves a new copy of each of these live songs (#213). Every copy is a new, independent entry with its
     * own request ID and cache key, never one already used by a row or a cached resource on that shelf, so
     * an older copy of the same track number is neither replaced nor merged into, and its bytes stay. Its
     * cover is fetched now, from the address it is saved from, and kept as that entry's own. With the card
     * chosen but unavailable, nothing is queued anywhere, and the refusal is said (#179 S1).
     */
    fun add(context: Context, endpoint: ServerEndpoint, tracks: List<TauonTrack>) {
        val store = get(context)
        val shelf = when (target(store)) {
            DownloadTarget.Phone -> store.phone
            DownloadTarget.Card -> store.card ?: return
            DownloadTarget.CardUnavailable -> {
                notice(context, "The SD card isn't available, so nothing was saved. Try again when it's back, " +
                    "or turn off Store on SD card in Settings to save to the phone.")
                return
            }
        }
        val playable = tracks.filter { it.playable }
        if (playable.isEmpty()) return
        saver.execute {
            val requests = runCatching { playable.map { track -> newSaveRequest(shelf, endpoint, track) } }.getOrNull()
            if (requests == null) {
                notice(context, "Muon couldn't save these copies. Nothing was changed.")
                return@execute
            }
            requests.forEach { (request, cover) ->
                DownloadService.sendAddDownload(context, shelf.service, request, false)
                store.artwork.execute { store.art.fetchEntry(request.id, cover) }
            }
            notice(context, if (requests.size == 1) "Saving a copy. It's under Saved copies."
                else "Saving ${requests.size} copies. They're under Saved copies.")
        }
    }

    /** A new save's request, under a fresh name unused on [shelf], and the cover address it is saved from. */
    internal fun newSaveRequest(shelf: Shelf, endpoint: ServerEndpoint, track: TauonTrack): Pair<DownloadRequest, String> {
        val id = newSaveId { candidate ->
            shelf.manager.downloadIndex.getDownload(candidate) != null || shelf.cache.getCachedSpans(candidate).isNotEmpty() ||
                ContentMetadata.getContentLength(shelf.cache.getContentMetadata(candidate)) != C.LENGTH_UNSET.toLong()
        }
        val request = DownloadRequest.Builder(id, Uri.parse(endpoint.url("/api1/fileopus/${track.id}")))
            .setCustomCacheKey(id).setData(encodeSong(track)).build()
        return request to endpoint.url("/api1/pic/medium/${track.id}")
    }

    /**
     * Every saved copy (#213): the download rows on the phone and on the card while it is available, and
     * the complete played copies, each as its own entry however many share a track number or tags.
     * Unknown or unreadable tags are kept, not dropped. An unavailable card's copies are not listed: they
     * could not play from it (#179 S1). Reads the indexes: call it off the main thread.
     */
    fun savedEntries(context: Context): List<SavedEntry> {
        val store = get(context)
        val entries = ArrayList<SavedEntry>()
        val shelves = listOfNotNull(SavedShelf.Phone to store.phone,
            store.card?.takeIf { it.available() }?.let { SavedShelf.Card to it })
        for ((name, shelf) in shelves) runCatching {
            val rows = ArrayList<Download>()
            shelf.manager.downloadIndex.getDownloads().use { while (it.moveToNext()) rows += it.download }
            entries += savedInventory(name, rows, shelf.cache, played = name == SavedShelf.Phone) { store.art.hasEntry(it) }
        }
        return sortSaved(entries)
    }

    /**
     * The server most saved copies came from, and every saved copy: what "Listen offline" opens when there
     * is no saved server, as after Disconnect. Null with nothing playable. Off the main thread.
     */
    fun savedLibrary(context: Context): Pair<String, List<SavedEntry>>? {
        val entries = savedEntries(context)
        if (entries.none { it.complete }) return null
        val origin = entries.mapNotNull { it.from }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        return origin to entries
    }

    /**
     * Whether [ref] names a saved copy that is here now: a row on its shelf whose key is the one named, or
     * a played copy in the phone's cache. For the session's admission check; reads one index row, so it
     * is called off the main thread. Existence only: it says nothing about what the audio is.
     */
    fun admitsSaved(context: Context, ref: SavedRef): Boolean = runCatching {
        val store = get(context)
        when (ref.source) {
            SavedSource.Played -> ref.key in store.cache.keys
            SavedSource.Download -> {
                val shelf = (if (ref.shelf == SavedShelf.Card) store.card else store.phone) ?: return@runCatching false
                val download = shelf.manager.downloadIndex.getDownload(ref.requestId) ?: return@runCatching false
                download.state != Download.STATE_REMOVING &&
                    (download.request.customCacheKey ?: download.request.uri.toString()) == ref.key
            }
        }
    }.getOrDefault(false)

    /**
     * Removes one saved copy, and only it (#213). A download row goes only when it is on its own shelf,
     * that shelf is available, and its key is its own request ID, which no other row there can claim
     * ([ownedDownload]); otherwise nothing is changed, the copy still plays, and the refusal is said.
     * A played copy is one cached resource no row claims; each new one is written under its own fresh key,
     * so removing a listed, complete one cannot cut into a copy being made. These checks are snapshots,
     * not a lock: they rely on every Muon writer keeping those rules.
     */
    fun removeSaved(context: Context, ref: SavedRef) {
        saver.execute {
            when (removeSavedNow(context, ref)) {
                SavedRemoval.Sent -> Unit
                SavedRemoval.Unavailable ->
                    notice(context, "That copy is on the SD card, which isn't available, so it wasn't removed.")
                SavedRemoval.NotOwned ->
                    notice(context, "Muon can't tell that copy's bytes belong to it alone, so it was kept.")
            }
        }
    }

    internal enum class SavedRemoval { Sent, Unavailable, NotOwned }

    /** [removeSaved]'s checks and command, on the calling thread; reads one index row. */
    internal fun removeSavedNow(context: Context, ref: SavedRef): SavedRemoval {
        val store = get(context)
        if (ref.source == SavedSource.Played) {
            runCatching { store.cache.removeResource(ref.key) }
            return SavedRemoval.Sent
        }
        val shelf = (if (ref.shelf == SavedShelf.Card) store.card else store.phone)
            ?.takeIf { it.available() } ?: return SavedRemoval.Unavailable
        val download = runCatching { shelf.manager.downloadIndex.getDownload(ref.requestId) }.getOrNull()
        val key = download?.request?.customCacheKey
        if (download == null || key != ref.key || !ownedDownload(ref.requestId, key)) return SavedRemoval.NotOwned
        // Its own move, if one is under way, loses its hand-over too (#234).
        moveOwnership.remove(listOf(ref.requestId))
        DownloadService.sendRemoveDownload(context, shelf.service, ref.requestId, false)
        return SavedRemoval.Sent
    }

    /**
     * Carries on with downloads left queued when Muon last closed. The service stops itself again
     * once there is nothing to do.
     */
    fun resume(context: Context) {
        // An unavailable card's service is not started here, so its queued downloads are not resumed onto
        // missing storage. A card service already running, or restarted by the system, is not covered.
        availableShelves(get(context).shelves).forEach { runCatching { DownloadService.start(context, it.service) } }
    }

    /** Moves one download at a time, behind everything else. */
    private val mover = Executors.newSingleThreadExecutor()
    private val moveOwnership = DownloadMoveOwnership()

    /**
     * How many finished downloads are on the card ([card]) or the phone. Reads the index. Null for a
     * card that is missing or unavailable, which is not the same as one with nothing on it (#179 S1).
     */
    fun downloadsOn(context: Context, card: Boolean): Int? {
        val store = get(context)
        val shelf = (if (card) store.card else store.phone) ?: return null
        if (!shelf.available()) return null
        return runCatching { shelf.manager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { it.count } }.getOrDefault(0)
    }

    /**
     * Moves every finished download to the card ([toCard]) or back to the phone. Each song's copy is
     * written from one shelf's cache into the other's on the phone itself, with no Tauon, then handed to
     * that shelf's manager, which finds it already complete; the original goes once it is recorded there
     * (see [watch]). A song that fails to copy stays where it was.
     */
    fun move(context: Context, toCard: Boolean) {
        val store = get(context)
        val card = store.card
        val from = if (toCard) store.phone else card
        val to = if (toCard) card else store.phone
        // #179 S1: not started without an available card, stopped before the next song if either shelf
        // goes, and a copy is not handed over if either went before the hand-over ran. These are
        // snapshots: a card that goes during one song's copy is not covered.
        if (from == null || to == null || !canMove(from, to)) {
            notice(context, "The SD card isn't available, so nothing was moved.")
            return
        }
        val main = Handler(Looper.getMainLooper())
        val batch = moveOwnership.begin()
        mover.execute {
            try {
                val downloads = ArrayList<Download>()
                runCatching { from.manager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { while (it.moveToNext()) downloads += it.download } }
                main.post { DownloadMarks.moving = 0 to downloads.size }
                for ((index, download) in downloads.withIndex()) {
                    if (!canMove(from, to)) {
                        notice(context, "The SD card isn't available any more, so the rest weren't moved.")
                        break
                    }
                    if (moveOwnership.permits(batch, download.request.id) &&
                        runCatching { copy(download, from, to) }.isSuccess)
                        // Hand-over needs both shelves still available (#179 S1) and the move still owning
                        // this song: removed or Remove all since means no Add (#234).
                        main.post {
                            deliverMovedCopy(from, to) {
                                moveOwnership.publish(batch, download.request.id) {
                                    DownloadService.sendAddDownload(context, to.service, download.request, false)
                                }
                            }
                        }
                    main.post { DownloadMarks.moving = index + 1 to downloads.size }
                }
            } finally {
                // Posted after every completion: callbacks still carry ownership until they drain.
                main.post { moveOwnership.finish(batch); DownloadMarks.moving = null }
            }
        }
    }

    /** Writes one finished download's bytes from [from]'s cache into [to]'s, under the same key. */
    private fun copy(download: Download, from: Shelf, to: Shelf) {
        val id = download.request.id
        val length = ContentMetadata.getContentLength(from.cache.getContentMetadata(id))
        require(length != C.LENGTH_UNSET.toLong() && from.cache.isCached(id, 0, length)) { "Not fully downloaded" }
        // CacheWriter fills holes around existing target spans. Refuse a known mismatch before that
        // first write, rather than extending an unrelated partial copy and rejecting it afterwards.
        // Snapshot only: late writers are still checked by the full post-copy comparison below.
        for (span in to.cache.getCachedSpans(id)) {
            require(span.position <= length && span.length <= length - span.position) {
                "Existing destination bytes extend beyond the source"
            }
            require(sameBytes(DataSpec.Builder().setUri(download.request.uri).setKey(id)
                .setPosition(span.position).setLength(span.length).build(), from, to)) {
                "Existing destination bytes differ from the source"
            }
        }
        // No upstream: a byte missing from the source fails the copy rather than reaching the network.
        val reader = CacheDataSource.Factory().setCache(from.cache).setCacheWriteDataSinkFactory(null)
        val writer = CacheDataSource.Factory().setCache(to.cache).setUpstreamDataSourceFactory(reader).createDataSourceForDownloading()
        CacheWriter(writer, DataSpec.Builder().setUri(download.request.uri).setKey(id).build(), null, null).cache()
        // CacheWriter keeps whatever the target already held for this key, so only an exact copy counts (#230).
        require(sameBytes(DataSpec.Builder().setUri(download.request.uri).setKey(id).setLength(length).build(), from, to)) {
            "Copy differs from its source"
        }
        to.cache.applyContentMetadataMutations(id, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), length))
    }

    /**
     * Whether [to] holds exactly [from]'s bytes for [spec], read now from both caches in bounded blocks.
     * Neither reader has an upstream or a sink: a missing or locked byte fails, and no replacement audio is fetched or
     * written. Cache reads may still touch metadata or reconcile stale spans. A check at this moment only, not a guarantee against later changes.
     */
    private fun sameBytes(spec: DataSpec, from: Shelf, to: Shelf): Boolean {
        val source = CacheDataSource.Factory().setCache(from.cache).createDataSource()
        val target = CacheDataSource.Factory().setCache(to.cache).createDataSource()
        return try {
            source.open(spec)
            target.open(spec)
            val expected = ByteArray(64 * 1024)
            val actual = ByteArray(expected.size)
            var left = spec.length
            while (left > 0) {
                val count = minOf(left, expected.size.toLong()).toInt()
                if (!readFully(source, expected, count) || !readFully(target, actual, count)) return false
                for (i in 0 until count) if (expected[i] != actual[i]) return false
                left -= count
            }
            true
        } finally {
            runCatching { source.close() }
            runCatching { target.close() }
        }
    }

    private fun readFully(source: DataSource, buffer: ByteArray, count: Int): Boolean {
        var done = 0
        while (done < count) {
            val read = source.read(buffer, done, count - done)
            if (read <= 0) return false
            done += read
        }
        return true
    }

    /** Removes every download from the available shelves; an unavailable card keeps its own (#179 S1). */
    fun removeAll(context: Context) {
        // Every move in flight loses its publication, whichever shelves receive the command (#234).
        moveOwnership.removeAll()
        val shelves = get(context).shelves
        availableShelves(shelves).forEach { DownloadService.sendRemoveAllDownloads(context, it.service, false) }
        if (shelves.any { !it.available() })
            notice(context, "The SD card isn't available, so its downloads weren't removed.")
    }
}

/** The card's name as Android shows it ("SanDisk SD card"), or a plain one. */
internal fun cardDescription(context: Context, folder: File): String = runCatching {
    context.getSystemService(android.os.storage.StorageManager::class.java)?.getStorageVolume(folder)?.getDescription(context)
}.getOrNull() ?: "SD card"
