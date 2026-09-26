package dev.avery.muon

import android.content.Context
import android.net.Uri
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
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import java.io.File
import java.util.concurrent.Executors

/**
 * The downloaded songs and the machinery that keeps them (#112): one Media3 [DownloadManager] over one
 * [SimpleCache] in the app's own storage, shared by the UI and the playback service in this process.
 * Created on the main thread, where the manager reports its changes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object OfflineStore {
    class Store(val cache: SimpleCache, val manager: DownloadManager, val art: DownloadArt,
        val played: PlayedSongEvictor, val prefs: android.content.SharedPreferences)

    /** Set while the library shows only what is on the phone; the player then also plays cached songs. */
    @Volatile var offline = false

    /** Copies played songs one at a time, behind playback. */
    private val copier = Executors.newSingleThreadExecutor()

    @Volatile private var store: Store? = null

    /** The store, if something has already made it; never makes one. */
    fun current(): Store? = store

    @Synchronized
    fun get(context: Context): Store = store ?: create(context.applicationContext).also { store = it }

    private fun create(context: Context): Store {
        val database = StandaloneDatabaseProvider(context)
        val prefs = context.getSharedPreferences("storage", Context.MODE_PRIVATE)
        val limit = prefs.getLong("cacheLimit", DEFAULT_CACHE_LIMIT)
        val main = Handler(Looper.getMainLooper())
        PlayedCacheState.limit = limit
        // Downloads stay until removed: only played-song copies are ever evicted, oldest first.
        val played = PlayedSongEvictor(limit) { used -> main.post { PlayedCacheState.used = used } }
        val cache = SimpleCache(File(context.filesDir, "downloads"), played, database)
        val manager = DownloadManager(context, database, cache, OkHttpDataSource.Factory(Transport.client),
            Executors.newFixedThreadPool(2))
        manager.maxParallelDownloads = 2
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
            if (mark == null) DownloadMarks.marks.remove(id) else DownloadMarks.marks[id] = mark
            if (mark == DownloadMark.Done) sizes[id] = download.bytesDownloaded else sizes.remove(id)
            // Also fills in the cover of a download made before covers were kept, when Tauon answers.
            if (mark != null && !art.has(id)) artwork.execute { art.fetch(id) }
            DownloadMarks.bytes = sizes.values.sum()
        }
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(m: DownloadManager, download: Download, finalException: Exception?) = record(download)
            override fun onDownloadRemoved(m: DownloadManager, download: Download) {
                artwork.execute { art.remove(download.request.id) }
                DownloadMarks.marks.remove(download.request.id)
                sizes.remove(download.request.id)
                DownloadMarks.bytes = sizes.values.sum()
            }
        })
        // What was downloaded before this launch, read off the main thread and reported on it.
        Executors.newSingleThreadExecutor().execute {
            val known = ArrayList<Download>()
            runCatching { manager.downloadIndex.getDownloads().use { while (it.moveToNext()) known += it.download } }
            main.post { known.forEach(::record) }
        }
        return Store(cache, manager, art, played, prefs)
    }

    /** Whether a finished download of [id] is on the phone. Called from the player's loading thread. */
    fun downloaded(context: Context, id: String): Boolean =
        runCatching { get(context).manager.downloadIndex.getDownload(id)?.state == Download.STATE_COMPLETED }.getOrDefault(false)

    /**
     * The player's data source: a song with a finished download plays its Opus copy from the phone,
     * with no network at all; every other request streams as before, and nothing streamed is written.
     */
    fun playbackSource(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val cached = CacheDataSource.Factory().setCache(get(context).cache)
            .setUpstreamDataSourceFactory(upstream).setCacheWriteDataSinkFactory(null)
        return ResolvingDataSource.Factory(cached) { spec ->
            val found = downloadForStream(spec.uri.scheme, spec.uri.encodedAuthority, spec.uri.path)
            when {
                found == null -> spec
                downloaded(context, found.first) -> spec.buildUpon().setUri(Uri.parse(found.second)).setKey(found.first).build()
                // Recent listening plays from the phone only when Tauon cannot be reached; at home the
                // original stream is always preferred.
                offline && playedCopy(context, found.first) -> spec.buildUpon().setUri(Uri.parse(found.second))
                    .setKey(playedKey(found.first)).build()
                else -> spec
            }
        }
    }

    /** Whether a complete played-song copy of [id] is on the phone. */
    fun playedCopy(context: Context, id: String): Boolean = runCatching {
        val cache = get(context).cache
        val key = playedKey(id)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        length != C.LENGTH_UNSET && cache.isCached(key, 0, length)
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
                CacheWriter(source, DataSpec.Builder().setUri(Uri.parse(url)).setKey(key).build(), null, null).cache()
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
            DownloadService.sendAddDownload(context, MuonDownloadService::class.java, request, false)
        }
    }

    /**
     * The songs downloaded from [origin], for the library shown when Tauon cannot be reached. Reads the
     * download index, so call it off the main thread, after [get].
     */
    fun downloadedSongs(context: Context, origin: String): List<TauonTrack> {
        val songs = ArrayList<TauonTrack>()
        runCatching {
            get(context).manager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    if (!download.request.id.startsWith("$origin/")) continue
                    decodeSong(download.request.data)?.takeIf { downloadId(origin, it.id) == download.request.id }?.let(songs::add)
                }
            }
        }
        // And the songs played recently enough to still have a complete copy.
        runCatching {
            val cache = get(context).cache
            val have = songs.mapTo(HashSet()) { it.id }
            cache.keys.filter { it.startsWith(playedKey("$origin/")) }.forEach { key ->
                val id = key.removePrefix(PLAYED_PREFIX)
                val song = cache.getContentMetadata(key).get(SONG_METADATA, null)?.let(::decodeSong) ?: return@forEach
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
        runCatching {
            get(context).manager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
                while (cursor.moveToNext()) {
                    val origin = cursor.download.request.id.substringBeforeLast('/', "")
                    if (origin.isNotEmpty()) origins[origin] = (origins[origin] ?: 0) + 1
                }
            }
        }
        val origin = origins.maxByOrNull { it.value }?.key ?: return null
        return downloadedSongs(context, origin).takeIf { it.isNotEmpty() }?.let { origin to it }
    }

    /** Removes these downloads, finished or not. */
    fun remove(context: Context, ids: List<String>) = ids.forEach {
        DownloadService.sendRemoveDownload(context, MuonDownloadService::class.java, it, false)
    }

    /**
     * Carries on with downloads left queued when Muon last closed. The service stops itself again
     * once there is nothing to do.
     */
    fun resume(context: Context) {
        runCatching { DownloadService.start(context, MuonDownloadService::class.java) }
    }

    fun removeAll(context: Context) = DownloadService.sendRemoveAllDownloads(context, MuonDownloadService::class.java, false)
}
