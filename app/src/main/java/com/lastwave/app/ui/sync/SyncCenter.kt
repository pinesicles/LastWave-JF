package com.lastwave.app.ui.sync

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.jellyfin.JellyfinRepository
import com.lastwave.app.data.likes.LikeMirror
import com.lastwave.app.data.likes.LikeSync
import com.lastwave.app.data.requests.PlaylistLinks
import com.lastwave.app.ui.library.ImportLeftovers
import com.lastwave.app.ui.library.ImportLeftoversDialog
import com.lastwave.app.ui.library.SyncIssuesHost
import com.lastwave.app.ui.settings.GroupPosition
import com.lastwave.app.ui.settings.LikesSyncSettingsSection
import com.lastwave.app.ui.settings.PlaylistLinksDialog
import com.lastwave.app.ui.settings.SectionLabel
import com.lastwave.app.ui.settings.SettingsActionCard
import com.lastwave.app.ui.settings.SettingsGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Sync & imports screen was opened for: everything, likes, or one playlist. */
class SyncFilter(val kind: Kind, val playlistId: String? = null, val name: String? = null) {
    enum class Kind { ALL, LIKES, PLAYLIST }
}

/** One place to open the screen from anywhere (Settings, Liked songs menu, a playlist's menu). */
object SyncCenter {
    internal val request = MutableStateFlow<SyncFilter?>(null)
    val open: StateFlow<SyncFilter?> get() = request
    fun show(filter: SyncFilter = SyncFilter(SyncFilter.Kind.ALL)) { request.value = filter }
    fun close() { request.value = null }
}

@HiltViewModel
class SyncCenterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val links: PlaylistLinks,
    val mirror: LikeMirror,
    val likeSync: LikeSync,
    private val jelly: JellyfinRepository,
) : ViewModel() {
    /** Imported playlists that still have songs they couldn't match: id to (name, count). */
    val leftovers = MutableStateFlow<List<Triple<String, String, Int>>>(emptyList())

    fun refreshLeftovers() {
        val ids = ImportLeftovers.allIds(context)
        if (ids.isEmpty()) { leftovers.value = emptyList(); return }
        viewModelScope.launch {
            val names = runCatching { jelly.playlists().items.associate { it.id to it.name.orEmpty() } }.getOrDefault(emptyMap())
            leftovers.value = ids.filter { names.containsKey(it) }.map { Triple(it, names[it].orEmpty(), ImportLeftovers.count(context, it)) }
        }
    }
}

/** Hosted once (in the player host) so any screen can open the sync screen. */
@Composable
fun SyncCenterOverlay() {
    val filter by SyncCenter.request.collectAsStateWithLifecycle()
    val f = filter ?: return
    Dialog(onDismissRequest = SyncCenter::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SyncCenterContent(f)
        }
    }
}

/** Live "what is syncing" cards. They read the same flows the settings rows and dialogs read, so all of them agree. */
@Composable
private fun ActivityCard(title: String, running: Boolean, detail: String?, last: String?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            val line = if (running) detail ?: "Working…" else last ?: detail ?: "Idle"
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SyncCenterContent(filter: SyncFilter, vm: SyncCenterViewModel = hiltViewModel()) {
    val linkStatus by vm.links.status.collectAsStateWithLifecycle()
    val links by vm.links.links.collectAsStateWithLifecycle()
    val mirrorBusy by vm.mirror.busy.collectAsStateWithLifecycle()
    val mirrorStatus by vm.mirror.status.collectAsStateWithLifecycle()
    val failures by vm.mirror.failures.collectAsStateWithLifecycle()
    val checkStatus by vm.likeSync.status.collectAsStateWithLifecycle()
    val leftovers by vm.leftovers.collectAsStateWithLifecycle()
    var linksDialog by remember { mutableStateOf(false) }
    var issues by remember { mutableStateOf(false) }
    var leftFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(leftFor) { if (leftFor == null) vm.refreshLeftovers() }

    val container = MaterialTheme.colorScheme.secondaryContainer
    val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
    val primary = MaterialTheme.colorScheme.primaryContainer
    val onPrimary = MaterialTheme.colorScheme.onPrimaryContainer
    val kind = filter.kind
    val thisLink = links.firstOrNull { it.jfId == filter.playlistId }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = SyncCenter::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(
                when (kind) {
                    SyncFilter.Kind.PLAYLIST -> filter.name ?: "Playlist"
                    SyncFilter.Kind.LIKES -> "Liked songs sync"
                    else -> "Sync & imports"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("Activity")
            if (kind != SyncFilter.Kind.PLAYLIST) {
                ActivityCard("Likes", mirrorBusy, mirrorStatus, mirrorStatus)
                ActivityCard("Liked songs on your server", checkStatus != null && checkStatus!!.startsWith("Checking"), checkStatus, checkStatus)
            }
            if (kind != SyncFilter.Kind.LIKES) {
                ActivityCard(
                    if (kind == SyncFilter.Kind.PLAYLIST) "Sync for this playlist" else "Playlists",
                    linkStatus != null, linkStatus,
                    if (kind == SyncFilter.Kind.PLAYLIST) thisLink?.last ?: "No sync set up for this playlist"
                    else links.mapNotNull { l -> l.last?.let { "${l.jfName}: $it" } }.joinToString("\n").ifBlank { "No playlist syncs yet" },
                )
            }

            if (kind != SyncFilter.Kind.PLAYLIST) {
                LikesSyncSettingsSection()
            }

            if (kind != SyncFilter.Kind.LIKES) {
                SectionLabel("Playlists")
                SettingsGroup(rowCount = if (kind == SyncFilter.Kind.PLAYLIST) 2 else 1 + leftovers.size.coerceAtMost(8)) { index, position ->
                    if (index == 0) SettingsActionCard(
                        icon = Icons.Filled.Sync, iconContainer = primary, iconTint = onPrimary,
                        title = "Playlist syncs",
                        subtitle = if (kind == SyncFilter.Kind.PLAYLIST) thisLink?.let { "Set up · ${it.last ?: "not run yet"}" } ?: "Set up a sync for this playlist"
                        else "${links.size} set up. Add, edit, sync now",
                        onClick = { linksDialog = true }, position = position,
                    ) else if (kind == SyncFilter.Kind.PLAYLIST) {
                        val n = ImportLeftovers.count(androidx.compose.ui.platform.LocalContext.current, filter.playlistId.orEmpty())
                        SettingsActionCard(
                            icon = Icons.Filled.PlaylistAddCheck, iconContainer = container, iconTint = onContainer,
                            title = "Unmatched songs", subtitle = if (n == 0) "None" else "$n from the import or sync. Search again or request",
                            onClick = { if (n > 0) leftFor = filter.playlistId }, position = position,
                        )
                    } else leftovers.getOrNull(index - 1)?.let { (id, name, count) ->
                        SettingsActionCard(
                            icon = Icons.Filled.PlaylistAddCheck, iconContainer = container, iconTint = onContainer,
                            title = name, subtitle = "$count unmatched songs. Search again or request",
                            onClick = { leftFor = id }, position = position,
                        )
                    }
                }
            }

            if (kind != SyncFilter.Kind.PLAYLIST) {
                SectionLabel("Problems")
                SettingsGroup(rowCount = 1) { _, position ->
                    SettingsActionCard(
                        icon = Icons.Filled.SyncProblem, iconContainer = container, iconTint = onContainer,
                        title = "Songs that didn't sync",
                        subtitle = if (failures.isEmpty()) "Nothing needs a look" else "${failures.size} to sort out. Choose a match, request or ignore",
                        onClick = { issues = true }, position = position,
                    )
                }
            }
        }
    }
    if (linksDialog) PlaylistLinksDialog(onClose = { linksDialog = false })
    if (issues) SyncIssuesHost { issues = false }
    leftFor?.let { ImportLeftoversDialog(it) { leftFor = null } }
}

/** The single Sync entry in Settings; shows live status from the same flows. */
@Composable
fun SyncCenterSettingsEntry(vm: SyncCenterViewModel = hiltViewModel()) {
    val busy by vm.mirror.busy.collectAsStateWithLifecycle()
    val mirrorStatus by vm.mirror.status.collectAsStateWithLifecycle()
    val linkStatus by vm.links.status.collectAsStateWithLifecycle()
    val failures by vm.mirror.failures.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Sync")
        SettingsGroup(rowCount = 1) { _, position ->
            SettingsActionCard(
                icon = Icons.Filled.Favorite,
                iconContainer = MaterialTheme.colorScheme.primaryContainer, iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                title = "Sync & imports",
                subtitle = when {
                    linkStatus != null -> linkStatus!!
                    busy -> mirrorStatus ?: "Syncing likes…"
                    failures.isNotEmpty() -> "${failures.size} songs need a look"
                    else -> "Likes, playlists, missing songs and imports"
                },
                onClick = { SyncCenter.show() }, position = position,
            )
        }
    }
}
