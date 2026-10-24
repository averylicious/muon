package dev.avery.muon

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Volume normalization (#97): a song's ReplayGain track gain, as `rsgain` wrote it into the FLAC on the
 * desktop. Tauon streams the file's own bytes and copies tags into its Opus downloads. Media3 exposes
 * FLAC/Ogg/Opus comments as VorbisComment and MP3 ID3 user text as TextInformationFrame.
 *
 * The gain is applied as the player's own volume, never the phone's media volume, and that volume can
 * only lower a song. So a song is turned down by its gain, never up: a quiet song keeps its level
 * rather than being raised past its peak, which is also the clipping protection. Album gain is left for
 * later.
 */
internal data class TrackLoudness(val gainDb: Float, val peak: Float? = null)

/** Gains outside this range are a damaged tag rather than a real song. */
private const val GAIN_LIMIT_DB = 51f

/** "-12.62 dB", "+1.5 dB" or a bare number; null for anything else. */
internal fun parseGainDb(text: String?): Float? =
    text?.trim()?.removeSuffix("dB")?.removeSuffix("DB")?.trim()?.removePrefix("+")?.toFloatOrNull()
        ?.takeIf { it.isFinite() && it in -GAIN_LIMIT_DB..GAIN_LIMIT_DB }

/**
 * The track gain from a song's tags, keyed in upper case (Media3 does this for Vorbis comments).
 * REPLAYGAIN_TRACK_GAIN wins; an Opus R128_TRACK_GAIN (Q7.8, relative to -23 LUFS) is moved to
 * ReplayGain's -18 LUFS reference by adding 5 dB.
 */
internal fun trackLoudness(tags: Map<String, String>): TrackLoudness? {
    val peak = tags["REPLAYGAIN_TRACK_PEAK"]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
    parseGainDb(tags["REPLAYGAIN_TRACK_GAIN"])?.let { return TrackLoudness(it, peak) }
    val r128 = tags["R128_TRACK_GAIN"]?.trim()?.toIntOrNull() ?: return null
    return TrackLoudness(r128 / 256f + 5f).takeIf { it.gainDb in -GAIN_LIMIT_DB..GAIN_LIMIT_DB }
}

/** The tags among a track format's metadata, or null when it has no ReplayGain. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun trackLoudness(metadata: Metadata?): TrackLoudness? {
    metadata ?: return null
    val tags = buildMap<String, String> {
        for (i in 0 until metadata.length()) when (val entry = metadata.get(i)) {
            is VorbisComment -> {
                // Media3 already normalizes Xiph keys to ASCII upper case.
                val key = entry.key
                if (key == "REPLAYGAIN_TRACK_GAIN" || key == "REPLAYGAIN_TRACK_PEAK" || key == "R128_TRACK_GAIN")
                    put(key, entry.value)
            }
            is TextInformationFrame -> if (entry.id == "TXXX") {
                val key = entry.description?.uppercase(Locale.ROOT)
                // rsgain ID3 uses scalar user-text values. Never interpret a title, album gain,
                // ambiguous multi-value frame or an Opus-only R128 field as an MP3 track gain.
                if (key == "REPLAYGAIN_TRACK_GAIN" || key == "REPLAYGAIN_TRACK_PEAK")
                    entry.values.singleOrNull()?.let { put(key, it) }
            }
        }
    }
    return trackLoudness(tags)
}

/** Current selected audio only; no remembered ID or listener-order cache. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun selectedTrackLoudness(tracks: Tracks): TrackLoudness? =
    tracks.groups.asSequence().filter { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        .flatMap { group -> (0 until group.length).asSequence().filter(group::isTrackSelected)
            .map { group.getTrackFormat(it) } }
        .firstNotNullOfOrNull { trackLoudness(it.metadata) }

/**
 * The gain actually applied, in dB: the track gain, kept low enough that the peak stays under full
 * scale, and never above 0 dB since the player's volume cannot raise a song.
 */
internal fun appliedGainDb(loudness: TrackLoudness): Float {
    val headroom = loudness.peak?.let { -20f * log10(it) } ?: Float.MAX_VALUE
    return minOf(loudness.gainDb, headroom, 0f)
}

/** The middle of the gains seen so far, for a song with none of its own; null before any. */
internal fun typicalGainDb(gains: Collection<Float>): Float? {
    if (gains.isEmpty()) return null
    val sorted = gains.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}

/** The player volume for a gain in dB. */
internal fun volumeForGain(gainDb: Float): Float = 10f.pow(gainDb / 20f).coerceIn(0f, 1f)

/**
 * Whether normalization is on, and the gain last read for each song.
 *
 * Remembered gains provide a library estimate and continuity for saved-copy playback. A live
 * Tauon ID can be reused for different audio, so its old exact gain is not proof of the current
 * song's level. Live playback uses current tags, or the library's typical gain while tags are absent.
 * An untagged song therefore gets an estimate rather than the previous song's exact gain. Off by
 * default: turning it on makes most modern songs quieter.
 */
internal class ReplayGainSettings(private val prefs: SharedPreferences) {
    var enabled by mutableStateOf(enabledFromPreferences())
        private set

    fun choose(on: Boolean) {
        if (on == enabled) return
        enabled = on
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** Re-reads the switch, for the playback service when the app changes it. */
    fun reload() { enabled = enabledFromPreferences() }

    fun remember(mediaId: String, gainDb: Float) {
        if (storedGain(mediaId) != gainDb) {
            prefs.edit().putFloat(gainKey(mediaId), gainDb).apply()
            typical = null
        }
    }

    // Worked out from every remembered gain when first needed, and again after one changes.
    private var typical: Float? = null
    private fun typicalGain(): Float? = typical ?: typicalGainDb(prefs.all.mapNotNull { (key, value) ->
        (value as? Float)?.takeIf { key.startsWith(GAIN_PREFIX) && it.isFinite() }
    }).also { typical = it }

    fun gainFor(mediaId: String?): Float? =
        mediaId?.let(::storedGain)?.takeIf { !it.isNaN() }

    // A mismatched local preference type is an absent value, like the playback-mode settings.
    // Reading does not delete it; a later user toggle or parsed gain writes the expected type.
    private fun enabledFromPreferences(): Boolean = try {
        prefs.getBoolean(KEY_ENABLED, false)
    } catch (_: ClassCastException) { false }

    private fun storedGain(mediaId: String): Float = try {
        prefs.getFloat(gainKey(mediaId), Float.NaN)
    } catch (_: ClassCastException) { Float.NaN }

    /** Stored exact hints are used only after playbackGainFor validates a saved-copy handle. */
    private fun effectiveGainFor(mediaId: String?): Float? = gainFor(mediaId) ?: typicalGain()

    /**
     * Current selected tags take priority. Only a canonical saved-copy handle may reuse its exact
     * stored hint; a live numeric Tauon ID is not a durable audio identity. History stays untouched
     * and still contributes to the same library estimate. This returns a gain even when disabled,
     * so the playback notice can explain what the switch would do.
     */
    fun playbackGainFor(mediaId: String?, selectedGainDb: Float?): Float? =
        selectedGainDb ?: effectiveGainFor(mediaId?.takeIf { SavedRef.parse(it) != null })

    /** Same selected-audio policy as the notice; disabled always returns unity. */
    fun volumeFor(mediaId: String?, selectedGainDb: Float? = null): Float =
        if (!enabled) 1f else playbackGainFor(mediaId, selectedGainDb)?.let(::volumeForGain) ?: 1f

    companion object {
        const val FILE = "loudness"
        const val KEY_ENABLED = "enabled"
        private const val GAIN_PREFIX = "gain:"
        private fun gainKey(mediaId: String) = GAIN_PREFIX + mediaId
    }
}

@androidx.compose.runtime.Composable
internal fun rememberReplayGainSettings(): ReplayGainSettings {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        ReplayGainSettings(context.getSharedPreferences(ReplayGainSettings.FILE, android.content.Context.MODE_PRIVATE))
    }
}

/** Current selected audio only; no persistent per-song missing-tag index or metadata scan. */
internal enum class LoudnessSource { Checking, Missing, Tagged, Estimated }
internal data class LoudnessPlayback(val mediaId: String? = null, val source: LoudnessSource = LoudnessSource.Checking,
    val playing: Boolean = false) {
    val usable: Boolean get() = source == LoudnessSource.Tagged || source == LoudnessSource.Estimated
    val missingTags: Boolean get() = source == LoudnessSource.Missing || source == LoudnessSource.Estimated
}
internal object PlaybackLoudness {
    var current by mutableStateOf(LoudnessPlayback())
}
internal fun loudnessPlayback(mediaId: String?, checked: Boolean, tagged: Boolean, gain: Float?, playing: Boolean) =
    LoudnessPlayback(mediaId, when {
        mediaId == null || !checked -> LoudnessSource.Checking
        tagged -> LoudnessSource.Tagged
        gain != null -> LoudnessSource.Estimated
        else -> LoudnessSource.Missing
    }, playing)

/** A bounded recent-song window, not a growing set of the whole library. Not persisted. */
internal class LoudnessNoticeHistory(private val capacity: Int = 64) {
    init { require(capacity > 0) }
    private val recent = LinkedHashSet<String>()
    fun shouldShow(state: LoudnessPlayback): Boolean {
        val id = state.mediaId ?: return false
        if (!state.playing || !state.missingTags || !recent.add(id)) return false
        if (recent.size > capacity) recent.remove(recent.first())
        return true
    }
}
