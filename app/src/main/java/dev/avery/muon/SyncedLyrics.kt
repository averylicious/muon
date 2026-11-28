package dev.avery.muon

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** One timed line of synced lyrics: when it starts, in milliseconds from the song's start, and its words. */
data class LyricLine(val atMs: Long, val text: String)

private val STAMP = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

/**
 * Parses LRC. Each line starts with one or more `[mm:ss.xx]` stamps (several repeat the line); a
 * `[offset:±ms]` tag moves every line, positive meaning earlier, as LRC defines it; other tags are
 * metadata and ignored. Lines come back in time order, never before 0, with blank ones kept as pauses.
 * Null when nothing in [text] is timed.
 */
internal fun parseLrc(text: String): List<LyricLine>? {
    var offset = 0L
    val lines = mutableListOf<LyricLine>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        OFFSET.find(line)?.let { offset = it.groupValues[1].toLong(); continue }
        var rest = line
        val starts = mutableListOf<Long>()
        while (true) {
            val stamp = STAMP.find(rest) ?: break
            val (min, sec, frac) = stamp.destructured
            val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.toLong() }
            starts += (min.toLong() * 60 + sec.toLong()) * 1000 + ms
            rest = rest.substring(stamp.range.last + 1)
        }
        starts.forEach { lines += LyricLine((it - offset).coerceAtLeast(0), rest.trim()) }
    }
    return lines.takeIf { it.isNotEmpty() }?.sortedBy { it.atMs }
}

/** The line being sung at [positionMs]: the last one that has started, or -1 before the first. */
internal fun currentLine(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].atMs <= positionMs) { found = mid; low = mid + 1 } else high = mid - 1
    }
    return found
}

/** How far LRCLIB's length for a search result may be from the song's own before it is another recording. */
internal const val SYNCED_LENGTH_TOLERANCE_S = 8

/**
 * From LRCLIB's search results, the first with synced lyrics whose length is within
 * [SYNCED_LENGTH_TOLERANCE_S] of [durationS] (any length when the song's is unknown, 0).
 */
internal fun pickSearchResult(results: List<Pair<Double, String?>>, durationS: Int): String? =
    results.firstOrNull { (length, synced) ->
        !synced.isNullOrBlank() && (durationS <= 0 || abs(length - durationS) <= SYNCED_LENGTH_TOLERANCE_S)
    }?.second

/**
 * Synced lyrics from LRCLIB (lrclib.net), the one online source for now (#797). Only ever asked when
 * the user has turned it on: it is sent the song's title, artist, album and length.
 */
internal object Lrclib {
    private val BASE = "https://lrclib.net".toHttpUrl()
    // LRCLIB asks clients to name themselves.
    private const val AGENT = "Muon (https://github.com/averylicious/muon)"
    private const val RESPONSE_BYTES = 1L * 1024 * 1024

    /** LRC text, or null when LRCLIB has no synced lyrics for the song. Throws on a network failure. */
    suspend fun synced(title: String, artist: String, album: String, durationS: Int): String? {
        val exact = BASE.newBuilder().addPathSegments("api/get")
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist)
            .apply { if (album.isNotBlank()) addQueryParameter("album_name", album) }
            .apply { if (durationS > 0) addQueryParameter("duration", durationS.toString()) }.build()
        get(exact)?.let { JSONObject(it) }?.let { o -> if (!o.isNull("syncedLyrics")) return o.getString("syncedLyrics").ifBlank { null } }
        val search = BASE.newBuilder().addPathSegments("api/search")
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist).build()
        val results = get(search)?.let { JSONArray(it) } ?: return null
        return pickSearchResult((0 until results.length()).map { i ->
            val o = results.getJSONObject(i)
            o.optDouble("duration", 0.0) to (if (o.isNull("syncedLyrics")) null else o.optString("syncedLyrics"))
        }, durationS)
    }

    /** The body of a successful answer, or null for 404 (LRCLIB's "not found"). */
    private suspend fun get(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", AGENT).build()
        Transport.metadataClient.newCall(request).readCancellable { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw java.io.IOException("LRCLIB returned HTTP ${response.code}")
                else -> response.peekBody(RESPONSE_BYTES).string()
            }
        }
    }
}

/** A song's name for the synced-lyrics cache: what LRCLIB matched it by, so a re-tagged song looks again. */
internal fun syncedLyricsKey(title: String, artist: String, durationS: Int): String =
    MessageDigest.getInstance("SHA-256").digest("${title.lowercase()}\n${artist.lowercase()}\n$durationS".toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Synced lyrics already found, kept in the app's cache, which Android may clear when space runs short. */
internal class SyncedLyricsCache(context: Context) {
    private val dir = File(context.cacheDir, "synced-lyrics")
    fun read(key: String): String? = File(dir, "$key.lrc").takeIf { it.isFile }?.readText()
    fun write(key: String, lrc: String) {
        dir.mkdirs()
        File(dir, "$key.lrc").writeText(lrc)
    }
}

/** Off by default (#797): nothing is sent to LRCLIB until the user turns it on. */
@Stable
class LyricsPreferences(private val prefs: SharedPreferences) {
    var online by mutableStateOf(prefs.getBoolean(KEY_ONLINE, false))
        private set
    /** The Lyrics view's "Find synced lyrics online" hint, once closed, stays closed. */
    var hintDismissed by mutableStateOf(prefs.getBoolean(KEY_HINT_DISMISSED, false))
        private set

    fun chooseOnline(enabled: Boolean) {
        if (enabled == online) return
        online = enabled
        prefs.edit().putBoolean(KEY_ONLINE, enabled).apply()
    }

    fun dismissHint() {
        hintDismissed = true
        prefs.edit().putBoolean(KEY_HINT_DISMISSED, true).apply()
    }

    private companion object {
        const val KEY_ONLINE = "online_synced_lyrics"
        const val KEY_HINT_DISMISSED = "synced_lyrics_hint_dismissed"
    }
}

@Composable
fun rememberLyricsPreferences(): LyricsPreferences {
    val context = LocalContext.current
    return remember(context) { LyricsPreferences(context.getSharedPreferences("lyrics", Context.MODE_PRIVATE)) }
}
