package dev.avery.muon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Transport {
    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
}
data class TauonPlaylist(val id: String, val name: String, val count: Int)
data class TauonTrack(
    val id: Long, val title: String, val artist: String, val album: String,
    val durationMs: Long, val playable: Boolean, val hasLyrics: Boolean,
    val albumArtist: String = artist, val trackNumber: String = "",
)
class TauonApi(val endpoint: ServerEndpoint) {
    private suspend fun json(path: String): JSONObject = withContext(Dispatchers.IO) {
        Transport.client.newCall(Request.Builder().url(endpoint.url(path)).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Tauon returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty Tauon response")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val stream = body.byteStream()
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                if (output.size() + count > 16 * 1024 * 1024) throw IOException("Playlist response exceeds 16 MiB")
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            parseTauonJson(bytes.toString(Charsets.UTF_8))
        }
    }
    suspend fun connect() {
        require(json("/api1/version").getInt("version") == 1) { "Unsupported Tauon API version" }
    }
    suspend fun playlists(): List<TauonPlaylist> {
        val a = json("/api1/playlists").getJSONArray("playlists")
        return List(a.length()) { i -> a.getJSONObject(i).let {
            val id = it.getString("id")
            require(id.matches(Regex("[0-9]+"))) { "Invalid playlist identifier" }
            TauonPlaylist(id, it.getString("name"), it.getInt("count"))
        } }
    }
    suspend fun tracks(playlistId: String): List<TauonTrack> {
        require(playlistId.matches(Regex("[0-9]+")))
        val a = json("/api1/tracklist/$playlistId").getJSONArray("tracks")
        return List(a.length()) { i -> a.getJSONObject(i).let {
            val id = it.getLong("id"); require(id >= 0)
            val artist = it.optString("artist")
            TauonTrack(id, trackDisplayTitle(it.opt("title") as? String, it.opt("path") as? String), artist,
                it.optString("album"), it.optLong("duration"),
                it.optBoolean("can_download", false), it.optBoolean("has_lyrics"),
                albumArtist = albumArtistTag(it.opt("album_artist"), artist),
                trackNumber = trackNumberTag(it.opt("track_number")))
        } }
    }
    suspend fun lyrics(trackId: Long): String {
        require(trackId >= 0)
        return json("/api1/lyrics/$trackId").optString("lyrics_text")
    }
}
