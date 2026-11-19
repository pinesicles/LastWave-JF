package com.lastwave.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.network.DataCategory
import com.lastwave.app.data.network.DataMeter
import kotlinx.coroutines.delay

private fun bytesLabel(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "${b / 1024} KB"
    else -> "$b B"
}

private val MODES = listOf(0 to "Off", 1 to "On mobile data only", 2 to "Always on")

/** Data usage page and data saver, under the Jellyfin and request-server sections. */
@Composable
fun DataUsageSettingsSection() {
    val mode by DataMeter.mode.collectAsStateWithLifecycle()
    val saving by DataMeter.saving.collectAsStateWithLifecycle()
    var showUsage by remember { mutableStateOf(false) }
    var pickMode by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primaryContainer
    val onPrimary = MaterialTheme.colorScheme.onPrimaryContainer
    var today by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(showUsage) {
        val s = DataMeter.snapshot(1).values
        today = s.sumOf { it.first } to s.sumOf { it.second }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Data")
        SettingsGroup(rowCount = 2) { index, position ->
            when (index) {
                0 -> SettingsActionCard(
                    icon = Icons.Filled.Wifi, iconContainer = primary, iconTint = onPrimary,
                    title = "Data usage",
                    subtitle = "Today: ${bytesLabel(today.first)} on Wi-Fi • ${bytesLabel(today.second)} on mobile data",
                    onClick = { showUsage = true }, position = position,
                )
                else -> SettingsActionCard(
                    icon = Icons.Filled.Download, iconContainer = primary, iconTint = onPrimary,
                    title = "Data saver",
                    subtitle = MODES.first { it.first == mode }.second + if (saving) " • saving now" else "",
                    onClick = { pickMode = true }, position = position,
                )
            }
        }
    }
    if (pickMode) {
        AlertDialog(
            onDismissRequest = { pickMode = false },
            title = { Text("Data saver") },
            text = {
                Column {
                    Text(
                        "While it's on: streaming is capped at 128 kbps, canvas and music videos are off, and YouTube shelves aren't loaded.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    MODES.forEach { (value, label) ->
                        TextButton(onClick = { DataMeter.setMode(value); pickMode = false }) {
                            Text(if (value == mode) "✓  $label" else label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickMode = false }) { Text("Cancel") } },
        )
    }
    if (showUsage) DataUsagePage { showUsage = false }
}

@Composable
private fun DataUsagePage(onClose: () -> Unit) {
    var days by remember { mutableStateOf(1) }
    var snap by remember { mutableStateOf<Map<DataCategory, Pair<Long, Long>>>(emptyMap()) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(days) {
        while (true) { snap = DataMeter.snapshot(days); delay(2000) }
    }
    val wifi = snap.values.sumOf { it.first }
    val mobile = snap.values.sumOf { it.second }
    val rows = DataCategory.values().filter { (snap[it]?.first ?: 0L) + (snap[it]?.second ?: 0L) > 0 }
        .sortedByDescending { (snap[it]?.first ?: 0L) + (snap[it]?.second ?: 0L) }
    val maxRow = rows.maxOfOrNull { (snap[it]?.first ?: 0L) + (snap[it]?.second ?: 0L) }?.coerceAtLeast(1L) ?: 1L

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text("Data usage", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirmReset = true }) { Text("Reset") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    listOf(1 to "Today", 7 to "7 days", 30 to "30 days").forEach { (d, label) ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    TotalCard("Wi-Fi", wifi, Modifier.weight(1f))
                    TotalCard("Mobile data", mobile, Modifier.weight(1f))
                }
                Text(
                    "Only counts this app, so it can differ from your phone's own totals.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (rows.isEmpty()) {
                    Text("Nothing recorded yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(rows, key = { it.name }) { cat ->
                        val (w, m) = snap[cat] ?: (0L to 0L)
                        Column {
                            Row(Modifier.fillMaxWidth()) {
                                Text(cat.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Text(bytesLabel(w + m), style = MaterialTheme.typography.bodyLarge)
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).clip(RoundedCornerShape(4.dp))) {
                                val total = (w + m).toFloat()
                                val scale = total / maxRow
                                if (w > 0) Box(Modifier.weight((w / total * scale).coerceAtLeast(0.001f)).height(8.dp).background(MaterialTheme.colorScheme.primary))
                                if (m > 0) Box(Modifier.weight((m / total * scale).coerceAtLeast(0.001f)).height(8.dp).background(MaterialTheme.colorScheme.tertiary))
                                val rest = 1f - scale
                                if (rest > 0.001f) Box(Modifier.weight(rest).height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                            }
                            Text(
                                "Wi-Fi ${bytesLabel(w)} • Mobile ${bytesLabel(m)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset data usage?") },
            text = { Text("Clears all recorded totals.") },
            confirmButton = { TextButton(onClick = { DataMeter.reset(); snap = DataMeter.snapshot(days); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TotalCard(label: String, bytes: Long, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(bytesLabel(bytes), style = MaterialTheme.typography.titleLarge)
        }
    }
}
