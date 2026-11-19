package com.lastwave.app.data.jellyfin

import kotlinx.coroutines.sync.withLock
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
    private val autoQuality: JellyAutoQuality,
    private val playTime: JellyPlayTimeQuality,
) {
    val session = store.session
    val streamKbps = store.streamKbps

    /** The streaming cap to use right now: automatic when that's switched on, otherwise your setting. */
    private suspend fun effectiveKbps(): Int =
        if (store.currentAutoQuality()) autoQuality.capKbps() else store.currentStreamKbps()

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
        val kbps = effectiveKbps()
        val page = api.tracks(s, startIndex, limit)
        return page.items.map { toPlayable(s, it, kbps) } to page.total
    }

    suspend fun randomTracks(limit: Int = 100): List<PlayableTrack> {
        val s = requireSession()
        val kbps = effectiveKbps()
        return api.tracks(s, 0, limit, sortBy = "Random").items.map { toPlayable(s, it, kbps) }
    }

    suspend fun artists(startIndex: Int = 0, limit: Int = 100, favoritesOnly: Boolean = false) =
        api.albumArtists(requireSession(), startIndex, limit, favoritesOnly)

    suspend fun artistAlbums(artistId: String) = api.artistAlbums(requireSession(), artistId)
    suspend fun playlists() = api.playlists(requireSession())
    suspend fun search(term: String) = api.search(requireSession(), term)

    suspend fun albumTracks(albumId: String): List<PlayableTrack> {
        val s = requireSession()
        val kbps = effectiveKbps()
        return api.albumTracks(s, albumId).items.map { toPlayable(s, it, kbps) }
    }

    suspend fun playlistTracks(playlistId: String): List<PlayableTrack> {
        val s = requireSession()
        val kbps = effectiveKbps()
        return api.playlistTracks(s, playlistId).items.map { toPlayable(s, it, kbps) }
    }

    /** A playlist row: the song plus the entry id used to remove or move it. */
    data class PlaylistEntry(val track: PlayableTrack, val entryId: String?)

    suspend fun playlistEntries(playlistId: String): List<PlaylistEntry> {
        val s = requireSession()
        val kbps = effectiveKbps()
        return api.playlistTracks(s, playlistId).items.map { PlaylistEntry(toPlayable(s, it, kbps), it.playlistItemId) }
    }

    /**
     * Creates the playlist with one song, then adds the rest in small batches (the same call the "add to playlist" menu
     * uses). Sending hundreds of ids in the create request can leave the playlist empty on some servers.
     */
    suspend fun createPlaylist(name: String, trackIds: List<String>): String {
        val s = requireSession()
        val id = api.createPlaylist(s, name, trackIds.take(1))
        if (trackIds.size > 1) api.addToPlaylist(s, id, trackIds.drop(1))
        // Make sure they really landed; if the server dropped them, add everything again.
        if (trackIds.isNotEmpty() && api.playlistTracks(s, id).items.isEmpty()) api.addToPlaylist(s, id, trackIds)
        return id
    }

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) =
        api.addToPlaylist(requireSession(), playlistId, trackIds)

    /** MusicBrainz ids Jellyfin holds for an item (MusicBrainzAlbum, MusicBrainzReleaseGroup, ...). */
    suspend fun providerIds(itemId: String): Map<String, String> = api.item(requireSession(), itemId).providerIds

    suspend fun refreshLibrary() = api.refreshLibrary(requireSession())

    suspend fun albumTrackIds(albumId: String): List<String> =
        api.albumTracks(requireSession(), albumId).items.map { it.id }

    suspend fun removeFromPlaylist(playlistId: String, entryId: String) =
        api.removeFromPlaylist(requireSession(), playlistId, listOf(entryId))

    suspend fun movePlaylistItem(playlistId: String, entryId: String, newIndex: Int) =
        api.movePlaylistItem(requireSession(), playlistId, entryId, newIndex)

    suspend fun renamePlaylist(playlistId: String, name: String) =
        api.renamePlaylist(requireSession(), playlistId, name)

    suspend fun deletePlaylist(playlistId: String) = api.deleteItem(requireSession(), playlistId)

    suspend fun genreNames(): List<String> = api.genreNames(requireSession())

    suspend fun albumsByGenres(genres: List<String>, limit: Int = 20): List<JfItem> =
        api.albumsByGenres(requireSession(), genres, limit).items

    suspend fun similarAlbums(id: String, limit: Int = 15): List<JfItem> = api.similar(requireSession(), "Albums", id, limit).items

    suspend fun similarArtists(id: String, limit: Int = 15): List<JfItem> = api.similar(requireSession(), "Artists", id, limit).items

    suspend fun favoriteAlbums(limit: Int = 30): List<JfItem> = api.albums(requireSession(), 0, limit, true).items

    suspend fun favoriteArtists(limit: Int = 30): List<JfItem> = api.albumArtists(requireSession(), 0, limit, true).items

    /** Finds the library artist with this exact (normalised) name, or null. */
    suspend fun findArtist(name: String): JfItem? {
        val n = com.lastwave.app.data.jellyfin.importer.ImportMatcher.norm(name)
        return api.search(requireSession(), name, 20).items
            .firstOrNull { it.type == "MusicArtist" && com.lastwave.app.data.jellyfin.importer.ImportMatcher.norm(it.name.orEmpty()) == n }
    }

    /**
     * Finds a song by title first, then checks the artist: searches the title (with and without "(Remastered)" style
     * tails), then keeps the candidate whose title and artists match best. Looser than exact matching on both.
     */
    suspend fun findSong(title: String, artist: String): PlayableTrack? {
        val m = com.lastwave.app.data.jellyfin.importer.ImportMatcher
        var best: PlayableTrack? = null
        var bestScore = 0.0
        for (q in linkedSetOf(title, m.baseTitle(title)).filter { it.isNotBlank() }) {
            for (c in searchSongs(q, 60)) {
                val ts = m.titleScore(c.title, title)
                if (ts < 0.85) continue
                val a = if (artist.isBlank()) 1.0 else m.artistScore(artist, c.artist)
                if (a < 0.5) continue
                if (ts + a > bestScore) { best = c; bestScore = ts + a }
            }
            if (best != null && bestScore >= 1.9) break
        }
        if (best != null) return best
        best = findSongLoose(title, artist)
        if (best != null || artist.isBlank()) return best
        // Common titles ("Intro", "Home") get buried in a plain search, so look inside the artist's own songs instead.
        val s = requireSession()
        val kbps = effectiveKbps()
        val artists = api.search(s, m.artistParts0(artist).firstOrNull() ?: artist, 10).items
            .filter { it.type == "MusicArtist" && m.artistScore(artist, it.name.orEmpty()) >= 0.8 }.take(3)
        for (a in artists) {
            for (term in listOf(title, m.baseTitle(title), null)) {
                val songs = api.songsByArtist(s, a.id, term, if (term == null) 600 else 100).items.map { toPlayable(s, it, kbps) }
                for (c in songs) {
                    val ts = m.titleScore(c.title, title)
                    if (ts >= 0.85 && ts > bestScore) { best = c; bestScore = ts }
                }
                if (best != null) return best
            }
        }
        return best
    }

    private class IndexEntry(val id: String, val title: String, val artists: List<String>)
    private var songIndex: List<IndexEntry>? = null
    private var songIndexAt = 0L

    private val indexLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun index(): List<IndexEntry> = indexLock.withLock {
        songIndex?.let { if (System.currentTimeMillis() - songIndexAt < 30 * 60_000L) return@withLock it }
        val s = requireSession()
        val all = ArrayList<IndexEntry>()
        var start = 0
        while (true) {
            val page = api.songPage(s, start, 2000)
            page.items.forEach { all += IndexEntry(it.id, it.name.orEmpty(), (it.artists + listOfNotNull(it.albumArtist)).distinct()) }
            start += page.items.size
            if (page.items.isEmpty() || start >= page.total) break
        }
        songIndex = all
        songIndexAt = System.currentTimeMillis()
        all
    }

    /** Last resort: compares against the whole library by sound, so "karyera" finds "карьера" and the reverse. */
    private suspend fun findSongLoose(title: String, artist: String): PlayableTrack? {
        val L = com.lastwave.app.data.jellyfin.importer.LooseMatch
        var bestId: String? = null
        var bestScore = 0.0
        for (e in index()) {
            val t = L.titleRatio(title, e.title)
            if (t < 0.8) continue
            val a = if (artist.isBlank()) 1.0 else L.artistRatio(artist, e.artists)
            if (a < 0.75) continue
            if (t + a > bestScore) { bestScore = t + a; bestId = e.id }
        }
        val id = bestId ?: return null
        val s = requireSession()
        return toPlayable(s, api.item(s, id), effectiveKbps())
    }

    suspend fun findAlbum(name: String, artist: String?): JfItem? {
        val m = com.lastwave.app.data.jellyfin.importer.ImportMatcher
        val n = m.norm(name)
        val a = artist?.let { m.norm(it) }
        return api.search(requireSession(), name, 20).items.firstOrNull {
            it.type == "MusicAlbum" && m.norm(it.name.orEmpty()) == n &&
                (a == null || m.norm(it.albumArtist.orEmpty()).let { x -> x.contains(a) || a.contains(x) })
        }
    }

    suspend fun searchSongs(term: String, limit: Int = 20): List<PlayableTrack> {
        val s = requireSession()
        val kbps = effectiveKbps()
        return api.searchSongs(s, term, limit).items.map { toPlayable(s, it, kbps) }
    }

    suspend fun item(id: String): JfItem = api.item(requireSession(), id)

    /** Finds the best library match for an imported song, trying a few search phrasings. */
    suspend fun matchImport(src: com.lastwave.app.data.jellyfin.importer.ImportTrack):
        com.lastwave.app.data.jellyfin.importer.ImportOutcome {
        val matcher = com.lastwave.app.data.jellyfin.importer.ImportMatcher
        val terms = listOf(
            matcher.baseTitle(src.title),
            src.title,
            matcher.baseTitle(src.title).split(' ').take(3).joinToString(" "),
        ).filter { it.isNotBlank() }.distinct()
        val found = LinkedHashMap<String, PlayableTrack>()
        for (term in terms) {
            val hits = try { searchSongs(term, 50) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            hits.forEach { h -> h.internalTrackId?.let { found.putIfAbsent(it, h) } }
            val best = matcher.rank(src, found.values.toList()).firstOrNull()
            if (best != null && matcher.accepts(src, best)) {
                return com.lastwave.app.data.jellyfin.importer.ImportOutcome(src, best, emptyList())
            }
        }
        // Title-first fallback: same title with a plausibly matching artist, even when the overall score is low.
        val byTitle = try { findSong(src.title, src.artist) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        if (byTitle != null) return com.lastwave.app.data.jellyfin.importer.ImportOutcome(src, byTitle, emptyList())
        val ranked = matcher.rank(src, found.values.toList())
        return com.lastwave.app.data.jellyfin.importer.ImportOutcome(src, null, ranked.take(3))
    }

    suspend fun searchHits(term: String): SearchHits {
        val s = requireSession()
        val kbps = effectiveKbps()
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
        val kbps = effectiveKbps()
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

    fun replaceLocalCover(id: String, bytes: ByteArray) = downloads.replaceCover(id, bytes)

    suspend fun favoriteTracks(): List<PlayableTrack> {
        val s = requireSession()
        val kbps = effectiveKbps()
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
        playTime.remember(item.id, sourceKbps)
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
