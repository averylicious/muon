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

private fun memoryKey(url: String, size: Int) = "$url#$size"

/**
 * A small decode of [url] for reading colours from (Now Playing's theme): the mini player's size, so
 * it is usually already in memory. Null if it cannot be had.
 */
internal suspend fun artworkBitmap(context: android.content.Context, url: String): Bitmap? =
    artCache.get(memoryKey(url, 128)) ?: fetchArtwork(url, 128, ArtworkStore.disk(context))

/** A picture decoded for one of [ARTWORK_SIZES]. */
private class LoadedArtwork(val bitmap: Bitmap, val size: Int)

/** The largest decode of [url] already in memory, whatever size it was made for. */
private fun cachedArtwork(url: String): LoadedArtwork? {
    for (i in ARTWORK_SIZES.indices.reversed()) {
        val size = ARTWORK_SIZES[i]
        artCache.get(memoryKey(url, size))?.let { return LoadedArtwork(it, size) }
    }
    return null
}

/**
 * Memory first (checked by the caller), then disk, then the server. Only a picture that decodes is
 * stored, so a broken response is never kept to be served again; a stored file that no longer decodes
 * is fetched afresh and replaced. Disk is used only when the address belongs to a known song.
 *
 * Each [size] is its own entry in memory and on disk: a list row keeps its small decode, the player
 * keeps the server's picture as it came.
 */
private suspend fun fetchArtwork(url: String, size: Int, disk: ArtworkDiskCache): Bitmap? = withContext(Dispatchers.IO) {
    val key = memoryKey(url, size)
    val identity = ArtworkIdentities.of(url)
    val diskKey = if (size >= ARTWORK_ORIGINAL_SIZE) url else key
    if (identity != null) disk.read(diskKey, identity)?.let { decodeArtwork(it, size) }?.let { art ->
        artCache.put(key, art)
        return@withContext art
    }
    // A downloaded song's cover is kept with the download, so it shows offline and after Disconnect.
    OfflineStore.current()?.art?.forArtwork(url)?.let { decodeArtwork(it, size) }?.let { art ->
        artCache.put(key, art)
        return@withContext art
    }
    runCatching {
        Transport.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            val source = body.source()
            if (source.request(4 * 1024 * 1024 + 1L)) return@use null
            val bytes = source.readByteArray()
            decodeArtwork(bytes, size)?.also { art ->
                artCache.put(key, art)
                if (identity != null) disk.write(diskKey, identity,
                    if (size >= ARTWORK_ORIGINAL_SIZE) bytes else encodeArtwork(art))
            }
        }
    }.getOrNull()
}

/**
 * Forgets all artwork, in memory and on disk, as when leaving a server: nothing from it is shown again
 * or kept. The disk part touches files, so call it off the main thread.
 */
internal fun forgetArtwork(disk: ArtworkDiskCache) {
    artCache.evictAll()
    disk.clear()
}

/**
 * A picture decoded for the size it is drawn at. Until it is measured nothing is fetched; a decode
 * already in memory at any size is drawn at once, and replaced by a sharper one only if it is smaller
 * than the space it now fills.
 */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier) {
    // Seeded from the cache during composition so art already decoded draws in the same frame
    // instead of flashing the placeholder every time a row scrolls back into view.
    var loaded by remember(url) { mutableStateOf(url?.let(::cachedArtwork)) }
    var side by remember { mutableIntStateOf(0) }
    val size = artworkSize(side)
    val context = LocalContext.current
    val disk = remember(context) { ArtworkStore.disk(context) }
    // Already in memory: it was drawn in this frame, so there is nothing to fade in.
    val fromCache = remember(url) { loaded != null }
    LaunchedEffect(url, size) {
        val shown = loaded
        if (url == null || size == 0 || (shown != null && shown.size >= size)) return@LaunchedEffect
        // Something is already drawn, and a picture growing into a page passes through every size
        // on the way: only the size it settles at is worth decoding.
        if (shown != null) delay(ARTWORK_SETTLE_MS)
        fetchArtwork(url, size, disk)?.let { loaded = LoadedArtwork(it, size) }
    }
    Box(modifier.onSizeChanged { side = minOf(it.width, it.height) }
        .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Crossfade(loaded, animationSpec = if (fromCache) snap() else motionMedium(), label = "artwork") { art ->
            if (art != null) Image(art.bitmap.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("♪", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private const val ARTWORK_SETTLE_MS = 150L
