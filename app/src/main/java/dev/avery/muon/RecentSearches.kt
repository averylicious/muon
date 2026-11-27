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

/** How many recent searches are kept. */
internal const val RECENT_SEARCHES_KEPT = 8

/** Shorter than this is a search still being typed, not one worth offering again. */
internal const val RECENT_SEARCH_MIN_LENGTH = 2

/**
 * The list after [query] was searched: it moves to the front, an earlier copy of it (ignoring case and
 * surrounding space) goes, and the list keeps at most [kept]. A blank or very short query changes nothing.
 */
internal fun recentSearchesAfter(recent: List<String>, query: String, kept: Int = RECENT_SEARCHES_KEPT): List<String> {
    val clean = query.trim().replace(Regex("\\s+"), " ")
    if (clean.length < RECENT_SEARCH_MIN_LENGTH) return recent
    return (listOf(clean) + recent.filterNot { it.equals(clean, ignoreCase = true) }).take(kept)
}

/** The list without [query], as when one chip is removed. */
internal fun recentSearchesWithout(recent: List<String>, query: String): List<String> =
    recent.filterNot { it.equals(query.trim(), ignoreCase = true) }

/** Stored one per line: a search box gives no way to type a line break. */
internal fun encodeRecentSearches(recent: List<String>): String = recent.joinToString("\n")

internal fun decodeRecentSearches(stored: String?): List<String> =
    stored.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        .take(RECENT_SEARCHES_KEPT)

/**
 * The searches made on this phone, most recent first, offered as chips on the Search tab (the Refined
 * mockup). They stay in Muon's private preferences, which are never backed up or synced
 * (`allowBackup="false"`), and Clear removes them all.
 */
@Stable
internal class RecentSearches(private val prefs: SharedPreferences) {
    var items by mutableStateOf(decodeRecentSearches(prefs.getString(KEY, null)))
        private set

    /** A search the user acted on: submitted from the keyboard, or one whose result they opened. */
    fun remember(query: String) = store(recentSearchesAfter(items, query))

    fun forget(query: String) = store(recentSearchesWithout(items, query))

    fun clear() = store(emptyList())

    private fun store(next: List<String>) {
        if (next == items) return
        items = next
        prefs.edit().putString(KEY, encodeRecentSearches(next)).apply()
    }

    private companion object { const val KEY = "recent" }
}

@Composable
internal fun rememberRecentSearches(): RecentSearches {
    val context = LocalContext.current
    return remember(context) { RecentSearches(context.getSharedPreferences("search", Context.MODE_PRIVATE)) }
}
