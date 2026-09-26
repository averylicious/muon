package dev.avery.muon

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
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
    class Store(val cache: SimpleCache, val manager: DownloadManager)

    @Volatile private var store: Store? = null

    @Synchronized
    fun get(context: Context): Store = store ?: create(context.applicationContext).also { store = it }

    private fun create(context: Context): Store {
        val database = StandaloneDatabaseProvider(context)
        // Downloads stay until removed: nothing is evicted behind the user's back.
        val cache = SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), database)
        val manager = DownloadManager(context, database, cache, OkHttpDataSource.Factory(Transport.client),
            Executors.newFixedThreadPool(2))
        manager.maxParallelDownloads = 2
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
            DownloadMarks.bytes = sizes.values.sum()
        }
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(m: DownloadManager, download: Download, finalException: Exception?) = record(download)
            override fun onDownloadRemoved(m: DownloadManager, download: Download) {
                DownloadMarks.marks.remove(download.request.id)
                sizes.remove(download.request.id)
                DownloadMarks.bytes = sizes.values.sum()
            }
        })
        // What was downloaded before this launch, read off the main thread and reported on it.
        val main = Handler(Looper.getMainLooper())
        Executors.newSingleThreadExecutor().execute {
            val known = ArrayList<Download>()
            runCatching { manager.downloadIndex.getDownloads().use { while (it.moveToNext()) known += it.download } }
            main.post { known.forEach(::record) }
        }
        return Store(cache, manager)
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
            if (found == null || !downloaded(context, found.first)) spec
            else spec.buildUpon().setUri(Uri.parse(found.second)).setKey(found.first).build()
        }
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
