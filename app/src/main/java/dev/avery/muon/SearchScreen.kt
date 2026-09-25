package dev.avery.muon

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Results plus enough state to tell "nothing typed" from "still searching" from "no matches". */
@Immutable
internal data class SearchResults(val tracks: List<TauonTrack> = emptyList(),
    val searching: Boolean = false, val completed: String = "")

@Composable
internal fun SearchField(query: String, onQuery: (String) -> Unit, searching: Boolean) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Songs, artists, albums") }, leadingIcon = { MuonIcon("search") },
            singleLine = true, shape = RoundedCornerShape(18.dp),
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text("Clear") } })
        // Reserved either way, so starting a search does not shift the results under the finger.
        Box(Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp)) {
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
    }
}
