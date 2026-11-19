package com.lastwave.app.ui.feed

import android.content.Context
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
import com.lastwave.app.data.generate.GenerateJson
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.listenbrainz.ListenBrainzClient
import com.lastwave.app.data.local.SessionPreferences
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.json.JSONArray
import org.json.JSONObject

@HiltViewModel
class LastFmShelfViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val generate: GenerateRepository,
    private val session: SessionPreferences,
    private val search: SearchRepository,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("jw_lastfm_shelf", Context.MODE_PRIVATE)
    private val _recs = MutableStateFlow<List<ListenBrainzClient.Rec>>(emptyList())
    val recs: StateFlow<List<ListenBrainzClient.Rec>> = _recs.asStateFlow()
    private var loaded = false

    private fun cached(): List<ListenBrainzClient.Rec>? = runCatching {
        if (System.currentTimeMillis() - prefs.getLong("at", 0L) > 12L * 3600_000L) return null
        val a = JSONArray(prefs.getString("list", null) ?: return null)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> ListenBrainzClient.Rec(o.getString("t"), o.getString("a"), null, null) } }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Needs your Last.fm API key and account (Settings → Integrations). Rebuilt at most every 12 hours. */
    fun load() {
        if (loaded) return
        val s = session.currentSession
        if (s.apiKey.isBlank() || s.username.isBlank() || s.username.equals("Guest User", true)) return
        loaded = true
        viewModelScope.launch {
            cached()?.let { _recs.value = it; return@launch }
            val found = try { build(s.username) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (found.isEmpty()) { loaded = false; return@launch }
            _recs.value = found
            val a = JSONArray()
            found.forEach { a.put(JSONObject().put("t", it.title).put("a", it.artist)) }
            prefs.edit().putString("list", a.toString()).putLong("at", System.currentTimeMillis()).apply()
        }
    }

    private fun names(el: kotlinx.serialization.json.JsonElement?): List<String> =
        GenerateJson.asObjectList(el).mapNotNull { (it["name"] as? JsonPrimitive)?.contentOrNull }

    /** Artists similar to ones you've played lately, each with their most popular song. */
    private suspend fun build(user: String): List<ListenBrainzClient.Rec> = withContext(Dispatchers.IO) {
        val top = names(generate.call(mapOf("method" to "user.gettopartists", "user" to user, "period" to "3month", "limit" to "20"))["topartists"]?.let { (it as? JsonObject)?.get("artist") })
        if (top.isEmpty()) return@withContext emptyList()
        val known = top.map { it.lowercase() }.toSet()
        val similar = LinkedHashSet<String>()
        for (a in top.shuffled().take(4)) {
            val sim = names((generate.call(mapOf("method" to "artist.getsimilar", "artist" to a, "limit" to "8"))["similarartists"] as? JsonObject)?.get("artist"))
            similar += sim.filter { it.lowercase() !in known }
        }
        similar.shuffled().take(14).mapNotNull { artist ->
            val t = names((generate.call(mapOf("method" to "artist.gettoptracks", "artist" to artist, "limit" to "1"))["toptracks"] as? JsonObject)?.get("track")).firstOrNull()
            t?.let { ListenBrainzClient.Rec(it, artist, null, null) }
        }
    }

    suspend fun resolve(rec: ListenBrainzClient.Rec): PlayableTrack? = try {
        search.search(SearchTab.TRACKS, "${rec.artist} ${rec.title}").firstOrNull()?.toPlayableTrack()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** "Because you listen to…": songs by artists similar to your recent Last.fm favourites. Hidden without a Last.fm key. */
@Composable
internal fun LastFmShelf(vm: LastFmShelfViewModel = hiltViewModel(key = "lastfm-shelf")) {
    val recs by vm.recs.collectAsStateWithLifecycle()
    val saving by DataMeter.saving.collectAsStateWithLifecycle()
    LaunchedEffect(saving) { if (!saving) vm.load() }
    if (recs.isEmpty()) return

    val context = LocalContext.current
    val player = LocalMusicPlayer.current
    val request = LocalRequestAlbum.current
    val find = LocalFindOnServer.current
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<ListenBrainzClient.Rec?>(null) }

    Column {
        Text(
            "Because you listen to…",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(recs, key = { i, r -> "lf_${i}_${r.title}_${r.artist}" }) { _, r ->
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
            subtitle = r.artist,
            coverUrl = r.coverUrl,
            round = false,
            actions = listOf(
                SheetAction(Icons.Filled.PlayArrow, "Play", "Streams it from YouTube") {
                    scope.launch {
                        val t = vm.resolve(r)
                        if (t != null) player.playQueue(listOf(t), 0, "Last.fm", startShuffled = false)
                        else Toast.makeText(context, "Couldn't find it on YouTube", Toast.LENGTH_SHORT).show()
                    }
                },
                SheetAction(Icons.Filled.Search, "Find on server", "Check if it's already in your library") {
                    find(FindQuery(FindKind.SONG, r.title, r.artist, r.album))
                },
                SheetAction(Icons.Filled.CloudDownload, "Request album", "Adds it to your library") {
                    request(listOf(r.artist, r.title).joinToString(" "))
                },
            ),
            onDismiss = { menuFor = null },
            badge = "Last.fm",
        )
    }
}
