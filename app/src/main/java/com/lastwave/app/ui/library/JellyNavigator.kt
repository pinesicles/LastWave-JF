package com.lastwave.app.ui.library

import com.lastwave.app.data.jellyfin.JellyfinRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Go to artist / album" from anywhere (Now Playing, track menus). Looks the name up in the Jellyfin
 * library; if it's there, the page is queued here and MainShell opens it in the Library tab. If not,
 * the caller falls back to the old YouTube artist/album screens.
 */
@Singleton
class JellyNavigator @Inject constructor(private val repository: JellyfinRepository) {
    private val _pending = MutableStateFlow<LibraryDetail?>(null)
    val pending: StateFlow<LibraryDetail?> = _pending.asStateFlow()

    /** True when found and queued. */
    suspend fun tryArtist(name: String): Boolean {
        if (repository.session.first() == null) return false
        val item = runCatching { repository.findArtist(name) }.getOrNull() ?: return false
        _pending.value = LibraryDetail.Artist(item)
        return true
    }

    suspend fun tryAlbum(title: String, artist: String?): Boolean {
        if (repository.session.first() == null) return false
        val item = runCatching { repository.findAlbum(title, artist?.takeIf { it.isNotBlank() }) }.getOrNull() ?: return false
        _pending.value = LibraryDetail.Album(item)
        return true
    }

    fun consume() { _pending.value = null }
}
