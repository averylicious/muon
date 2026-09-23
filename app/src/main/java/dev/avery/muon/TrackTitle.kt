package dev.avery.muon

/** Resolve missing tags once so library, search and playback share the same display title. */
internal fun trackDisplayTitle(title: String?, serverPath: String?): String {
    if (!title.isNullOrBlank()) return title
    // Tauon can run on either platform. Keep only its filename, never the server directory.
    val filename = serverPath.orEmpty().trim().substringAfterLast('/').substringAfterLast('\\').trim()
    return filename.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "Untitled"
}
