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
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private val artCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}
private fun decodeArtwork(bytes: ByteArray): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inJustDecodeBounds = false
    options.inSampleSize = maxOf(options.outWidth / 1000, options.outHeight / 1000, 1)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/**
 * Memory first (checked by the caller), then disk, then the server. Only a picture that decodes is
 * stored, so a broken response is never kept to be served again; a stored file that no longer decodes
 * is fetched afresh and replaced. Disk is used only when the address belongs to a known song.
 */
private suspend fun fetchArtwork(url: String, disk: ArtworkDiskCache): Bitmap? = withContext(Dispatchers.IO) {
    val identity = ArtworkIdentities.of(url)
    if (identity != null) disk.read(url, identity)?.let(::decodeArtwork)?.let { art ->
        artCache.put(url, art)
        return@withContext art
    }
    runCatching {
        Transport.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            val source = body.source()
            if (source.request(4 * 1024 * 1024 + 1L)) return@use null
            val bytes = source.readByteArray()
            decodeArtwork(bytes)?.also { art ->
                artCache.put(url, art)
                if (identity != null) disk.write(url, identity, bytes)
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

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier) {
    // Seeded from the cache during composition so art already decoded draws in the same frame
    // instead of flashing the placeholder every time a row scrolls back into view.
    var bitmap by remember(url) { mutableStateOf(url?.let(artCache::get)) }
    val context = LocalContext.current
    val disk = remember(context) { ArtworkStore.disk(context) }
    // Already in memory: it was drawn in this frame, so there is nothing to fade in.
    val fromCache = remember(url) { bitmap != null }
    LaunchedEffect(url) {
        if (url != null && bitmap == null) bitmap = fetchArtwork(url, disk)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Crossfade(bitmap, animationSpec = if (fromCache) snap() else motionMedium(), label = "artwork") { art ->
            if (art != null) Image(art.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("♪", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
