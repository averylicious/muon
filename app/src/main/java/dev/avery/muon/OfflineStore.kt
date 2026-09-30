package dev.avery.muon

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
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

/**
 * Where downloads are kept: the phone's own storage, or a removable SD card (#112, mockup 01). Each has
 * its own cache, its own download index and its own service, so a card taken out simply takes its
 * downloads with it until it is back.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class Shelf(val cache: SimpleCache, val manager: DownloadManager, val service: Class<out DownloadService>) {
    /** Reads this shelf's copies; anything not on it streams, and nothing streamed is written. */
    val source: CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Transport.client)).setCacheWriteDataSinkFactory(null)

    fun completed(id: String): Boolean =
        runCatching { manager.downloadIndex.getDownload(id)?.state == Download.STATE_COMPLETED }.getOrDefault(false)
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
        val record: (Download) -> Unit, val removed: (Download) -> Unit) {
        /** The phone's cache, which also holds the played-song copies. */
        val cache: SimpleCache get() = phone.cache
        @Volatile var card: Shelf? = null
        val shelves: List<Shelf> get() = listOfNotNull(phone, card)
    }

    /** Set while the library shows only what is on the phone; the player then also plays cached songs. */
    @Volatile var offline = false

    /** Copies played songs one at a time, behind playback. */
    private val copier = Executors.newSingleThreadExecutor()

    @Volatile private var store: Store? = null

    /** The store, if something has already made it; never makes one. */
    fun current(): Store? = store

    @Synchronized
    fun get(context: Context): Store = store ?: create(context.applicationContext).also { store = it }

    private fun shelf(context: Context, folder: File, evictor: androidx.media3.datasource.cache.CacheEvictor,
        database: StandaloneDatabaseProvider, index: String, service: Class<out DownloadService>): Shelf {
        val cache = SimpleCache(folder, evictor, database)
        val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Transport.client))
        val manager = DownloadManager(context, DefaultDownloadIndex(database, index),
            DefaultDownloaderFactory(factory, Executors.newFixedThreadPool(2)))
        manager.maxParallelDownloads = 2
        return Shelf(cache, manager, service)
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
        val sizes = HashMap<String, Long>()
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
            if (mark == DownloadMark.Done) sizes[id] = download.bytesDownloaded else sizes.remove(id)
            // Also fills in the cover of a download made before covers were kept, when Tauon answers.
            if (mark != null && !art.has(id)) artwork.execute { art.fetch(id) }
            DownloadMarks.bytes = sizes.values.sum()
        }
        fun removed(download: Download) {
            // Moved rather than removed: the song is still kept, on the other shelf.
            if (listOfNotNull(phone, store?.card).any { it.completed(download.request.id) }) return
            artwork.execute { art.remove(download.request.id) }
            DownloadMarks.marks.remove(download.request.id)
            sizes.remove(download.request.id)
            DownloadMarks.bytes = sizes.values.sum()
        }
        val made = Store(phone, art, played, prefs, database, ::record, ::removed)
        watch(context, phone, made, main)
        cardFolder(context)?.let { folder ->
            runCatching { shelf(context, File(folder, "downloads"), NoOpCacheEvictor(), database, "card", MuonCardDownloadService::class.java) }
                .getOrNull()?.let { card -> made.card = card; watch(context, card, made, main) }
        }
        return made
    }

    /**
     * Reports one shelf's downloads to the UI: those already there, then every change. A song is kept on
     * one shelf only, so once it finishes on this one, a copy left on the other (a move) is removed.
     */
    private fun watch(context: Context, shelf: Shelf, store: Store, main: Handler) {
        shelf.manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(m: DownloadManager, download: Download, finalException: Exception?) {
                store.record(download)
                if (download.state != Download.STATE_COMPLETED) return
                val id = download.request.id
                store.shelves.filter { it !== shelf && it.completed(id) }
                    .forEach { DownloadService.sendRemoveDownload(context, it.service, id, false) }
            }
            override fun onDownloadRemoved(m: DownloadManager, download: Download) = store.removed(download)
        })
        Executors.newSingleThreadExecutor().execute {
            val known = ArrayList<Download>()
            runCatching { shelf.manager.downloadIndex.getDownloads().use { while (it.moveToNext()) known += it.download } }
            main.post { known.forEach(store.record) }
        }
    }

    /** Where new downloads go: the card when chosen and in, otherwise the phone. */
    private fun target(store: Store): Shelf = store.card?.takeIf { store.prefs.getBoolean("onCard", false) } ?: store.phone

    /** Whether new downloads go to the SD card; only offered while one is in. */
    fun storeOnCard(context: Context): Boolean = get(context).prefs.getBoolean("onCard", false)

    fun setStoreOnCard(context: Context, onCard: Boolean) { get(context).prefs.edit().putBoolean("onCard", onCard).apply() }

    /** The shelf holding a finished download of [id], if any. Called from the player's loading thread. */
    fun downloadedOn(context: Context, id: String): Shelf? = get(context).shelves.firstOrNull { it.completed(id) }

    /** Whether a finished download of [id] is on the phone or its card. */
    fun downloaded(context: Context, id: String): Boolean = downloadedOn(context, id) != null

    /**
     * The player's data source: a song with a finished download plays its Opus copy from wherever it is
     * kept, with no network at all; every other request streams as before, and nothing streamed is written.
     */
    @Suppress("UNUSED_PARAMETER")
    fun playbackSource(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { OfflineDataSource { spec -> route(context, spec) } }

    /** Which shelf serves [spec], and the request to make of it. */
    private fun route(context: Context, spec: DataSpec): Pair<Shelf, DataSpec> {
        val phone = get(context).phone
        val found = downloadForStream(spec.uri.scheme, spec.uri.encodedAuthority, spec.uri.path) ?: return phone to spec
        downloadedOn(context, found.first)?.let { return it to spec.buildUpon().setUri(Uri.parse(found.second)).setKey(found.first).build() }
        // Recent listening plays from the phone only when Tauon cannot be reached; at home the original
        // stream is always preferred.
        if (offline && playedCopy(context, found.first))
            return phone to spec.buildUpon().setUri(Uri.parse(found.second)).setKey(playedKey(found.first)).build()
        return phone to spec
    }

    /** Whether a complete played-song copy of [id] is on the phone. */
    fun playedCopy(context: Context, id: String): Boolean = runCatching {
        val cache = get(context).cache
        val key = playedKey(id)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        length != C.LENGTH_UNSET.toLong() && cache.isCached(key, 0, length)
    }.getOrDefault(false)

    /**
     * Keeps an Opus copy of a song that has started playing, with its details for the offline library,
     * unless it is downloaded or already copied. Runs behind playback; a failure leaves nothing to show.
     */
    fun copyPlayed(context: Context, id: String, song: ByteArray?) {
        if (song == null) return
        val store = get(context)
        copier.execute {
            runCatching {
                if (downloaded(context, id) || playedCopy(context, id)) return@runCatching
                val origin = id.substringBeforeLast('/')
                val number = id.substringAfterLast('/')
                val url = ServerEndpoint.parse(origin).url("/api1/fileopus/$number")
                val key = playedKey(id)
                store.cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(SONG_METADATA, song))
                val source = CacheDataSource.Factory().setCache(store.cache)
                    .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Transport.client)).createDataSourceForDownloading()
                copyPlayedWithinLimit(source, DataSpec.Builder().setUri(Uri.parse(url)).setKey(key).build()) {
                    store.prefs.getLong("cacheLimit", DEFAULT_CACHE_LIMIT)
                }
            }
        }
    }

    /** A new cache limit, kept for next time; lowering it makes room at once. */
    fun setCacheLimit(context: Context, limit: Long) {
        val store = get(context)
        store.prefs.edit().putLong("cacheLimit", limit).apply()
        PlayedCacheState.limit = limit
        copier.execute { store.played.resize(limit) }
    }

    /** Empties the played-song cache; downloads stay. */
    fun clearPlayed(context: Context) {
        val cache = get(context).cache
        copier.execute { runCatching { cache.keys.filter { it.startsWith(PLAYED_PREFIX) }.forEach(cache::removeResource) } }
    }

    /** Asks for these songs to be downloaded, skipping any already on the phone or on their way. */
    fun add(context: Context, endpoint: ServerEndpoint, tracks: List<TauonTrack>) {
        tracks.filter { it.playable }.forEach { track ->
            val id = downloadId(endpoint.origin, track.id)
            if (DownloadMarks.marks[id] != null) return@forEach
            val request = DownloadRequest.Builder(id, Uri.parse(endpoint.url("/api1/fileopus/${track.id}")))
                .setCustomCacheKey(id).setData(encodeSong(track)).build()
            DownloadService.sendAddDownload(context, target(get(context)).service, request, false)
        }
    }

    /**
     * The songs downloaded from [origin], for the library shown when Tauon cannot be reached. Reads the
     * download index, so call it off the main thread, after [get].
     */
    fun downloadedSongs(context: Context, origin: String): List<TauonTrack> {
        val songs = ArrayList<TauonTrack>()
        get(context).shelves.forEach { shelf -> runCatching {
            shelf.manager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    if (!download.request.id.startsWith("$origin/")) continue
                    decodeSong(download.request.data)?.takeIf { downloadId(origin, it.id) == download.request.id }?.let(songs::add)
                }
            }
        } }
        // And the songs played recently enough to still have a complete copy.
        runCatching {
            val cache = get(context).cache
            val have = songs.mapTo(HashSet()) { it.id }
            cache.keys.filter { it.startsWith(playedKey("$origin/")) }.forEach { key ->
                val id = key.removePrefix(PLAYED_PREFIX)
                val song = cache.getContentMetadata(key).get(SONG_METADATA, null as ByteArray?)?.let(::decodeSong) ?: return@forEach
                if (song.id !in have && downloadId(origin, song.id) == id && playedCopy(context, id)) { songs += song; have += song.id }
            }
        }
        return songs
    }

    /**
     * The server most songs were downloaded from, and those songs: what "Listen offline" opens when
     * there is no saved server, as after Disconnect. Null with nothing downloaded. Off the main thread.
     */
    fun downloadedLibrary(context: Context): Pair<String, List<TauonTrack>>? {
        val origins = HashMap<String, Int>()
        get(context).shelves.forEach { shelf -> runCatching {
            shelf.manager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
                while (cursor.moveToNext()) {
                    val origin = cursor.download.request.id.substringBeforeLast('/', "")
                    if (origin.isNotEmpty()) origins[origin] = (origins[origin] ?: 0) + 1
                }
            }
        } }
        val origin = origins.maxByOrNull { it.value }?.key ?: return null
        return downloadedSongs(context, origin).takeIf { it.isNotEmpty() }?.let { origin to it }
    }

    /** Removes these downloads, finished or not. */
    fun remove(context: Context, ids: List<String>) = get(context).shelves.forEach { shelf ->
        // A download is on one shelf; asking the other to remove it does nothing.
        ids.forEach { DownloadService.sendRemoveDownload(context, shelf.service, it, false) }
    }

    /**
     * Carries on with downloads left queued when Muon last closed. The service stops itself again
     * once there is nothing to do.
     */
    fun resume(context: Context) {
        get(context).shelves.forEach { runCatching { DownloadService.start(context, it.service) } }
    }

    /** Moves one download at a time, behind everything else. */
    private val mover = Executors.newSingleThreadExecutor()

    /** How many finished downloads are on the card ([card]) or the phone. Reads the index. */
    fun downloadsOn(context: Context, card: Boolean): Int {
        val store = get(context)
        val shelf = (if (card) store.card else store.phone) ?: return 0
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
        val card = store.card ?: return
        val from = if (toCard) store.phone else card
        val to = if (toCard) card else store.phone
        val main = Handler(Looper.getMainLooper())
        mover.execute {
            val downloads = ArrayList<Download>()
            runCatching { from.manager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { while (it.moveToNext()) downloads += it.download } }
            main.post { DownloadMarks.moving = 0 to downloads.size }
            downloads.forEachIndexed { index, download ->
                if (runCatching { copy(download, from, to) }.isSuccess)
                    main.post { DownloadService.sendAddDownload(context, to.service, download.request, false) }
                main.post { DownloadMarks.moving = index + 1 to downloads.size }
            }
            main.post { DownloadMarks.moving = null }
        }
    }

    /** Writes one finished download's bytes from [from]'s cache into [to]'s, under the same key. */
    private fun copy(download: Download, from: Shelf, to: Shelf) {
        val id = download.request.id
        val length = ContentMetadata.getContentLength(from.cache.getContentMetadata(id))
        require(length != C.LENGTH_UNSET.toLong() && from.cache.isCached(id, 0, length)) { "Not fully downloaded" }
        // No upstream: a byte missing from the source fails the copy rather than reaching the network.
        val reader = CacheDataSource.Factory().setCache(from.cache).setCacheWriteDataSinkFactory(null)
        val writer = CacheDataSource.Factory().setCache(to.cache).setUpstreamDataSourceFactory(reader).createDataSourceForDownloading()
        CacheWriter(writer, DataSpec.Builder().setUri(download.request.uri).setKey(id).build(), null, null).cache()
        to.cache.applyContentMetadataMutations(id, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), length))
    }

    fun removeAll(context: Context) = get(context).shelves.forEach {
        DownloadService.sendRemoveAllDownloads(context, it.service, false)
    }
}

/** The card's name as Android shows it ("SanDisk SD card"), or a plain one. */
internal fun cardDescription(context: Context, folder: File): String = runCatching {
    context.getSystemService(android.os.storage.StorageManager::class.java)?.getStorageVolume(folder)?.getDescription(context)
}.getOrNull() ?: "SD card"
