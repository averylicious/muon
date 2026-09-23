package dev.avery.muon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
internal fun ErrorCard(message: String, action: String? = null, retry: () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            if (action != null) TextButton(onClick = retry) { Text(action, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@Composable
internal fun Control(kind: String, label: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) { MuonIcon(kind) }
}

@Composable
internal fun ToggleControl(kind: String, label: String, state: String, active: Boolean, enabled: Boolean, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.semantics { contentDescription = label; stateDescription = state }) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            MuonIcon(kind)
            if (active) Box(Modifier.align(Alignment.BottomCenter).size(4.dp)
                .background(LocalContentColor.current, CircleShape))
        }
    }
}

@Composable
internal fun MuonIcon(kind: String, modifier: Modifier = Modifier) {
    Icon(painterResource(iconRes(kind)), contentDescription = null, modifier = modifier.size(24.dp))
}
