package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.playback.CarLyrics

private val CAR_MODES = listOf(
    CarLyrics.OFF to "Off",
    CarLyrics.ARTIST to "In the artist line",
    CarLyrics.ALBUM to "In the album line",
)

/** Android Auto lyrics: shows the current line in the artist or album line of the now-playing screen. */
@Composable
fun CarLyricsSettingsSection() {
    val ctx = LocalContext.current
    CarLyrics.load(ctx)
    val mode by CarLyrics.modeState.collectAsStateWithLifecycle()
    var pick by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Android Auto")
        SettingsGroup(rowCount = 1) { _, position ->
            SettingsActionCard(
                icon = Icons.Filled.Lyrics,
                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                title = "Lyrics in Android Auto",
                subtitle = CAR_MODES.first { it.first == mode }.second,
                onClick = { pick = true }, position = position,
            )
        }
    }
    if (pick) {
        AlertDialog(
            onDismissRequest = { pick = false },
            title = { Text("Lyrics in Android Auto") },
            text = {
                Column {
                    Text(
                        "Android Auto has no lyrics screen, so the current line replaces the artist or album text while the car is connected. Needs synced lyrics.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    CAR_MODES.forEach { (value, label) ->
                        TextButton(onClick = { CarLyrics.setMode(ctx, value); pick = false }) {
                            Text(if (value == mode) "✓  $label" else label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pick = false }) { Text("Close") } },
        )
    }
}
