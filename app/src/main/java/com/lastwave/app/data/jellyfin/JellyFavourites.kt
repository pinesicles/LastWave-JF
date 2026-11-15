package com.lastwave.app.data.jellyfin

import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The heart on the player for Jellyfin songs. A heart is a Jellyfin favourite, so it shows up in
 * Liked songs on every device. [keys] holds the same title|artist markers the player's heart already
 * checks, so the existing heart buttons work without changes.
 */
@Singleton
class JellyFavourites @Inject constructor(private val repository: JellyfinRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    init {
        scope.launch {
            repository.session.collect { session ->
                if (session == null) _keys.value = emptySet() else refresh()
            }
        }
    }

    private fun keyOf(track: PlayableTrack) = GeneratedTrack(name = track.title, artist = track.artist).key

    suspend fun refresh() {
        runCatching { repository.favoriteTracks() }.onSuccess { tracks -> _keys.value = tracks.map(::keyOf).toSet() }
    }

    /** Flips the favourite. Returns the new state; throws if the server refused (the heart is put back). */
    suspend fun toggle(track: PlayableTrack): Boolean = set(track, keyOf(track) !in _keys.value)

    suspend fun like(track: PlayableTrack): Boolean = set(track, true)

    private suspend fun set(track: PlayableTrack, liked: Boolean): Boolean {
        val id = track.jellyfinItemIdOrNull() ?: return false
        val key = keyOf(track)
        _keys.value = if (liked) _keys.value + key else _keys.value - key
        try {
            repository.setFavorite(id, liked)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _keys.value = if (liked) _keys.value - key else _keys.value + key
            throw e
        }
        return liked
    }
}
