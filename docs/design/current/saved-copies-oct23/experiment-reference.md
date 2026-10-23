# Experimental design reference (read-only)

Live remote M3E head verified 2026-10-23: `e1bf045c1fa7139c4966e480f2f06941a703ddfc`. Target RC: `467ab92885dd2c26735245ebcdedc9b7a4d755e9`. These excerpts are design references only. Main must retain stable Material3 1.4.0. Do not copy alpha-only APIs.

## app/src/main/java/dev/avery/muon/MuonTheme.kt

```kotlin
package dev.avery.muon

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val MuonDarkColors = darkColorScheme(
    primary = Color(0xFFBCF580),
    onPrimary = Color(0xFF152700),
    background = Color(0xFF101411),
    surface = Color(0xFF101411),
    surfaceVariant = Color(0xFF252D27),
    secondaryContainer = Color(0xFF283E2A),
    onSecondaryContainer = Color(0xFFD3F8B5),
    onSurface = Color(0xFFF3F4EF),
    onSurfaceVariant = Color(0xFFA9B4A9),
)

private val MuonLightColors = lightColorScheme(
    primary = Color(0xFF3D6800),
    onPrimary = Color.White,
    background = Color(0xFFF9FAF4),
    surface = Color(0xFFF9FAF4),
    surfaceVariant = Color(0xFFE1E8DA),
    secondaryContainer = Color(0xFFD9E7CB),
    onSecondaryContainer = Color(0xFF172E0B),
    onSurface = Color(0xFF1A1C18),
    onSurfaceVariant = Color(0xFF454A40),
)

@Composable
fun MuonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    blackSurfaces: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> MuonDarkColors
        else -> MuonLightColors
    }
    // Only the ground goes black. Containers keep their own colour so cards, the mini player and
    // artwork placeholders stay visible against it, and accents are left alone entirely.
    val scheme = if (blackSurfaces && darkTheme) {
        colors.copy(background = Color.Black, surface = Color.Black)
    } else colors
    // Material's own controls (switches, sliders, sheets, the pull-to-refresh indicator) take their
    // springs from the motion scheme; the expressive one is springier (the Canary experiment).
    MaterialTheme(colorScheme = scheme, typography = MuonTypography,
        motionScheme = if (Expressive.motion) MotionScheme.expressive() else MotionScheme.standard(),
        content = content)
}
```

## app/src/main/java/dev/avery/muon/Artwork.kt

```kotlin
package dev.avery.muon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request

private val artCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/**
 * The sizes a picture is decoded at, in pixels along its shorter side. A picture is decoded for the
 * smallest of these that covers the space it is drawn in, so a list row keeps a couple of hundred
 * pixels rather than the server's thousand, and a few steps keep the memory cache from holding a copy
 * for every pixel a layout might measure. The largest is the server's own medium picture.
 */
internal val ARTWORK_SIZES = intArrayOf(64, 128, 192, 256, 384, 512, 768, 1024)

/** At or above this size the server's own bytes are kept on disk; below it, the smaller decode. */
internal const val ARTWORK_ORIGINAL_SIZE = 1024

/** The size to decode for a picture drawn [px] pixels along its shorter side; 0 until it is measured. */
internal fun artworkSize(px: Int): Int =
    if (px <= 0) 0 else ARTWORK_SIZES.firstOrNull { it >= px } ?: ARTWORK_SIZES.last()

/**
 * The power-of-two subsampling that still leaves the shorter side at least [target] pixels, so the
 * final scale down to size is at most a halving and stays sharp. A picture far longer than it is wide
 * is subsampled further, so its long side never reaches a bitmap much beyond [ARTWORK_MAX_SIDE].
 */
internal fun artworkSampleSize(width: Int, height: Int, target: Int): Int {
    val shorter = minOf(width, height)
    val longer = maxOf(width, height)
    var sample = 1
    while (shorter / (sample * 2) >= target) sample *= 2
    while (longer / sample > ARTWORK_MAX_SIDE) sample *= 2
    return sample
}

internal const val ARTWORK_MAX_SIDE = 2048

private fun decodeArtwork(bytes: ByteArray, target: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inJustDecodeBounds = false
    options.inSampleSize = artworkSampleSize(options.outWidth, options.outHeight, target)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    // Subsampling gets within a halving; filtering the rest keeps edges clean where a list row draws it.
    val shorter = minOf(decoded.width, decoded.height)
    if (shorter <= target) return decoded
    val scale = target.toFloat() / shorter
    val scaled = Bitmap.createScaledBitmap(decoded,
        (decoded.width * scale).roundToInt().coerceAtLeast(1), (decoded.height * scale).roundToInt().coerceAtLeast(1), true)
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

/** A small decode is kept on disk as itself, so a list's pictures do not each cost the full cover. */
private fun encodeArtwork(art: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
    art.compress(if (art.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
    out.toByteArray()
}

```

## app/src/main/java/dev/avery/muon/SongActions.kt

```kotlin
package dev.avery.muon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The album a song belongs to, for *Go to album*; none for a song without an album tag. */
internal fun songAlbum(track: TauonTrack, albums: List<LibraryAlbum>?): LibraryAlbum? {
    if (track.album.isBlank()) return null
    val key = trackAlbumKey(track)
    return albums?.firstOrNull { it.key == key }
}

/** Each artist a song is credited to, in credit order, for *Go to artist*; none for an untagged song. */
internal fun songArtists(track: TauonTrack, artists: List<LibraryArtist>?): List<LibraryArtist> {
    if (artists == null || track.artist.isBlank()) return emptyList()
    val byKey = trackArtistKeys(track)
    return byKey.mapNotNull { key -> artists.firstOrNull { it.key == key } }
}

/** The snackbar after a queue action, in the action's own words, naming the song. */
internal fun queuedMessage(title: String, next: Boolean): String {
    val name = "“${title.ifBlank { "Untitled" }}”"
    return if (next) "$name will play next" else "$name added to the queue"
}

/**
 * What can be done with one song (#46, mockup 14), opened by a long press on it: its cover, title,
 * artist and album repeated at the top, because the sheet covers the row; then Play next, Add to
 * queue, Go to album and Go to artist. [album] and [artists] leave out the page already on show, and
 * a song credited to several artists offers each. The sheet closes before the chosen action runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongActionsSheet(track: TauonTrack, endpoint: ServerEndpoint?, canQueue: Boolean,
    album: LibraryAlbum?, artists: List<LibraryArtist>, dismiss: () -> Unit,
    queue: (next: Boolean) -> Unit, goToAlbum: (LibraryAlbum) -> Unit, goToArtist: (LibraryArtist) -> Unit,
    download: DownloadMark? = null, canDownload: Boolean = false, toggleDownload: () -> Unit = {}) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    fun choose(action: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { dismiss(); action() }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(endpoint?.url("/api1/pic/medium/${track.id}"), Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(track.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                displayCredits(track.artist).ifBlank { null }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                track.album.trim().ifBlank { null }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
        Column(Modifier.padding(vertical = 8.dp)) {
            if (canQueue) {
                SheetAction("play-next", "Play next") { choose { queue(true) } }
                SheetAction("add-queue", "Add to queue") { choose { queue(false) } }
            }
            // Keep on the phone, or stop keeping it (#112).
            when (download) {
                DownloadMark.Done -> SheetAction("delete", "Remove download") { choose(toggleDownload) }
                DownloadMark.Queued, DownloadMark.Downloading -> SheetAction("close", "Cancel download") { choose(toggleDownload) }
                null -> if (canDownload) SheetAction("download", "Download") { choose(toggleDownload) }
            }
            album?.let { a -> SheetAction("album", "Go to album") { choose { goToAlbum(a) } } }
            artists.forEach { a ->
                SheetAction("artist", "Go to artist", if (artists.size > 1) artistLabel(a.name) else null) { choose { goToArtist(a) } }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SheetAction(icon: String, label: String, detail: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = detail?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { MuonIcon(icon) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    )
}
```
