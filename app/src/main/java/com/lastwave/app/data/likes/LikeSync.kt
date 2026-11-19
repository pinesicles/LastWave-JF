package com.lastwave.app.data.likes

import android.content.Context
import com.lastwave.app.data.jellyfin.JellyfinRepository
import com.lastwave.app.data.jellyfin.importer.ImportMatcher
import com.lastwave.app.data.jellyfin.importer.ImportTrack
import com.lastwave.app.data.jellyfin.jellyfinItemIdOrNull
import com.lastwave.app.data.listenbrainz.ListenBrainzClient
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.requests.DroppedNeedleClient
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passes a like on to other services. When a song is liked or un-liked in the app it can be loved on
 * ListenBrainz and starred on the request server. It also finds liked songs that aren't on your server.
 */
@Singleton
class LikeSync @Inject constructor(
    @ApplicationContext context: Context,
    private val listenBrainz: ListenBrainzClient,
    private val dn: DroppedNeedleClient,
    private val jelly: JellyfinRepository,
    private val playlists: PlaylistRepository,
    val mirror: LikeMirror,
) {
    init { mirror.onNotOnServer = { addMissing(it) } }

    private val prefs = context.getSharedPreferences("jw_likesync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _toLb = MutableStateFlow(prefs.getBoolean("lb", true))
    val toListenBrainz: StateFlow<Boolean> = _toLb.asStateFlow()
    private val _toDn = MutableStateFlow(prefs.getBoolean("dn", true))
    val toRequestServer: StateFlow<Boolean> = _toDn.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _missing = MutableStateFlow<List<ImportTrack>?>(loadMissing())
    /** The last "not on your server" result, kept across app restarts. Null until a check has run. */
    val missing: StateFlow<List<ImportTrack>?> = _missing.asStateFlow()

    private fun loadMissing(): List<ImportTrack>? = try {
        prefs.getString("missing", null)?.let { s ->
            val arr = org.json.JSONArray(s)
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { ImportTrack(it.getString("t"), it.getString("a"), it.optString("al").ifBlank { null }) }
        }
    } catch (_: Exception) { null }

    private fun setMissing(list: List<ImportTrack>) {
        _missing.value = list
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("t", it.title).put("a", it.artist).put("al", it.album.orEmpty())) }
        prefs.edit().putString("missing", arr.toString()).apply()
    }

    /** Songs the sync couldn't favourite because Jellyfin lacks them join the "not on your server" list. */
    private fun addMissing(more: List<ImportTrack>) {
        val have = (_missing.value ?: emptyList())
        val keys = have.mapTo(HashSet()) { ImportMatcher.norm(it.title) + "|" + ImportMatcher.norm(it.artist) }
        setMissing(have + more.filter { keys.add(ImportMatcher.norm(it.title) + "|" + ImportMatcher.norm(it.artist)) })
    }

    /** An album was requested: its songs leave the list. */
    fun dropAlbum(artist: String, album: String?) {
        val a = ImportMatcher.norm(artist)
        val al = ImportMatcher.norm(album.orEmpty())
        _missing.value?.let { list ->
            setMissing(list.filterNot { ImportMatcher.norm(it.artist) == a && ImportMatcher.norm(it.album.orEmpty()) == al })
        }
    }

    fun setToListenBrainz(on: Boolean) { _toLb.value = on; prefs.edit().putBoolean("lb", on).apply() }
    fun setToRequestServer(on: Boolean) { _toDn.value = on; prefs.edit().putBoolean("dn", on).apply() }

    /** Called after every heart tap. Fire-and-forget; failures are ignored. */
    fun onToggled(track: PlayableTrack, liked: Boolean) {
        scope.launch {
            // Un-liking only reaches ListenBrainz when "sync removals" is on.
            if (_toLb.value && (liked || mirror.syncRemovals.value)) runCatching { listenBrainz.setLove(track.artist, track.title, liked) }
            mirror.syncSoon()
            if (_toDn.value && (liked || mirror.syncRemovals.value)) runCatching { starOnRequestServer(track, liked) }
        }
    }

    private suspend fun starOnRequestServer(track: PlayableTrack, liked: Boolean) {
        val id = track.jellyfinItemIdOrNull() ?: return
        val ids = jelly.providerIds(id)
        dn.setTrackFavorite(listOfNotNull(ids["MusicBrainzReleaseGroup"], ids["MusicBrainzAlbum"]), track.title, liked)
    }

    /** Loves every Jellyfin favourite on ListenBrainz. Already-sent songs are skipped, so it can be run again. */
    suspend fun sendFavouritesToListenBrainz() {
        val favs = try { jelly.favoriteTracks() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _status.value = "Couldn't read your Jellyfin favourites"; return
        }
        val done = prefs.getStringSet("lb_done", emptySet())!!.toMutableSet()
        var sent = 0
        favs.forEachIndexed { i, t ->
            val key = ImportMatcher.norm(t.title) + "|" + ImportMatcher.norm(t.artist)
            _status.value = "Sending ${i + 1} of ${favs.size}…"
            if (key !in done && listenBrainz.setLove(t.artist, t.title, true)) {
                done += key; sent++
                prefs.edit().putStringSet("lb_done", HashSet(done)).apply()
                delay(300)
            }
        }
        _status.value = "Sent $sent new loves (${favs.size - sent} already sent or not found)"
    }

    /**
     * Liked songs (YouTube likes, plus Spotify likes if the request server has Spotify linked) that Jellyfin
     * doesn't have, ready for "request missing albums".
     */
    suspend fun findMissing(): List<ImportTrack> {
        val norm = { s: String -> ImportMatcher.norm(s) }
        val liked = playlists.getLikedSongs()?.tracks.orEmpty().map { ImportTrack(it.name, it.artist, it.album) }
        val spotify = dn.remoteFavoriteTracks("spotify")
        val all = (liked + spotify).distinctBy { norm(it.title) + "|" + norm(it.artist) }
        val missing = mutableListOf<ImportTrack>()
        all.chunked(4).forEachIndexed { i, chunk ->
            _status.value = "Checking ${minOf((i + 1) * 4, all.size)} of ${all.size}…"
            val found = coroutineScope {
                chunk.map { t ->
                    async {
                        try {
                            jelly.findSong(t.title, t.artist) != null
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { true }
                    }
                }.awaitAll()
            }
            chunk.forEachIndexed { j, t -> if (!found[j]) missing += t }
        }
        _status.value = "${missing.size} of ${all.size} liked songs aren't on your server"
        setMissing(missing)
        return missing
    }
}
