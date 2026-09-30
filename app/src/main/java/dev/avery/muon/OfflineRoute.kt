package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata

/** The existing retained-copy routing, separated so disposable real cache/index fixtures can inspect it. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun routeOfflineRequest(spec: DataSpec, phone: Shelf, shelves: List<Shelf>, offline: Boolean): Pair<Shelf, DataSpec> {
    val found = downloadForStream(spec.uri.scheme, spec.uri.encodedAuthority, spec.uri.path) ?: return phone to spec
    shelves.firstOrNull { it.completed(found.first) }?.let {
        return it to spec.buildUpon().setUri(Uri.parse(found.second)).setKey(found.first).build()
    }
    // Recent listening uses the phone only offline; reachable Tauon still serves the original.
    if (offline && hasPlayedCopy(phone.cache, found.first))
        return phone to spec.buildUpon().setUri(Uri.parse(found.second)).setKey(playedKey(found.first)).build()
    return phone to spec
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun hasPlayedCopy(cache: Cache, id: String): Boolean = runCatching {
    val key = playedKey(id)
    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
    length != C.LENGTH_UNSET.toLong() && cache.isCached(key, 0, length)
}.getOrDefault(false)
