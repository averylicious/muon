package dev.avery.muon

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A busy indicator that occupies its space whether or not it is showing. Inserting one into the
 * column shifted every screen down as a refresh started and back again as it finished.
 */
@Composable
internal fun BusyStrip(busy: Boolean) {
    Box(Modifier.fillMaxWidth().height(4.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
    }
}

@Composable
internal fun ErrorCard(message: String, action: String? = null, quiet: Boolean = false,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), retry: () -> Unit = {}) {
    // One row: the message, then its action at the end, centred on it, so the card is as tall as its
    // text and no taller. [quiet] is for a state that is not a failure, such as being offline with
    // downloads to play: a tonal card rather than the error colour.
    val colors = MaterialTheme.colorScheme
    val content = if (quiet) colors.onSecondaryContainer else colors.onErrorContainer
    Surface(color = if (quiet) colors.secondaryContainer else colors.errorContainer, contentColor = content,
        shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 20.dp, end = if (action != null) 8.dp else 20.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = retry, colors = ButtonDefaults.textButtonColors(contentColor = content),
                modifier = Modifier.padding(start = 8.dp)) { Text(action) }
        }
    }
}

@Composable
internal fun Control(kind: String, label: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) { MuonIcon(kind) }
}

@Composable
internal fun ToggleControl(kind: String, label: String, state: String, active: Boolean, enabled: Boolean, action: () -> Unit) {
    // On is a tonal container behind the icon, Material's toggle, rather than a colour change and a
    // dot (#120). TalkBack hears the state either way.
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = action, enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) colors.secondaryContainer else Color.Transparent,
            contentColor = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant),
        modifier = Modifier.semantics { contentDescription = label; stateDescription = state }) {
        MuonIcon(kind)
    }
}

@Composable
internal fun MuonIcon(kind: String, modifier: Modifier = Modifier) {
    Icon(painterResource(iconRes(kind)), contentDescription = null, modifier = modifier.size(24.dp))
}
