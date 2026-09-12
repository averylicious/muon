package dev.avery.muon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private val artCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}
private suspend fun fetchArtwork(url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        Transport.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            val source = body.source()
            if (source.request(4 * 1024 * 1024 + 1L)) return@use null
            val bytes = source.readByteArray()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return@use null
            options.inJustDecodeBounds = false
            options.inSampleSize = maxOf(options.outWidth / 1000, options.outHeight / 1000, 1)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { artCache.put(url, it) }
        }
    }.getOrNull()
}

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier) {
    // Seeded from the cache during composition so art already decoded draws in the same frame
    // instead of flashing the placeholder every time a row scrolls back into view.
    var bitmap by remember(url) { mutableStateOf(url?.let(artCache::get)) }
    LaunchedEffect(url) {
        if (url != null && bitmap == null) bitmap = fetchArtwork(url)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text("♪", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
    }
}
