package com.lastwave.app.ui.feed

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.listenbrainz.ListenBrainzClient
import com.lastwave.app.data.network.DataMeter
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.library.CoverCard
import com.lastwave.app.ui.library.FindKind
import com.lastwave.app.ui.library.FindQuery
import com.lastwave.app.ui.library.LocalFindOnServer
import com.lastwave.app.ui.library.OutsideActionSheet
import com.lastwave.app.ui.library.SheetAction
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.requests.LocalRequestAlbum
import com.lastwave.app.ui.search.toPlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ListenBrainzShelfViewModel @Inject constructor(
    private val client: ListenBrainzClient,
    private val search: SearchRepository,
) : ViewModel() {
    val user: StateFlow<String?> = client.user
    private val _recs = MutableStateFlow<List<ListenBrainzClient.Rec>>(emptyList())
    val recs: StateFlow<List<ListenBrainzClient.Rec>> = _recs.asStateFlow()
    private var loaded = false

    /** Once per app session, and only when ListenBrainz is connected. */
    fun load() {
        if (loaded || client.user.value == null) return
        loaded = true
        viewModelScope.launch {
            val found = client.recommendations()
            if (found.isEmpty()) loaded = false else _recs.value = found
        }
    }

    /** Finds the song on YouTube so it can be played. */
    suspend fun resolve(rec: ListenBrainzClient.Rec): PlayableTrack? = try {
        search.search(SearchTab.TRACKS, "${rec.artist} ${rec.title}").firstOrNull()?.toPlayableTrack()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** "Picked for you by ListenBrainz": songs from your listening history that you don't have yet. */
@Composable
internal fun ListenBrainzShelf(vm: ListenBrainzShelfViewModel = hiltViewModel(key = "lb-shelf")) {
    val user by vm.user.collectAsStateWithLifecycle()
    val recs by vm.recs.collectAsStateWithLifecycle()
    val saving by DataMeter.saving.collectAsStateWithLifecycle()
    LaunchedEffect(user, saving) { if (!saving) vm.load() }
    if (user == null || recs.isEmpty()) return

    val context = LocalContext.current
    val player = LocalMusicPlayer.current
    val request = LocalRequestAlbum.current
    val find = LocalFindOnServer.current
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<ListenBrainzClient.Rec?>(null) }

    Column {
        Text(
            "Picked for you by ListenBrainz",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(recs, key = { i, r -> "lb_${i}_${r.title}_${r.artist}" }) { _, r ->
                CoverCard(
                    title = r.title, subtitle = r.artist, coverUrl = r.coverUrl, round = false,
                    onClick = { menuFor = r }, onLongClick = { menuFor = r },
                    modifier = Modifier.width(150.dp),
                )
            }
        }
    }
    menuFor?.let { r ->
        OutsideActionSheet(
            title = r.title,
            subtitle = listOfNotNull(r.artist, r.album).joinToString(" • "),
            coverUrl = r.coverUrl,
            round = false,
            actions = listOf(
                SheetAction(Icons.Filled.PlayArrow, "Play", "Streams it from YouTube") {
                    scope.launch {
                        val t = vm.resolve(r)
                        if (t != null) player.playQueue(listOf(t), 0, "ListenBrainz", startShuffled = false)
                        else Toast.makeText(context, "Couldn't find it on YouTube", Toast.LENGTH_SHORT).show()
                    }
                },
                SheetAction(Icons.Filled.Search, "Find on server", "Check if it's already in your library") {
                    find(FindQuery(FindKind.SONG, r.title, r.artist, r.album))
                },
                SheetAction(Icons.Filled.CloudDownload, "Request album", "Adds it to your library") {
                    request(listOf(r.artist, r.album ?: r.title).joinToString(" "))
                },
            ),
            onDismiss = { menuFor = null },
            badge = "ListenBrainz",
        )
    }
}
