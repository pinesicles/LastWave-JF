package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.jellyfin.importer.ImportTrack
import com.lastwave.app.data.likes.LikeSync
import com.lastwave.app.ui.library.JellyImportViewModel
import com.lastwave.app.ui.library.RequestMissingDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LikesSyncViewModel @Inject constructor(val sync: LikeSync) : ViewModel() {
    val missing: StateFlow<List<ImportTrack>?> = sync.missing
    private val _showMissing = MutableStateFlow(false)
    val showMissing: StateFlow<Boolean> = _showMissing.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private var job: Job? = null

    private val _plan = MutableStateFlow<com.lastwave.app.data.likes.LikePlan?>(null)
    val plan: StateFlow<com.lastwave.app.data.likes.LikePlan?> = _plan.asStateFlow()

    /** Reads every service and shows what a sync would change, before touching anything. */
    fun preview(mode: com.lastwave.app.data.likes.SyncMode = com.lastwave.app.data.likes.SyncMode.MERGE) =
        launchJob { _plan.value = sync.mirror.plan(mode) }
    fun cancelPlan() { _plan.value = null }
    fun applyPlan() {
        val p = _plan.value ?: return
        _plan.value = null
        sync.mirror.enqueue(p.mode)
    }
    /** Opens the saved list, or runs the check the first time. */
    fun open() {
        if (sync.missing.value != null) _showMissing.value = true else recheck()
    }
    fun recheck() = launchJob { sync.findMissing(); _showMissing.value = true }
    fun closeMissing() { _showMissing.value = false }

    private fun launchJob(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        job = viewModelScope.launch { try { block() } finally { _busy.value = false } }
    }
}

/** Likes sync: pass hearts on to ListenBrainz and the request server, and find liked songs your server lacks. */
@Composable
fun LikesSyncSettingsSection(vm: LikesSyncViewModel = hiltViewModel()) {
    val toLb by vm.sync.toListenBrainz.collectAsStateWithLifecycle()
    val toDn by vm.sync.toRequestServer.collectAsStateWithLifecycle()
    val status by vm.sync.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val showMissing by vm.showMissing.collectAsStateWithLifecycle()
    val removals by vm.sync.mirror.syncRemovals.collectAsStateWithLifecycle()
    val auto by vm.sync.mirror.autoSync.collectAsStateWithLifecycle()
    val mirrorStatus by vm.sync.mirror.status.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val mirrorBusy by vm.sync.mirror.busy.collectAsStateWithLifecycle()
    var chooser by remember { mutableStateOf(false) }
    var copyPlaylists by remember { mutableStateOf(false) }
    val container = MaterialTheme.colorScheme.secondaryContainer
    val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
    val primary = MaterialTheme.colorScheme.primaryContainer
    val onPrimary = MaterialTheme.colorScheme.onPrimaryContainer

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Likes sync")
        SettingsGroup(rowCount = 7) { index, position ->
            when (index) {
                0 -> SettingsToggleCard(
                    icon = Icons.Filled.Favorite, iconContainer = container, iconTint = onContainer,
                    title = "Love on ListenBrainz", subtitle = "Hearting a song also loves it on ListenBrainz",
                    checked = toLb, onCheckedChange = vm.sync::setToListenBrainz, position = position,
                )
                1 -> SettingsToggleCard(
                    icon = Icons.Filled.GraphicEq, iconContainer = container, iconTint = onContainer,
                    title = "Star on request server", subtitle = "Server songs you heart are also starred on DroppedNeedle (best effort)",
                    checked = toDn, onCheckedChange = vm.sync::setToRequestServer, position = position,
                )
                2 -> SettingsToggleCard(
                    icon = Icons.Filled.Sync, iconContainer = container, iconTint = onContainer,
                    title = "Sync removals too", subtitle = "Off: removing a like in one place leaves it in the others",
                    checked = removals, onCheckedChange = vm.sync.mirror::setSyncRemovals, position = position,
                )
                3 -> SettingsToggleCard(
                    icon = Icons.Filled.Sync, iconContainer = container, iconTint = onContainer,
                    title = "Keep in sync automatically", subtitle = "Checks every few hours and after you heart a song. Starts after the first sync",
                    checked = auto, onCheckedChange = vm.sync.mirror::setAutoSync, position = position,
                )
                4 -> SettingsActionCard(
                    icon = Icons.Filled.Sync, iconContainer = primary, iconTint = onPrimary,
                    title = "Sync likes now",
                    subtitle = if (busy || mirrorBusy) (mirrorStatus ?: "Working…") else mirrorStatus
                        ?: "Jellyfin, Spotify, ListenBrainz and your app likes. Shows what will change first",
                    onClick = { if (!busy && !mirrorBusy) chooser = true }, position = position,
                )
                5 -> SettingsActionCard(
                    icon = Icons.Filled.CloudDownload, iconContainer = primary, iconTint = onPrimary,
                    title = "Liked songs not on your server",
                    subtitle = if (busy && status != null) status!!
                    else missing?.let { "${it.size} songs not on your server. Tap to see them" }
                        ?: "YouTube likes, and Spotify likes if linked on the request server",
                    onClick = { vm.open() }, position = position,
                )
                else -> SettingsActionCard(
                    icon = Icons.Filled.Sync, iconContainer = primary, iconTint = onPrimary,
                    title = "Playlist syncs",
                    subtitle = "Keep Jellyfin playlists matched with the request server and Spotify",
                    onClick = { copyPlaylists = true }, position = position,
                )
            }
        }
    }
    var detail by remember { mutableStateOf<com.lastwave.app.data.likes.LikeNode?>(null) }
    if (copyPlaylists) PlaylistLinksDialog(onClose = { copyPlaylists = false })
    if (chooser) SyncModeDialog(onClose = { chooser = false }, onPreview = { chooser = false; vm.preview(it) })
    plan?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::cancelPlan,
            title = { androidx.compose.material3.Text("Sync likes") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        if (p.available.size < 2) "Connect at least two services first (Jellyfin, Spotify, ListenBrainz)."
                        else if (p.isEmpty) "Everything already matches."
                        else "This will change:",
                    )
                    p.available.forEach { n ->
                        val a = p.adds[n]?.size ?: 0
                        val r = p.removes[n]?.size ?: 0
                        androidx.compose.material3.TextButton(onClick = { detail = n }, enabled = a + r > 0) {
                            val has = if (p.rawCount(n) != p.count(n)) "${p.rawCount(n)} (${p.count(n)} unique)" else "${p.count(n)}"
                            androidx.compose.material3.Text(
                                "${n.label}: has $has, +$a" + (if (r > 0 || removals) " / −$r" else "") + (if (a + r > 0) "  ›" else ""),
                            )
                        }
                    }
                    androidx.compose.material3.Text(
                        "Songs Jellyfin doesn't have are added to the \"not on your server\" list so you can request them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = vm::applyPlan, enabled = p.available.size >= 2) {
                    androidx.compose.material3.Text("Apply")
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = vm::cancelPlan) { androidx.compose.material3.Text("Cancel") } },
        )
    }
    val shown = plan
    detail?.let { n ->
        if (shown != null) androidx.compose.material3.AlertDialog(
            onDismissRequest = { detail = null },
            title = { androidx.compose.material3.Text("Adding to ${n.label}") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown.addsWithSources(n)) { (t, from) ->
                        androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 6.dp)) {
                            androidx.compose.material3.Text(t.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            androidx.compose.material3.Text(
                                "${t.artist} • from " + from.joinToString(", ") { it.label },
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(shown.removes[n].orEmpty()) { t ->
                        androidx.compose.material3.Text("Remove: ${t.title} • ${t.artist}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { detail = null }) { androidx.compose.material3.Text("Back") } },
        )
    }
    val saved = missing
    if (showMissing && saved != null) {
        RequestMissingDialog(
            unmatched = saved.map { JellyImportViewModel.Unmatched(it, emptyList()) },
            onClose = vm::closeMissing,
            onRequested = { g -> vm.sync.dropAlbum(g.artist, g.album) },
            onRecheck = vm::recheck,
        )
    }
}

/** Pick how to sync: merge everything, or copy one service's likes to others (optionally removing what the source lacks). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SyncModeDialog(onClose: () -> Unit, onPreview: (com.lastwave.app.data.likes.SyncMode) -> Unit) {
    val nodes = com.lastwave.app.data.likes.LikeNode.values().toList()
    var oneWay by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(com.lastwave.app.data.likes.LikeNode.SPOTIFY) }
    var targets by remember { mutableStateOf(setOf(com.lastwave.app.data.likes.LikeNode.YOUTUBE)) }
    var mirror by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { androidx.compose.material3.Text("Sync likes") },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.TextButton(onClick = { oneWay = false }) {
                    androidx.compose.material3.Text((if (!oneWay) "●  " else "○  ") + "Merge everything (two-way)")
                }
                androidx.compose.material3.TextButton(onClick = { oneWay = true }) {
                    androidx.compose.material3.Text((if (oneWay) "●  " else "○  ") + "One way: copy from one service")
                }
                if (oneWay) {
                    androidx.compose.material3.Text("From", style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow {
                        nodes.forEach { n ->
                            androidx.compose.material3.FilterChip(
                                selected = source == n, onClick = { source = n; targets = targets - n },
                                label = { androidx.compose.material3.Text(n.label) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    androidx.compose.material3.Text("To", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    androidx.compose.foundation.layout.FlowRow {
                        nodes.filter { it != source }.forEach { n ->
                            androidx.compose.material3.FilterChip(
                                selected = n in targets, onClick = { targets = if (n in targets) targets - n else targets + n },
                                label = { androidx.compose.material3.Text(n.label) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    androidx.compose.material3.TextButton(onClick = { mirror = !mirror }) {
                        androidx.compose.material3.Text((if (mirror) "☑  " else "☐  ") + "Also remove what the source doesn't have")
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = !oneWay || targets.isNotEmpty(),
                onClick = {
                    onPreview(
                        if (oneWay) com.lastwave.app.data.likes.SyncMode(source, targets, mirror)
                        else com.lastwave.app.data.likes.SyncMode.MERGE,
                    )
                },
            ) { androidx.compose.material3.Text("Preview") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { androidx.compose.material3.Text("Cancel") } },
    )
}

