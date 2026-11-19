package com.lastwave.app.data.requests

import com.lastwave.app.data.jellyfin.JfItem
import com.lastwave.app.data.jellyfin.JellyfinRepository
import com.lastwave.app.data.jellyfin.importer.ImportTrack
import com.lastwave.app.data.likes.likeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies Jellyfin playlists to your request server (DroppedNeedle), one way. A playlist with the same name there is
 * topped up; otherwise it is created. Songs already in it are skipped, and nothing is ever removed.
 */
@Singleton
class PlaylistSync @Inject constructor(
    private val jelly: JellyfinRepository,
    private val dn: DroppedNeedleClient,
) {
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    suspend fun jellyfinPlaylists(): List<JfItem> = jelly.playlists().items

    /** Returns a one-line summary. */
    suspend fun copy(selected: List<JfItem>): String {
        var created = 0
        var songs = 0
        val failed = mutableListOf<String>()
        val existing = try { dn.playlists() } catch (e: DnException) { return e.message ?: "Couldn't reach the request server" }
        selected.forEachIndexed { i, p ->
            val name = p.name.orEmpty().ifBlank { "Playlist" }
            _status.value = "Copying ${i + 1} of ${selected.size}: $name"
            try {
                val tracks = jelly.playlistTracks(p.id).map { ImportTrack(it.title, it.artist, it.album) }
                val match = existing.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
                val id = match?.id ?: dn.createPlaylist(name, "jellyfin:${p.id}").also { created++ }
                val have = if (match != null) dn.playlistSongs(id).mapTo(HashSet<String>()) { likeKey(it.first, it.second) } else HashSet<String>()
                val missing = tracks.filter { have.add(likeKey(it.title, it.artist)) }
                if (missing.isNotEmpty()) dn.addPlaylistSongs(id, missing)
                songs += missing.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += name
            }
        }
        val msg = "Copied ${selected.size - failed.size} playlists ($created new), $songs songs added" +
            if (failed.isNotEmpty()) ". Failed: ${failed.joinToString(", ")}" else ""
        _status.value = msg
        return msg
    }
}
