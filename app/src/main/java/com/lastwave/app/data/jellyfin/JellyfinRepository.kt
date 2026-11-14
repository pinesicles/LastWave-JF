package com.lastwave.app.data.jellyfin

import com.lastwave.app.playback.PlayableTrack
import javax.inject.Inject
import javax.inject.Singleton

/** Prefix on [PlayableTrack.internalTrackId] for tracks that come from Jellyfin. */
const val JELLYFIN_TRACK_PREFIX = "jf:"

fun PlayableTrack.jellyfinItemIdOrNull(): String? =
    internalTrackId?.takeIf { it.startsWith(JELLYFIN_TRACK_PREFIX) }?.removePrefix(JELLYFIN_TRACK_PREFIX)

/** What the UI works with: login state plus library and playback helpers. */
/** Search results, grouped. Used for server search and for searching saved items. */
data class SearchHits(
    val albums: List<JfItem> = emptyList(),
    val artists: List<JfItem> = emptyList(),
    val playlists: List<JfItem> = emptyList(),
    val tracks: List<PlayableTrack> = emptyList(),
)

@Singleton
class JellyfinRepository @Inject constructor(
    private val api: JellyfinApi,
    private val store: JellyfinSessionStore,
    private val downloads: com.lastwave.app.data.jellyfin.download.JellyDownloads,
) {
    val session = store.session
    val streamKbps = store.streamKbps

    suspend fun login(serverInput: String, username: String, password: String): JellyfinSession {
        val serverUrl = JellyfinApi.normalizeServerUrl(serverInput)
        if (serverUrl.isEmpty()) throw JellyfinException("Enter your server address")
        val info = api.publicInfo(serverUrl)
        val deviceId = store.deviceId()
        val auth = api.authenticate(serverUrl, username.trim(), password, deviceId)
        val session = JellyfinSession(
            serverUrl = serverUrl,
            token = auth.accessToken,
            userId = auth.user.id,
            deviceId = deviceId,
            serverName = info.serverName.orEmpty(),
            serverVersion = info.version.orEmpty(),
        )
        store.save(session)
        return session
    }

    suspend fun logout() = store.clear()

    suspend fun setStreamKbps(kbps: Int) = store.setStreamKbps(kbps)

    private suspend fun requireSession(): JellyfinSession =
        store.current() ?: throw JellyfinException("Not signed in to Jellyfin")

    suspend fun albums(
        startIndex: Int = 0,
        limit: Int = 100,
        favoritesOnly: Boolean = false,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
    ) = api.albums(requireSession(), startIndex, limit, favoritesOnly, sortBy, sortOrder)

    suspend fun recentlyAddedAlbums(limit: Int = 20) = albums(0, limit, false, "DateCreated", "Descending")
    suspend fun randomAlbums(limit: Int = 20) = albums(0, limit, false, "Random", "Ascending")

    /** One page of every song on the server, already mapped for the player. */
    suspend fun songsPage(startIndex: Int, limit: Int): Pair<List<PlayableTrack>, Int> {
        val s = requireSession()
        val kbps = store.currentStreamKbps()
        val page = api.tracks(s, startIndex, limit)
        return page.items.map { toPlayable(s, it, kbps) } to page.total
    }

    suspend fun randomTracks(limit: Int = 100): List<PlayableTrack> {
        val s = requireSession()
        val kbps = store.currentStreamKbps()
        return api.tracks(s, 0, limit, sortBy = "Random").items.map { toPlayable(s, it, kbps) }
    }

    suspend fun artists(startIndex: Int = 0, limit: Int = 100, favoritesOnly: Boolean = false) =
        api.albumArtists(requireSession(), startIndex, limit, favoritesOnly)

    suspend fun artistAlbums(artistId: String) = api.artistAlbums(requireSession(), artistId)
    suspend fun playlists() = api.playlists(requireSession())
    suspend fun search(term: String) = api.search(requireSession(), term)

    suspend fun albumTracks(albumId: String): List<PlayableTrack> {
        val s = requireSession()
        val kbps = store.currentStreamKbps()
        return api.albumTracks(s, albumId).items.map { toPlayable(s, it, kbps) }
    }

    suspend fun playlistTracks(playlistId: String): List<PlayableTrack> {
        val s = requireSession()
        val kbps = store.currentStreamKbps()
        return api.playlistTracks(s, playlistId).items.map { toPlayable(s, it, kbps) }
    }

    /** A playlist row: the song plus the entry id used to remove or move it. */
    data class PlaylistEntry(val track: PlayableTrack, val entryId: String?)

    suspend fun playlistEntries(playlistId: String): List<PlaylistEntry> {
        val s = requireSession()
        val kbps = store.currentStreamKbps()
        return api.playlistTracks(s, playlistId).items.map { PlaylistEntry(toPlayable(s, it, kbps), it.playlistItemId) }
    }

    suspend fun createPlaylist(name: String, trackIds: List<String>) =
        api.createPlaylist(requireSession(), name, trackIds)

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) =
        api.addToPlaylist(requireSession(), playlistId, trackIds)

    suspend fun removeFromPlaylist(playlistId: String, entryId: String) =
        api.removeFromPlaylist(requireSession(), playlistId, listOf(entryId))

    suspend fun movePlaylistItem(playlistId: String, entryId: String, newIndex: Int) =
        api.movePlaylistItem(requireSession(), playlistId, entryId, newIndex)

    suspend fun renamePlaylist(playlistId: String, name: String) =
        api.renamePlaylist(requireSession(), playlistId, name)

    suspend fun deletePlaylist(playlistId: String) = api.deleteItem(requireSession(), playlistId)

    suspend fun searchHits(term: String): SearchHits {
        val s = requireSession()
        val kbps = store.currentStreamKbps()
        val items = api.search(s, term).items
        return SearchHits(
            albums = items.filter { it.type == "MusicAlbum" },
            artists = items.filter { it.type == "MusicArtist" },
            tracks = items.filter { it.type == "Audio" }.map { toPlayable(s, it, kbps) },
        )
    }

    /** Everything saved (hearted albums/artists, playlists, liked songs), for searching locally. */
    suspend fun savedHits(): SearchHits {
        val s = requireSession()
        val kbps = store.currentStreamKbps()
        return SearchHits(
            albums = api.albums(s, 0, 500, true).items,
            artists = api.albumArtists(s, 0, 500, true).items,
            playlists = api.playlists(s).items,
            tracks = api.favoriteTracks(s).items.map { toPlayable(s, it, kbps) },
        )
    }

    /** Downloaded songs of an album/playlist, and whether every song has finished downloading. */
    suspend fun localCollection(id: String): Pair<List<PlayableTrack>, Boolean> {
        val rows = downloads.tracksOf(id)
        val playable = rows.mapNotNull { t ->
            val uri = downloads.localUri(t.itemId) ?: return@mapNotNull null
            PlayableTrack(
                title = t.title,
                artist = t.artist,
                album = t.album,
                artworkUrl = downloads.localCover(t.albumId),
                internalTrackId = JELLYFIN_TRACK_PREFIX + t.itemId,
                playbackUrl = uri,
                durationMs = t.durationMs,
                trackNumber = t.trackNumber,
            )
        }
        return playable to (rows.isNotEmpty() && playable.size == rows.size)
    }

    suspend fun setItemImage(itemId: String, bytes: ByteArray, mime: String) =
        api.setItemImage(requireSession(), itemId, bytes, mime)

    fun coverUrlForTag(session: JellyfinSession, itemId: String, tag: String, size: Int = 400): String =
        api.imageUrl(session, itemId, tag, size)

    fun localCover(id: String): String? = downloads.localCover(id)

    suspend fun favoriteTracks(): List<PlayableTrack> {
        val s = requireSession()
        val kbps = store.currentStreamKbps()
        return api.favoriteTracks(s).items.map { toPlayable(s, it, kbps) }
    }

    suspend fun setFavorite(itemId: String, favorite: Boolean) =
        api.setFavorite(requireSession(), itemId, favorite)

    /** Cover art URL for a row, or null when the item has no picture. No network call. */
    fun coverUrl(session: JellyfinSession, item: JfItem, size: Int = 400): String? {
        val tag = item.imageTags["Primary"] ?: return null
        return api.imageUrl(session, item.id, tag, size)
    }

    /** Cover art URL for an album/artist/playlist row. */
    suspend fun imageUrl(item: JfItem, size: Int = 600): String? {
        val s = store.current() ?: return null
        val tag = item.imageTags["Primary"] ?: return null
        return api.imageUrl(s, item.id, tag, size)
    }

    fun toPlayable(session: JellyfinSession, item: JfItem, maxKbps: Int): PlayableTrack {
        val source = item.mediaSources.firstOrNull()
        val sourceKbps = source?.bitrate?.let { (it / 1000L).toInt() }
        val target = api.streamTarget(session, item.id, maxKbps, sourceKbps)
        val artistText = item.artists.joinToString(", ").ifBlank { item.albumArtist.orEmpty() }
        // Tracks without their own cover use the album's.
        val artId = if (item.imageTags.containsKey("Primary")) item.id else (item.albumId ?: item.id)
        val artTag = item.imageTags["Primary"] ?: item.albumPrimaryImageTag
        val local = downloads.localUri(item.id)
        return PlayableTrack(
            title = item.name.orEmpty().ifBlank { "Unknown track" },
            artist = artistText.ifBlank { "Unknown artist" },
            album = item.album,
            artworkUrl = downloads.localCover(item.albumId ?: item.id) ?: api.imageUrl(session, artId, artTag),
            internalTrackId = JELLYFIN_TRACK_PREFIX + item.id,
            playbackUrl = local ?: target.url,
            playbackMimeType = if (local != null) null else target.mimeType,
            durationMs = item.durationMs,
            trackNumber = item.trackNumber,
        )
    }
}
