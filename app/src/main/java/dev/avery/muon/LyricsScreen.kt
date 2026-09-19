package dev.avery.muon

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException

@Composable
internal fun LyricsScreen(item: MediaItem?, back: () -> Unit) {
    var lyrics by remember(item?.mediaId) { mutableStateOf("Loading lyrics…") }
    var failure by remember(item?.mediaId) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(item?.mediaId, attempt) {
        failure = false
        if (item == null) { lyrics = "Choose a track to see its lyrics."; return@LaunchedEffect }
        lyrics = "Loading lyrics…"
        try {
            val id = item.mediaId.substringAfterLast('/').toLong()
            val endpoint = ServerEndpoint.parse(item.mediaId.substringBeforeLast('/'))
            lyrics = TauonApi(endpoint).lyrics(id).ifBlank { "No lyrics stored for this track in Tauon." }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { lyrics = friendlyError(e); failure = true }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(onClick = back) { Text("‹ Now Playing") }
        Text("Lyrics", style = MaterialTheme.typography.headlineLarge)
        Text(item?.mediaMetadata?.title?.toString().orEmpty(), color = MaterialTheme.colorScheme.primary)
        if (failure) ErrorCard(lyrics, "Retry") { attempt++ }
        else SelectionContainer { Text(lyrics, style = MaterialTheme.typography.titleLarge, lineHeight = MaterialTheme.typography.headlineMedium.lineHeight) }
        Text("Stored lyrics from Tauon · Not time-synchronized", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
