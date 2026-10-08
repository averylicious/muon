package dev.avery.muon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
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
import java.io.IOException
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
    /**
     * Streams a live song straight from Tauon (#213): no cache is read or written at all, so no kept copy,
     * even one whose key is exactly the live address, can answer for it. Replaceable only by fixtures.
     */
    @Volatile var stream: DataSource.Factory = OkHttpDataSource.Factory(Transport.client)

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

/**
 * What the download marks record about one row: its request ID, state and downloaded bytes, and nothing
 * else (#253). It is read from a row and holds no reference to it, so a status waiting to be published
 * does not keep the row's request, URI or stored song record alive. It grants no authority over an index row or
 * cache bytes; ownership and identity checks use the actual records.
 */
internal data class DownloadStatus(val id: String, val state: Int, val bytesDownloaded: Long) {
    companion object {
        @androidx.annotation.OptIn(UnstableApi::class)
        fun of(download: Download) = DownloadStatus(download.request.id, download.state, download.bytesDownloaded)
    }
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
        val record: (DownloadStatus) -> Unit, val removed: (Download) -> Unit,
        /** Where a new save's own cover is fetched; fixtures leave it doing nothing. */
        val artwork: java.util.concurrent.Executor = java.util.concurrent.Executor { },
        /** Played-copy keys a phone index row names, which the played cache never removes (#213). */
        val playedClaims: PlayedClaims = PlayedClaims.none(),
        val moves: DownloadMoveReceipts = DownloadMoveReceipts()) {
        /** The phone's cache, which also holds the played-song copies. */
        val cache: SimpleCache get() = phone.cache
        /** Commands admitted by the services since a completion census; no snapshot survives a mutation. */
        val moveCommandEpoch = java.util.concurrent.atomic.AtomicLong()
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
        val manager = DownloadManager(context, RetainedDownloadIndex(DefaultDownloadIndex(database, index)),
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
        // Downloads stay until removed: only played-song copies are ever evicted, oldest first, and none that
        // a download row names, nor any until the phone's index has been read (#213).
        val claims = PlayedClaims()
        val played = PlayedSongEvictor(limit, claims::removable) { used -> main.post { PlayedCacheState.used = used } }
        val phone = shelf(context, File(context.filesDir, "downloads"), played, database, "", MuonDownloadService::class.java)
        saver.execute {
            // If the index cannot be read, the claims stay unknown and no played copy is ever removed.
            claims.read(phone.manager.downloadIndex)
            if (claims.known) playedWork.resize { played.resize(limit) }
        }
        val art = DownloadArt(File(context.filesDir, "downloads-art"))
        // Covers are fetched one at a time, beside the downloads rather than in their way.
        val artwork = Executors.newSingleThreadExecutor()
        val sizes = DownloadByteTotals()
        fun record(download: DownloadStatus) {
            val id = download.id
            val mark = when (download.state) {
                Download.STATE_COMPLETED -> DownloadMark.Done
                Download.STATE_QUEUED, Download.STATE_RESTARTING -> DownloadMark.Queued
                Download.STATE_DOWNLOADING -> DownloadMark.Downloading
                else -> null
            }
            DownloadMarks.revision++
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
            DownloadMarks.revision++
            // Moved rather than removed: the song is still kept, on the other shelf.
            if (listOfNotNull(phone, store?.card).any { it.completed(download.request.id) }) return
            // Only a cover this entry owns goes with it, and only once no row of its ID is left anywhere.
            // An older download's cover was kept per track number and may belong to other copies, so it is
            // left where it is.
            val id = download.request.id
            if (id.startsWith(NEW_SAVE_PREFIX) && listOfNotNull(phone, store?.card).none { it.holds(id) })
                artwork.execute { art.removeEntry(id) }
            DownloadMarks.marks.remove(id)
            sizes.remove(id)
            DownloadMarks.bytes = sizes.total
        }
        val made = Store(phone, art, played, prefs, database, ::record, ::removed, artwork, claims)
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
     * Reports one shelf's downloads to the UI. Removing a move's original requires this process's
     * receipt and rechecked records/bytes, never a same-ID completion alone. Restart keeps both copies.
     */
    private fun watch(context: Context, shelf: Shelf, store: Store, main: Handler) {
        // Live callbacks and publication run on the application/main looper. An index snapshot can
        // already be obsolete when posted; retain newer events until that one bootstrap finishes.
        var changed: MutableSet<String>? = HashSet()
        shelf.manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(m: DownloadManager, download: Download, finalException: Exception?) {
                val id = download.request.id
                changed?.add(id)
                store.record(DownloadStatus.of(download))
                if (download.state == Download.STATE_FAILED || download.state == Download.STATE_REMOVING)
                    store.moves.find(shelf, id)?.let(store.moves::finish)
                if (download.state == Download.STATE_COMPLETED) finishReadyMoves(context, store)
            }
            override fun onIdle(m: DownloadManager) { finishReadyMoves(context, store) }
            override fun onDownloadRemoved(m: DownloadManager, download: Download) {
                changed?.add(download.request.id)
                store.moves.acknowledgeRemoval(shelf, download.request)
                store.moves.invalidate(download.request.id)
                store.removed(download)
            }
        })
        // One-shot: shut down once the scan is submitted. An orderly shutdown still lets it run and post.
        val bootstrap = Executors.newSingleThreadExecutor()
        try {
            bootstrap.execute {
                // Only each row's ID, state and byte count wait for the main thread, not its stored song
                // record (#253): each cursor row is projected as it is read and its Download dropped.
                val known = ArrayList<DownloadStatus>()
                runCatching { shelf.manager.downloadIndex.getDownloads().use { while (it.moveToNext()) known += DownloadStatus.of(it.download) } }
                main.post {
                    try { known.filterNot { it.id in changed.orEmpty() }.forEach(store.record) }
                    finally { changed = null } // No lifetime-long tombstones for removed/changed songs.
                }
            }
        } finally { bootstrap.shutdown() }
    }

    /** Serial cleanup starts after both managers settle, not during another target Add/source Remove. */
    private fun finishReadyMoves(context: Context, store: Store) {
        if (!store.shelves.all { quiet(it.manager) }) return
        for (receipt in store.moves.ready()) saver.execute {
            val completed = runCatching { receipt.to.manager.downloadIndex.getDownload(receipt.request.id) }.getOrNull()
            if (completed == null || completed.state != Download.STATE_COMPLETED) {
                store.moves.finish(receipt)
            } else if (!completeMovedCopyNow(context, store, receipt.to, completed) &&
                store.moves.find(receipt.to, receipt.request.id) == null) {
                notice(context, "The original copy was kept: Muon couldn't confirm the move finished safely.")
            }
        }
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
                // Not before the phone index has been read: a fresh key must be one no row names (#213).
                if (owner.isCancelled || !store.playedClaims.known || hasPlayedCopyFrom(store.cache, id, song)) return@runCatching
                val call = AtomicReference<Call?>()
                val writer = AtomicReference<CacheWriter?>()
                owner.onCancel { writer.get()?.cancel(); call.get()?.cancel() }
                val origin = id.substringBeforeLast('/')
                val number = id.substringAfterLast('/')
                val url = ServerEndpoint.parse(origin).url("/api1/fileopus/$number")
                val key = playedKey(newSaveId { candidate ->
                    playedKey(candidate) in store.cache.keys || !store.playedClaims.removable(playedKey(candidate))
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
                    // The key was made for this copy alone, on this worker, in no cache and named by no row,
                    // and no Muon writer gives a row a played-copy key, so nothing else owns it.
                    if (store.playedClaims.removable(key)) runCatching { store.cache.removeResource(key) }
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

    /**
     * Empties the played-song cache; downloads stay. A played-copy key a download row also names is kept
     * (#213, [PlayedClaims]): its bytes are that row's too. Before the phone index is read, nothing goes.
     */
    fun clearPlayed(context: Context) {
        val store = get(context)
        val cache = store.cache
        playedWork.clear {
            runCatching {
                cache.keys.filter { it.startsWith(PLAYED_PREFIX) && store.playedClaims.removable(it) }.forEach(cache::removeResource)
            }
        }
    }

    /**
     * Every row of a shelf's index, in every state: what a key census reads, as names and states only
     * (#253). A failed read throws. Off the main thread.
     */
    private fun readCensus(shelf: Shelf): IndexCensus = indexCensus(shelf.manager.downloadIndex)

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
        // #230: nothing is queued while a move is in flight; the service would refuse it anyway.
        if (moveExclusion.held || store.moves.removalInFlight) {
            notice(context, "A move is under way, so nothing was saved. Try again when it finishes.")
            return
        }
        saver.execute {
            val requests = runCatching {
                val taken = takenNames(store)
                playable.map { track -> newSaveRequest(taken, endpoint, track) }
            }.getOrNull()
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

    /**
     * Every name a new save must not take: each row's request ID and cache key in the phone's and the
     * card's index (both kept in the app's own database, read even while the card is out), and each key in
     * either cache. Off the main thread. A failed read fails the save rather than guessing.
     */
    internal fun takenNames(store: Store): MutableSet<String> {
        val taken = HashSet<String>()
        for (shelf in store.shelves) {
            forEachIndexRow(shelf.manager.downloadIndex) { taken += it.id; taken += it.key }
            taken += shelf.cache.keys
        }
        return taken
    }

    /**
     * A new save's request, under a fresh request ID and key that is in [taken] nowhere (and is then added
     * to it), and the cover address it is saved from. The name says nothing about the audio.
     */
    internal fun newSaveRequest(taken: MutableSet<String>, endpoint: ServerEndpoint, track: TauonTrack): Pair<DownloadRequest, String> {
        val id = newSaveId { it in taken }
        taken += id
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
            entries += savedInventory(name, shelf.manager.downloadIndex, shelf.cache,
                played = store.playedClaims.takeIf { name == SavedShelf.Phone }) { store.art.hasEntry(it) }
        }
        return sortSaved(entries)
    }

    /** Stream the current display inventory, optionally hydrating only a bounded locator set. Off main.
     * Failure propagates so a catalog rebuild/page never publishes a partial shelf as success.
     * Full hidden-owner scans remain separate from display-page residency (#253).
     */
    internal fun projectSavedEntries(context: Context, include: (SavedRef) -> Boolean = { true },
        checkpoint: () -> Unit = {}, emit: (SavedEntry) -> Unit) {
        val store = get(context)
        val shelves = listOfNotNull(SavedShelf.Phone to store.phone,
            store.card?.takeIf { it.available() }?.let { SavedShelf.Card to it })
        for ((name, shelf) in shelves) {
            checkpoint()
            SavedOwnerProjection.open(context, shelf.manager.downloadIndex, checkpoint).use { owners ->
                forEachSavedEntry(name, shelf.manager.downloadIndex, shelf.cache,
                    if (name == SavedShelf.Phone) store.playedClaims else null,
                    { store.art.hasEntry(it) }, include, checkpoint, ownership = owners::soleOwner, emit = emit)
            }
            if (!shelf.available()) throw java.io.IOException("Saved storage changed; refresh your copies")
        }
    }

    /** Connect's playable-copy count, without constructing a sorted saved library (#253). Off main. */
    fun savedCompleteCount(context: Context): Long {
        val store = get(context)
        val shelves = listOfNotNull(SavedShelf.Phone to store.phone,
            store.card?.takeIf { it.available() }?.let { SavedShelf.Card to it })
        return shelves.sumOf { (name, shelf) ->
            // Match savedEntries: a failed shelf contributes nothing, never its partial scan.
            runCatching { countCompleteSavedCopies(name, shelf.manager.downloadIndex, shelf.cache,
                includePlayed = name == SavedShelf.Phone) }.getOrDefault(0L)
        }
    }

    /**
     * The server most saved copies came from, if any names one, and every saved copy: what "Listen offline"
     * opens when there is no saved server, as after Disconnect. Copies with no known origin, played copies
     * included, open just the same: they play from this phone without any server. Null with nothing
     * playable. Off the main thread.
     */
    fun savedLibrary(context: Context): Pair<String?, List<SavedEntry>>? {
        val entries = savedEntries(context)
        if (entries.none { it.complete }) return null
        return entries.mapNotNull { it.from }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key to entries
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
                if (!shelf.available()) return@runCatching false
                val download = shelf.manager.downloadIndex.getDownload(ref.requestId) ?: return@runCatching false
                download.state != Download.STATE_REMOVING &&
                    (download.request.customCacheKey ?: download.request.uri.toString()) == ref.key
            }
        }
    }.getOrDefault(false)

    /**
     * Removes one saved copy, and only it (#213). A download row goes only when its shelf is available and
     * a census of that shelf's index finds it the sole owner of its bytes ([soleOwner]); a played copy only
     * when no download row names its key ([PlayedClaims]). Otherwise nothing is changed, the copy still
     * plays, and the refusal is said. A played copy being made is under its own fresh key and is not listed,
     * so removing a listed one cannot cut into it. The census is a snapshot; why it holds until Media3
     * removes the bytes is set out at [soleOwner].
     */
    fun removeSaved(context: Context, ref: SavedRef) {
        saver.execute {
            when (removeSavedNow(context, ref)) {
                SavedRemoval.Sent -> Unit
                SavedRemoval.Unavailable ->
                    notice(context, "That copy is on the SD card, which isn't available, so it wasn't removed.")
                SavedRemoval.NotOwned ->
                    notice(context, "Muon can't tell that copy's bytes belong to it alone, so it was kept.")
                SavedRemoval.Busy ->
                    notice(context, "A move is under way, so that copy wasn't removed. " +
                        "Try again when the move finishes.")
            }
        }
    }

    internal enum class SavedRemoval { Sent, Unavailable, NotOwned, Busy }

    /** [removeSaved]'s checks and command, on the calling thread; reads one index row. */
    internal fun removeSavedNow(context: Context, ref: SavedRef): SavedRemoval {
        val store = get(context)
        if (store.moves.removalInFlight) return SavedRemoval.Busy
        store.moves.invalidate(ref.requestId)
        if (ref.source == SavedSource.Played) {
            if (!store.playedClaims.removable(ref.key)) return SavedRemoval.NotOwned
            // A copy being written is under a fresh key of its own; SimpleCache serializes removals.
            runCatching { store.cache.removeResource(ref.key) }
            return SavedRemoval.Sent
        }
        // #230: no removal reaches a manager while a move reads and writes copies. The song's own hand-over is
        // revoked now, so the move does not add it once it finishes (#234); the removal itself is not kept
        // to run later, and the user is asked to remove it again.
        if (moveExclusion.held) {
            moveOwnership.remove(listOf(ref.requestId))
            return SavedRemoval.Busy
        }
        val shelf = (if (ref.shelf == SavedShelf.Card) store.card else store.phone)
            ?.takeIf { it.available() } ?: return SavedRemoval.Unavailable
        val census = runCatching { readCensus(shelf) }.getOrNull() ?: return SavedRemoval.NotOwned
        val row = census.row(ref.requestId)
        if (row == null || row.key != ref.key || !census.soleOwner(row)) return SavedRemoval.NotOwned
        // Its own move, if one is under way, loses its hand-over too (#234).
        moveOwnership.remove(listOf(ref.requestId))
        DownloadService.sendRemoveDownload(context, shelf.service, ref.requestId, false)
        return SavedRemoval.Sent
    }

    /**
     * Completes only this process's byte-checked move, never an unrelated same-ID download. Current
     * source and destination rows must still be the exact published request, completed and sole owners;
     * every cached byte is compared again before removal. Missing evidence or any failure keeps both.
     * The final command shares the receipt invalidation lock. Cache/index checks remain snapshots;
     * supported Muon producers must continue never rebinding or aliasing another entry's key.
     */
    internal fun completeMovedCopyNow(context: Context, store: Store, to: Shelf, completed: Download): Boolean {
        val receipt = store.moves.find(to, completed.request.id) ?: return false
        if (!store.moves.readyToCheck(receipt)) return false
        val epoch = store.moveCommandEpoch.get()
        try {
            val from = receipt.from
            if (receipt.to !== to || completed.request != receipt.request || !canMove(from, to)) return false
            val id = receipt.request.id
            val sourceCensus = readCensus(from)
            val targetCensus = readCensus(to)
            if (sourceCensus.row(id)?.state != Download.STATE_COMPLETED || targetCensus.row(id)?.state != Download.STATE_COMPLETED ||
                !sourceCensus.soleOwner(id) || !targetCensus.soleOwner(id)) return false
            // Only this song's two full records, read again by ID, for the exact request (#253).
            val source = from.manager.downloadIndex.getDownload(id) ?: return false
            val target = to.manager.downloadIndex.getDownload(id) ?: return false
            if (source.request != receipt.request || target.request != receipt.request ||
                source.state != Download.STATE_COMPLETED || target.state != Download.STATE_COMPLETED) return false
            val key = receipt.request.customCacheKey ?: return false
            val length = ContentMetadata.getContentLength(from.cache.getContentMetadata(key))
            if (length <= 0 || ContentMetadata.getContentLength(to.cache.getContentMetadata(key)) != length ||
                !from.cache.isCached(key, 0, length) || !to.cache.isCached(key, 0, length) ||
                !spansWithin(from.cache, key, length) || !spansWithin(to.cache, key, length)) return false
            if (!sameBytes(DataSpec.Builder().setUri(receipt.request.uri).setKey(key).setLength(length).build(), from, to))
                return false
            if (!spansWithin(from.cache, key, length) || !spansWithin(to.cache, key, length)) return false
            if (store.moveCommandEpoch.get() != epoch) return false
            return store.moves.queueRemoval(receipt, epoch, length) {
                val intent = DownloadService.buildRemoveDownloadIntent(context, from.service, receipt.request.id, false)
                    .putExtra(MOVE_COMMAND_TOKEN, receipt.token)
                context.startService(intent)
            }
        } catch (_: Exception) { return false }
        finally { if (!receipt.removalPending) store.moves.finish(receipt) }
    }

    /**
     * Starts services for current-process saves. Retained unfinished operations are stopped by
     * RetainedDownloadIndex; they never fetch replacement audio or resume an old deletion. The service
     * stops itself again once there is nothing to do.
     */
    fun resume(context: Context) {
        // An unavailable card's service is not started here, so its queued downloads are not resumed onto
        // missing storage. A card service already running, or restarted by the system, is not covered.
        availableShelves(get(context).shelves).forEach { runCatching { DownloadService.start(context, it.service) } }
    }

    /** Moves one download at a time, behind everything else. */
    private val mover = Executors.newSingleThreadExecutor()
    private val moveOwnership = DownloadMoveOwnership()
    /** How a move's destination files are opened ([StrictMoveSink]); replaced only by fixtures injecting failures. */
    @Volatile internal var moveOutputs: MoveFileOutputs = MoveFileOutputs.Real
    /** Held from a move's admission until its copies are handed over (#230); see [MoveExclusion]. */
    private val moveExclusion = MoveExclusion()

    /** Service commands a move in flight refuses: every one that can add, remove, start or restart work. */
    private val moveExcludedActions = setOf(DownloadService.ACTION_ADD_DOWNLOAD, DownloadService.ACTION_REMOVE_DOWNLOAD,
        DownloadService.ACTION_REMOVE_ALL_DOWNLOADS, DownloadService.ACTION_RESUME_DOWNLOADS,
        DownloadService.ACTION_SET_STOP_REASON, DownloadService.ACTION_SET_REQUIREMENTS)

    /**
     * The command a download service may pass on to Media3 now (#230). While a move holds [moveExclusion],
     * a command that could add, remove, start or restart a download becomes a copy that changes nothing, with
     * the same extras (so a foreground start still shows its notification), and the refusal is said; it is
     * not kept to run later. Called on the main thread, before DownloadService.onStartCommand; never makes a
     * store. Pausing, and everything when no move is in flight, passes unchanged.
     */
    internal const val MOVE_COMMAND_TOKEN = "dev.avery.muon.move-command-token"

    /** The card's earlier binding/availability gate refused a move command before this admission gate. */
    internal fun refusedMoveCommand(intent: Intent) {
        intent.getStringExtra(MOVE_COMMAND_TOKEN)?.let { current()?.moves?.refuseQueued(it) }
    }

    /** Rechecks a process-owned command at actual service delivery; old/replayed commands become INIT. */
    @Suppress("DEPRECATION")
    internal fun admitCommand(context: Context, intent: Intent?, shelf: Shelf? = null): Intent? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Download command admission requires main" }
        val action = intent?.action ?: return intent
        val store = current()
        fun refused(message: String): Intent {
            notice(context, message)
            return Intent(intent).setAction(DownloadService.ACTION_INIT)
        }
        if (intent.hasExtra(MOVE_COMMAND_TOKEN)) {
            val token = intent.getStringExtra(MOVE_COMMAND_TOKEN)
            val adding = action == DownloadService.ACTION_ADD_DOWNLOAD
            val request = if (adding) intent.getParcelableExtra<DownloadRequest>(DownloadService.KEY_DOWNLOAD_REQUEST) else null
            val id = if (adding) request?.id else intent.getStringExtra(DownloadService.KEY_CONTENT_ID)
            val receipt = if (store != null && shelf != null && token != null && id != null)
                store.moves.tagged(shelf, id, token, adding) else null
            val valid = receipt != null && !moveExclusion.held && canMove(receipt.from, receipt.to) &&
                (if (adding) request == receipt.request && store!!.moves.admitAdd(receipt)
                else action == DownloadService.ACTION_REMOVE_DOWNLOAD && runCatching {
                    // Census/byte comparison ran off main. Any subsequently admitted mutation invalidates it;
                    // quiet managers and exact records prevent a pending/rebound request using this receipt.
                    val from = receipt.from; val to = receipt.to
                    val source = from.manager.downloadIndex.getDownload(receipt.request.id)
                    val target = to.manager.downloadIndex.getDownload(receipt.request.id)
                    val key = receipt.request.customCacheKey
                    val length = receipt.checkedLength
                    store!!.moveCommandEpoch.get() == receipt.checkedEpoch && quiet(from.manager) && quiet(to.manager) &&
                        source != null && target != null && source.request == receipt.request && target.request == receipt.request &&
                        source.state == Download.STATE_COMPLETED && target.state == Download.STATE_COMPLETED &&
                        key != null && length > 0 &&
                        ContentMetadata.getContentLength(from.cache.getContentMetadata(key)) == length &&
                        ContentMetadata.getContentLength(to.cache.getContentMetadata(key)) == length &&
                        from.cache.isCached(key, 0, length) && to.cache.isCached(key, 0, length) &&
                        spansWithin(from.cache, key, length) && spansWithin(to.cache, key, length) &&
                        store.moves.admitRemoval(receipt)
                }.getOrDefault(false))
            if (!valid) {
                if (receipt != null && !receipt.removalPending) store!!.moves.finish(receipt)
                // A queued removal refused at delivery will never be retried automatically.
                if (receipt != null && receipt.removeQueued()) {
                    store!!.moves.finish(receipt)
                    Handler(Looper.getMainLooper()).post { finishReadyMoves(context, store) }
                }
                return refused("That move command is no longer current, so it wasn't applied. The remaining copies were kept.")
            }
            return intent
        }
        if (action !in moveExcludedActions) return intent
        if (moveExclusion.held || store?.moves?.removalInFlight == true)
            return refused("A move is under way, so that change wasn't made. Try again when it finishes.")
        if (store != null) {
            store.moveCommandEpoch.incrementAndGet()
            val id = if (action == DownloadService.ACTION_ADD_DOWNLOAD)
                intent.getParcelableExtra<DownloadRequest>(DownloadService.KEY_DOWNLOAD_REQUEST)?.id
                else intent.getStringExtra(DownloadService.KEY_CONTENT_ID)
            if (id == null) store.moves.invalidateAll() else store.moves.invalidate(id)
        }
        return intent
    }

    /**
     * Whether [manager] has nothing in flight a move could race (#230): initialized, idle (no command waiting
     * on its handler, no task running) and no download it could start or remove when resumed or when its
     * requirements are met. Stopped downloads stay stopped: setting a stop reason is refused during a move.
     * Read on the main thread, where Media3 keeps these counts.
     */
    private fun quiet(manager: DownloadManager): Boolean = manager.isInitialized && manager.isIdle &&
        manager.currentDownloads.none {
            it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING ||
                it.state == Download.STATE_REMOVING || it.state == Download.STATE_RESTARTING
        }

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
        check(Looper.myLooper() == Looper.getMainLooper()) { "Move admission requires main" }
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
        // #230: only with both managers quiet, and no other move in flight, does a move start; then no
        // command can reach either manager until its copies are handed over. Otherwise nothing is moved.
        if (store.moves.hasPending || !quiet(from.manager) || !quiet(to.manager) || !moveExclusion.tryAcquire()) {
            notice(context, "Saved copies are still being saved, removed or moved, so nothing was moved. " +
                "Try again when that has finished.")
            return
        }
        val main = Handler(Looper.getMainLooper())
        val batch = moveOwnership.begin()
        try { mover.execute { moveBatch(context, store, from, to, batch, main) } }
        catch (failure: RuntimeException) {
            moveOwnership.finish(batch)
            moveExclusion.release()
            throw failure
        }
    }

    /** One admitted move, on the mover: copies first, then one main-thread step that releases and hands over. */
    private fun moveBatch(context: Context, store: Store, from: Shelf, to: Shelf, batch: DownloadMoveOwnership.Batch,
        main: Handler) {
        // Written by the mover only; read by the main-thread step it posts last (the post orders the two).
        val copiedRequests = ArrayList<DownloadRequest>()
        run {
            // run only scopes the batch; the finally below runs on every exit, including a thrown Error.
            try {
                // One census of both indexes for the batch (#213). It stays valid for each song: neither
                // writer can add a row naming a key another row owns (see [soleOwner]), and this move adds
                // only rows that pass [movable]. Names and states only (#253): each song's full record is
                // read again when its turn comes, and dropped after its copy.
                val sourceCensus = runCatching { readCensus(from) }.getOrNull()
                val targetCensus = runCatching { readCensus(to) }.getOrNull()
                val ids = sourceCensus?.rows.orEmpty().filter { it.state == Download.STATE_COMPLETED }.map { it.id }
                main.post { DownloadMarks.moving = 0 to ids.size }
                // Hand-over can publish only as many copies as there are free receipts (#253), so no more are
                // copied: a copy past that would leave unindexed bytes on the target and never be added. Read
                // once: only this batch's hand-over remembers receipts while the move holds its exclusion.
                val room = store.moves.available()
                val metadata = DownloadMoveBudget()
                var oversized = 0
                var kept = 0
                var failed = 0
                var deferred = 0
                for ((index, id) in ids.withIndex()) {
                    // Only successful copies take a place; the rest stay where they are, untouched, for a
                    // later move.
                    if (copiedRequests.size >= room) {
                        deferred = ids.size - index
                        break
                    }
                    if (!canMove(from, to)) {
                        notice(context, "The SD card isn't available any more, so the rest weren't moved.")
                        break
                    }
                    // A copy whose bytes another row may share, or one the target already names otherwise,
                    // stays where it is, untouched; an unread target index moves nothing. So does a row that
                    // is no longer the finished one counted, or can't be read again.
                    val download = runCatching { from.manager.downloadIndex.getDownload(id) }.getOrNull()
                        ?.takeIf { it.state == Download.STATE_COMPLETED }
                    val safe = sourceCensus != null && targetCensus != null && download != null && runCatching {
                        movable(download, sourceCensus, targetCensus) { to.manager.downloadIndex.getDownload(it) }
                    }.getOrDefault(false)
                    if (!safe) kept++
                    // Count the exact preserved request before copying any target output (#253).
                    // Count alone does not bound the legacy raw tags retained until hand-over.
                    if (safe && download != null && !metadata.fits(download.request)) {
                        if (!metadata.fitsAlone(download.request)) {
                            oversized++ // A later move cannot fit it either; explain without a retry loop.
                            main.post { DownloadMarks.moving = index + 1 to ids.size }
                            continue
                        }
                        deferred = ids.size - index
                        break // Successful requests consume budget; failed/unsafe ones do not.
                    }
                    val attempted = safe && moveOwnership.permits(batch, id)
                    // The copy stops writing as soon as the move no longer owns this song (removed, or
                    // Remove all, #234) or either shelf goes (#179 S1), rather than finishing a copy no one
                    // will hand over (#230).
                    val copied = attempted && download != null && runCatching {
                        copy(download, from, to) { canMove(from, to) && moveOwnership.permits(batch, id) }
                    }.isSuccess
                    if (attempted && !copied) failed++
                    // Handed over only after the last copy, when the exclusion is released (#230). Each handed
                    // over request, with its stored record, is kept until then.
                    if (copied && download != null) {
                        metadata.commit(download.request)
                        copiedRequests += download.request
                    }
                    main.post { DownloadMarks.moving = index + 1 to ids.size }
                }
                if (kept > 0) notice(context, "$kept ${if (kept == 1) "copy was" else "copies were"} kept where " +
                    "${if (kept == 1) "it was" else "they were"}: Muon can't tell their bytes belong to them alone.")
                if (failed > 0) notice(context, if (failed == 1)
                    "1 copy couldn't be moved. Its saved entry was kept."
                else "$failed copies couldn't be moved. Their saved entries were kept.")
                if (oversized > 0) notice(context, "$oversized saved ${if (oversized == 1) "copy has" else "copies have"} " +
                    "too much stored metadata to move safely. The original records and audio were kept.")
                if (deferred > 0) notice(context, if (deferred == 1)
                    "1 more copy stays where it is. Move again once this move finishes."
                else "$deferred more copies stay where they are. Move again once this move finishes.")
            } finally {
                // One main-thread step, after the mover has stopped reading and writing the copies: release
                // the exclusion, then hand over. Nothing runs between the two, so a command refused during
                // the move cannot slip in first; one sent after it is an ordinary command. A removal refused
                // during the move revoked that song's hand-over, so it is not added (#234).
                main.post {
                    try {
                        moveExclusion.release()
                        handOver(context, store, from, to, batch, copiedRequests)
                    } finally { moveOwnership.finish(batch); DownloadMarks.moving = null }
                }
            }
        }
    }

    /**
     * Sends each copied song's Add to [to], on the main thread, once the move's exclusion is released.
     * Hand-over needs both shelves still available (#179 S1) and the move still owning the song: removed or
     * Remove all since means no Add (#234). A send that fails invalidates that song's receipt and is said;
     * the others are still handed over.
     */
    private fun handOver(context: Context, store: Store, from: Shelf, to: Shelf, batch: DownloadMoveOwnership.Batch,
        copied: List<DownloadRequest>) {
        var unsent = 0
        for (request in copied) deliverMovedCopy(from, to) {
            moveOwnership.publish(batch, request.id) {
                if (store.moves.remember(from, to, request)) {
                    try {
                        val receipt = requireNotNull(store.moves.find(to, request.id))
                        context.startService(DownloadService.buildAddDownloadIntent(context, to.service, request,
                            Download.STOP_REASON_NONE, false).putExtra(MOVE_COMMAND_TOKEN, receipt.token))
                    }
                    catch (_: Exception) { store.moves.invalidate(request.id); unsent++ }
                } else notice(context, "The original copy was kept: another move is still pending. Retry later.")
            }
        }
        if (unsent > 0) notice(context, "$unsent moved ${if (unsent == 1) "copy wasn't" else "copies weren't"} " +
            "handed over. The originals were kept.")
    }

    /** Thrown from the copy's progress callback once [copy]'s owner no longer wants it; see [copy]. */
    private class MoveCopyStopped : java.io.IOException("The move stopped owning this copy")

    /**
     * Writes one finished download's bytes from [from]'s cache into [to]'s, under the same key. [keepGoing]
     * is asked before the first write and after every block the writer caches; once it says no, writing
     * stops there (#230). Nothing already written is removed, truncated or relabelled: those bytes stay
     * unindexed on [to], where a later attempt compares them with the source before filling the rest, and
     * refuses them if they differ. Stopping is a check between blocks, not exclusion of other writers.
     */
    private fun copy(download: Download, from: Shelf, to: Shelf, keepGoing: () -> Boolean = { canMove(from, to) }) {
        val id = download.request.id
        val length = ContentMetadata.getContentLength(from.cache.getContentMetadata(id))
        require(length > 0 && from.cache.isCached(id, 0, length) && spansWithin(from.cache, id, length)) { "Not fully downloaded within the expected length" }
        val targetLength = ContentMetadata.getContentLength(to.cache.getContentMetadata(id))
        require(spansWithin(to.cache, id, length)) { "Existing destination bytes extend beyond the source" }
        val targetRecord = to.manager.downloadIndex.getDownload(id)
        if (targetRecord != null) {
            // Matching address/tags are not audio identity. An older recorded partial copy cannot be
            // extended/relabelled from another copy; keep it intact. An already complete exact copy
            // needs no writes or metadata mutation, only the later tracked hand-over/completion.
            require(targetRecord.request == download.request &&
                (targetRecord.state == Download.STATE_COMPLETED ||
                    (targetRecord.state == Download.STATE_STOPPED && targetRecord.stopReason == RETAINED_STOP_REASON)) &&
                targetLength == length && to.cache.isCached(id, 0, length)) { "Existing recorded destination is not a complete exact copy" }
            require(sameBytes(DataSpec.Builder().setUri(download.request.uri).setKey(id).setLength(length).build(), from, to)) {
                "Existing recorded destination differs from the source"
            }
            return
        }
        val targetSpans = to.cache.getCachedSpans(id)
        require(targetSpans.isEmpty() || targetLength == C.LENGTH_UNSET.toLong() || targetLength == length) {
            "Existing destination has another known length"
        }
        // CacheWriter fills holes around existing target spans. Refuse a known mismatch before that
        // first write, rather than extending an unrelated partial copy and rejecting it afterwards.
        // Snapshot only: late writers are still checked by the full post-copy comparison below.
        for (span in targetSpans) {
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
        // Destination files go through a strict sink (#230): each is committed only after its own flush,
        // sync and close succeed, and any failure is kept even where Media3 closes quietly.
        val sinks = ArrayList<StrictMoveSink>()
        val writer = CacheDataSource.Factory().setCache(to.cache).setUpstreamDataSourceFactory(reader)
            .setCacheWriteDataSinkFactory { StrictMoveSink(to.cache, moveOutputs).also(sinks::add) }
            .createDataSourceForDownloading()
        if (!keepGoing()) throw MoveCopyStopped()
        // Throwing from the progress callback ends cache(), which closes its source and releases its hole
        // lock first, as PlayedCopy's budget check relies on; the bytes committed so far are kept.
        CacheWriter(writer, DataSpec.Builder().setUri(download.request.uri).setKey(id).build(), null) { _, _, _ ->
            if (!keepGoing()) throw MoveCopyStopped()
        }.cache()
        // CacheWriter returning is not evidence its output was written out: require each destination file
        // this copy opened to have been flushed, synced, closed and committed without a failure.
        sinks.firstOrNull { it.failure != null }?.let { throw IOException("A moved file wasn't written out", it.failure) }
        require(sinks.all { it.clean }) { "A moved file wasn't fully written out" }
        // CacheWriter keeps whatever the target already held for this key, so only an exact copy counts (#230).
        require(sameBytes(DataSpec.Builder().setUri(download.request.uri).setKey(id).setLength(length).build(), from, to)) {
            "Copy differs from its source"
        }
        // A late writer may have appended bytes after the preflight. Comparing only [0, length)
        // cannot see them. Keep all bytes and the source when the current extent no longer fits.
        require(spansWithin(from.cache, id, length) && spansWithin(to.cache, id, length)) { "Copy bytes now extend beyond the expected length" }
        to.cache.applyContentMetadataMutations(id, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), length))
    }

    /** A current extent snapshot, not a writer lock or authority to truncate unknown spans. */
    private fun spansWithin(cache: Cache, key: String, length: Long): Boolean =
        length > 0 && cache.getCachedSpans(key).all {
            it.position >= 0 && it.length >= 0 && it.position <= length && it.length <= length - it.position
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
                if (!canMove(from, to)) return false
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
        if (get(context).moves.removalInFlight) {
            notice(context, "A move is finishing, so nothing was removed. Try again when it finishes.")
            return
        }
        // Every move in flight loses its publication, whichever shelves receive the commands (#234).
        moveOwnership.removeAll()
        get(context).moves.invalidateAll()
        // #230: nothing reaches a manager while a move is in flight. Its hand-overs were just revoked, so
        // nothing it copied is added; the removal is not kept to run later.
        if (moveExclusion.held) {
            notice(context, "A move is under way, so nothing was removed, and nothing more will be moved. " +
                "Remove all again when the move finishes.")
            return
        }
        saver.execute {
            val (_, kept) = removeAllNow(context)
            if (get(context).shelves.any { !it.available() })
                notice(context, "The SD card isn't available, so its saved copies weren't removed.")
            if (kept > 0) notice(context, "$kept saved ${if (kept == 1) "copy was" else "copies were"} kept: " +
                "Muon can't tell their bytes belong to them alone.")
        }
    }

    /**
     * [removeAll]'s commands, on the calling thread: every row on each available shelf that a census finds
     * the sole owner of its bytes ([soleOwner]) is removed, one by one. Media3's own Remove all would also
     * delete bytes a kept played copy or an unknown row shares (#213). Returns how many were sent and kept.
     */
    internal fun removeAllNow(context: Context): Pair<Int, Int> {
        var sent = 0
        var kept = 0
        for (shelf in availableShelves(get(context).shelves)) {
            // The whole census is read before any command, so a removal cannot change the rows being judged.
            val census = runCatching { readCensus(shelf) }.getOrNull() ?: continue
            for (row in census.rows) {
                if (row.state == Download.STATE_REMOVING) continue
                if (census.soleOwner(row)) {
                    DownloadService.sendRemoveDownload(context, shelf.service, row.id, false)
                    sent++
                } else kept++
            }
        }
        return sent to kept
    }
}

/** The card's name as Android shows it ("SanDisk SD card"), or a plain one. */
internal fun cardDescription(context: Context, folder: File): String = runCatching {
    context.getSystemService(android.os.storage.StorageManager::class.java)?.getStorageVolume(folder)?.getDescription(context)
}.getOrNull() ?: "SD card"
