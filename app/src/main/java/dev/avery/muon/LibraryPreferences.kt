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

/** Which way the library is being browsed. Albums and Artists join this when they exist. */
enum class LibraryView { Songs, Playlists }

internal fun libraryViewFrom(stored: String?): LibraryView =
    if (stored == LibraryView.Playlists.name) LibraryView.Playlists else LibraryView.Songs

/**
 * The playlist a detail view should show, or `null` to go back to the list.
 *
 * A refresh can empty a playlist or remove it entirely, and a different server can reuse the same
 * identifier for something else. Rather than watch for those, the screen asks this question every
 * time it draws, so an identifier that no longer names a playlist with music in it simply stops
 * opening one.
 */
internal fun openPlaylist(id: String?, playlists: List<TauonPlaylist>): TauonPlaylist? =
    id?.let { wanted -> playlists.firstOrNull { it.id == wanted && it.count > 0 } }

/**
 * How the library is browsed, kept apart from the connection preferences that `disconnect()`
 * clears: which view someone prefers outlives the server they were looking at.
 */
@Stable
class LibrarySettings(private val prefs: SharedPreferences) {
    var view by mutableStateOf(libraryViewFrom(prefs.getString(KEY_VIEW, null)))
        private set

    fun choose(choice: LibraryView) {
        if (choice == view) return
        view = choice
        prefs.edit().putString(KEY_VIEW, choice.name).apply()
    }

    private companion object {
        const val KEY_VIEW = "view"
    }
}

@Composable
fun rememberLibrarySettings(): LibrarySettings {
    val context = LocalContext.current
    return remember(context) {
        LibrarySettings(context.getSharedPreferences("library", Context.MODE_PRIVATE))
    }
}
