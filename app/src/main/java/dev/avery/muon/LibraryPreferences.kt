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
 * The playlist an identifier names, if it still names one with music in it.
 *
 * A refresh can empty a playlist or remove it entirely, so the screen asks this every time it
 * draws rather than watching for those events.
 */
internal fun openPlaylist(id: String?, playlists: List<TauonPlaylist>): TauonPlaylist? =
    id?.let { wanted -> playlists.firstOrNull { it.id == wanted && it.count > 0 } }

/** What to do with a playlist selection that outlived the composition that made it. */
internal enum class StoredSelection {
    /** Nothing was stored. */
    None,

    /** Stored, but there is no loaded library to judge it against yet. Show the list meanwhile. */
    Wait,

    /** Still names a playlist with music on the server it was made for. */
    Open,

    /** The server changed, or the playlist was emptied or removed. Forget it. */
    Discard,
}

/**
 * Whether a saved selection may still be opened.
 *
 * The selection is bound to the server it was made on, because identifiers mean nothing across
 * servers and one could name something else entirely. Judging it needs a loaded library, so until
 * there is one the answer is [Wait] rather than [Discard]: an app reopened from scratch has a
 * saved selection and an empty model for a moment, and discarding then would throw away a choice
 * that is about to become valid again. Once a library is loaded the answer is final, so a
 * selection that cannot be opened is forgotten rather than left to reappear if a later refresh
 * brings that identifier back.
 */
internal fun storedSelection(savedOrigin: String?, savedId: String?, origin: String?,
    playlists: List<TauonPlaylist>, libraryLoaded: Boolean): StoredSelection = when {
    savedId == null || savedOrigin == null -> StoredSelection.None
    origin == null || !libraryLoaded -> StoredSelection.Wait
    savedOrigin != origin -> StoredSelection.Discard
    openPlaylist(savedId, playlists) == null -> StoredSelection.Discard
    else -> StoredSelection.Open
}

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
