package dev.avery.muon

import java.util.Locale

/**
 * Artist credits as a person reads them: Tauon's tag keeps several artists in one field separated by
 * `;` ("A Flow Mobz; Luna Blake"), the same separator the artist grouping splits on, and this shows
 * them as "A Flow Mobz, Luna Blake" (#119). Blank credits and repeats (ignoring case) are dropped.
 * Only for display: search and grouping keep reading the tag as it is.
 */
internal fun displayCredits(artist: String): String =
    artist.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }.joinToString(", ")

/**
 * The album line under Now Playing's title, or null when it would add nothing: blank, or the same as
 * the title, as it is for most singles (#118). Compared trimmed and ignoring case.
 */
internal fun albumLine(title: String?, album: String?): String? {
    val shown = album?.trim().orEmpty()
    if (shown.isEmpty() || shown.equals(title?.trim(), ignoreCase = true)) return null
    return shown
}
