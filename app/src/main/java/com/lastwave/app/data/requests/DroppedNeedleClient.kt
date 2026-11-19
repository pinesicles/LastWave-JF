package com.lastwave.app.data.requests

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class DnConfig(val url: String, val user: String, val password: String, val token: String?, val role: String)

data class DnAlbum(
    val mbid: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val inLibrary: Boolean,
    val requested: Boolean,
    val coverUrl: String? = null,
)

/** A titled row of albums from DroppedNeedle's Discover page. */
data class DnShelf(val title: String, val albums: List<DnAlbum>)

/**
 * Lets Coil load DroppedNeedle's cover images, which need the same sign-in as the API. The client keeps this
 * up to date; the shared OkHttp client adds the bearer token to requests for that one host.
 */
object DnAuth {
    @Volatile var host: String? = null
    @Volatile var token: String? = null
    val interceptor = okhttp3.Interceptor { chain ->
        val r = chain.request()
        val t = token
        if (t != null && host != null && r.url.host == host && r.header("Authorization") == null) {
            chain.proceed(r.newBuilder().header("Authorization", "Bearer $t").build())
        } else chain.proceed(r)
    }
}

data class DnRequest(
    val mbid: String,
    val title: String,
    val artist: String,
    val status: String,
    val progress: Float?,
    val error: String?,
)

class DnException(message: String) : Exception(message)

/**
 * Talks to a DroppedNeedle server (the music request app, formerly MusicSeerr) over its v3 API:
 * search the catalogue, request albums, and follow their progress. Signs in once with your
 * username and password to get a bearer token and signs in again by itself if it expires.
 * Credentials sit in the app's private DataStore, like the Jellyfin token.
 */
@Singleton
class DroppedNeedleClient @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val http: OkHttpClient,
) {
    private object K {
        val URL = stringPreferencesKey("dn_url")
        val USER = stringPreferencesKey("dn_user")
        val PASS = stringPreferencesKey("dn_pass")
        val TOKEN = stringPreferencesKey("dn_token")
        val ROLE = stringPreferencesKey("dn_role")
    }

    val config: Flow<DnConfig?> = dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            val url = p[K.URL]; val user = p[K.USER]; val pass = p[K.PASS]
            if (url.isNullOrBlank() || user.isNullOrBlank() || pass.isNullOrBlank()) null
            else DnConfig(url, user, pass, p[K.TOKEN], p[K.ROLE].orEmpty())
        }.distinctUntilChanged()

    private val authScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    init {
        authScope.launch {
            config.collect { c ->
                DnAuth.host = c?.url?.let { runCatching { it.toHttpUrl().host }.getOrNull() }
                DnAuth.token = c?.token
            }
        }
    }

    suspend fun disconnect() {
        dataStore.edit { p -> listOf(K.URL, K.USER, K.PASS, K.TOKEN, K.ROLE).forEach { p.remove(it) } }
    }

    /** Signs in and saves the details. Throws [DnException] with a readable message on failure. */
    suspend fun connect(rawUrl: String, user: String, password: String) {
        var url = rawUrl.trim().trimEnd('/')
        if (!url.startsWith("http")) url = "http://$url"
        runCatching { url.toHttpUrl() }.getOrElse { throw DnException("That doesn't look like a web address") }
        val cfg = DnConfig(url, user.trim(), password, null, "")
        val (token, role) = login(cfg)
        dataStore.edit { p ->
            p[K.URL] = url; p[K.USER] = cfg.user; p[K.PASS] = password; p[K.TOKEN] = token; p[K.ROLE] = role
        }
    }

    private suspend fun login(cfg: DnConfig): Pair<String, String> {
        val body = JSONObject().put("username", cfg.user).put("password", cfg.password).put("transport", "bearer")
        val text = raw("POST", cfg.url, "/api/v3/auth/login", body, token = null)
        val json = JSONObject(text)
        val token = json.optString("token")
        if (token.isBlank()) throw DnException("The server didn't return a sign-in token")
        return token to json.optJSONObject("user")?.optString("role").orEmpty()
    }

    private suspend fun raw(method: String, base: String, path: String, body: JSONObject?, token: String?): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(base + path)
            if (token != null) builder.header("Authorization", "Bearer $token")
            builder.header("Accept", "application/json")
            val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
            when (method) {
                "POST" -> builder.post(rb ?: "{}".toRequestBody("application/json".toMediaType()))
                else -> builder.get()
            }
            try {
                http.newCall(builder.build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.code == 401 && token != null) throw UnauthorizedException()
                    if (!r.isSuccessful) {
                        val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                        throw DnException(msg ?: "Server said ${r.code}")
                    }
                    text
                }
            } catch (e: IOException) {
                throw DnException("Couldn't reach the request server")
            }
        }

    private class UnauthorizedException : Exception()

    private suspend fun call(method: String, path: String, body: JSONObject? = null): String {
        val cfg = config.first() ?: throw DnException("Connect to your request server in Settings first")
        var token = cfg.token
        if (token.isNullOrBlank()) token = relogin(cfg)
        return try {
            raw(method, cfg.url, path, body, token)
        } catch (e: UnauthorizedException) {
            raw(method, cfg.url, path, body, relogin(cfg))
        }
    }

    private suspend fun relogin(cfg: DnConfig): String {
        val (token, role) = login(cfg)
        dataStore.edit { it[K.TOKEN] = token; it[K.ROLE] = role }
        return token
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    /** Albums matching [query] in the catalogue; flags the ones already in the library or already requested. */
    suspend fun searchAlbums(query: String): List<DnAlbum> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val arr = JSONObject(call("GET", "/api/v3/search?q=$q&limit_albums=8&limit_artists=0&limit_tracks=0")).optJSONArray("albums")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.str("musicbrainz_id") ?: o.str("id") ?: return@mapNotNull null
            DnAlbum(
                mbid = id,
                title = o.optString("title"),
                artist = o.str("artist").orEmpty(),
                year = if (o.isNull("year")) null else o.optInt("year"),
                inLibrary = o.optBoolean("in_library"),
                requested = o.optBoolean("requested"),
            )
        }
    }

    /** The albums on DroppedNeedle's Discover page that you don't have yet, grouped under their shelf titles. */
    suspend fun discover(): List<DnShelf> {
        val cfg = config.first() ?: throw DnException("Connect to your request server in Settings first")
        val base = cfg.url
        fun cover(u: String?): String? = when {
            u.isNullOrBlank() -> null
            u.startsWith("http") -> u
            else -> base + (if (u.startsWith("/")) u else "/$u")
        }
        val out = ArrayList<DnShelf>()
        fun addShelf(title: String, shelf: JSONObject?) {
            val items = shelf?.optJSONArray("items") ?: return
            val albums = (0 until items.length()).mapNotNull { i ->
                val o = items.getJSONObject(i)
                if (o.optString("kind") != "album" || o.optBoolean("in_library")) return@mapNotNull null
                val id = o.str("musicbrainz_id") ?: return@mapNotNull null
                DnAlbum(
                    mbid = id, title = o.optString("title"), artist = o.str("artist_name").orEmpty(),
                    year = if (o.isNull("year")) null else o.optInt("year"),
                    inLibrary = false, requested = o.optBoolean("requested"), coverUrl = cover(o.str("cover_url")),
                )
            }
            if (albums.size >= 3) out += DnShelf(title.ifBlank { "Discover" }, albums.take(15))
        }
        val sections = JSONObject(call("GET", "/api/v3/discover")).optJSONArray("sections") ?: return emptyList()
        for (i in 0 until sections.length()) {
            val content = sections.getJSONObject(i).optJSONObject("content") ?: continue
            when (content.optString("shape")) {
                "shelf" -> content.optJSONObject("shelf")?.let { addShelf(it.optString("title"), it) }
                "shelves" -> content.optJSONArray("shelves")?.let { a ->
                    for (j in 0 until a.length()) a.getJSONObject(j).let { addShelf(it.optString("title"), it) }
                }
                "because" -> content.optJSONArray("entries")?.let { a ->
                    for (j in 0 until a.length()) a.getJSONObject(j).let { e ->
                        addShelf("Because you listen to ${e.optString("seed_artist")}", e.optJSONObject("section"))
                    }
                }
            }
        }
        return out.take(4)
    }

    /** Returns the new request status, e.g. "queued" or "awaiting_approval". */
    suspend fun requestAlbum(album: DnAlbum): String {
        val body = JSONObject().put("musicbrainz_id", album.mbid).put("album", album.title).put("artist", album.artist)
        album.year?.let { body.put("year", it) }
        return JSONObject(call("POST", "/api/v3/requests/albums", body)).optString("status")
    }

    /** Live requests first, then recent history. */
    private fun rows(text: String): List<JSONObject> {
        val t = text.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { it.optJSONArray("items") ?: it.optJSONArray("tracks") ?: JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /**
     * Stars a song on the request server. The server stores favourites against its own ids, so this finds the
     * album from a MusicBrainz id, then the song by title. Best effort: returns false when anything doesn't match.
     */
    suspend fun setTrackFavorite(albumMbids: List<String>, title: String, favorited: Boolean): Boolean {
        val want = com.lastwave.app.data.jellyfin.importer.ImportMatcher.norm(title)
        for (mbid in albumMbids.filter { it.isNotBlank() }) {
            try {
                val a = JSONObject(call("GET", "/api/v3/library/albums/match/$mbid"))
                val albumId = a.optString("id").ifBlank { a.optJSONObject("album")?.optString("id").orEmpty() }
                if (albumId.isBlank()) continue
                val track = rows(call("GET", "/api/v3/library/albums/$albumId/tracks"))
                    .firstOrNull { com.lastwave.app.data.jellyfin.importer.ImportMatcher.norm(it.optString("title")) == want } ?: continue
                val id = track.optString("id")
                if (id.isBlank()) continue
                call("PUT", "/api/v3/favorites/track/$id", JSONObject().put("favorited", favorited).put("name", title))
                return true
            } catch (e: DnException) {
                continue
            } catch (e: org.json.JSONException) {
                continue
            }
        }
        return false
    }

    /** Favourite songs the server can read from a linked service (e.g. "spotify", "jellyfin"). Empty when not linked. */
    suspend fun remoteFavoriteTracks(source: String): List<com.lastwave.app.data.jellyfin.importer.ImportTrack> = try {
        JSONObject(call("GET", "/api/v3/remotes/$source/favorites")).optJSONArray("tracks").let { arr ->
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull {
                val title = it.optString("title")
                val artist = it.optString("artist_name")
                if (title.isBlank() || artist.isBlank()) null
                else com.lastwave.app.data.jellyfin.importer.ImportTrack(title, artist, it.optString("album_name").ifBlank { null })
            }
        }
    } catch (e: DnException) {
        emptyList()
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    class DnPlaylist(val id: String, val name: String)

    /** Your own playlists on the request server. */
    suspend fun playlists(): List<DnPlaylist> =
        rows(call("GET", "/api/v3/playlists")).filter { it.optBoolean("is_owner", true) }
            .map { DnPlaylist(it.optString("id"), it.optString("name")) }.filter { it.id.isNotBlank() }

    suspend fun createPlaylist(name: String, sourceRef: String?): String {
        val body = JSONObject().put("name", name)
        if (sourceRef != null) body.put("source_ref", sourceRef)
        return JSONObject(call("POST", "/api/v3/playlists", body)).optString("id").ifBlank { throw DnException("The server didn't return the new playlist") }
    }

    /** The (title, artist) of every song already in a server playlist. */
    suspend fun playlistSongs(id: String): List<Pair<String, String>> =
        rows(JSONObject(call("GET", "/api/v3/playlists/$id")).optJSONArray("tracks")?.toString() ?: "[]")
            .map { it.optString("track_name") to it.optString("artist_name") }

    /** Appends songs to a server playlist by name. Tries the "jellyfin" source first, then "library". */
    suspend fun addPlaylistSongs(id: String, songs: List<com.lastwave.app.data.jellyfin.importer.ImportTrack>) {
        for (chunk in songs.chunked(100)) {
            fun body(source: String) = JSONObject().put(
                "tracks",
                JSONArray().also { arr ->
                    chunk.forEach {
                        arr.put(JSONObject().put("track_name", it.title).put("artist_name", it.artist)
                            .put("album_name", it.album.orEmpty()).put("source_type", source))
                    }
                },
            )
            try {
                call("POST", "/api/v3/playlists/$id/tracks", body("jellyfin"))
            } catch (e: DnException) {
                call("POST", "/api/v3/playlists/$id/tracks", body("library"))
            }
        }
    }

    suspend fun myRequests(): List<DnRequest> {
        fun parse(text: String): List<DnRequest> {
            val arr = JSONObject(text).optJSONArray("items") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DnRequest(
                    mbid = o.optString("musicbrainz_id"),
                    title = o.optString("album_title"),
                    artist = o.optString("artist_name"),
                    status = o.optString("status"),
                    progress = if (o.isNull("progress")) null else o.optDouble("progress").toFloat(),
                    error = o.str("error_message"),
                )
            }
        }
        val active = parse(call("GET", "/api/v3/requests/active"))
        val history = parse(call("GET", "/api/v3/requests/history?page_size=25"))
        val seen = active.map { it.mbid }.toSet()
        return active + history.filter { it.mbid !in seen }
    }
}

fun dnStatusLabel(status: String): String = when (status) {
    "pending", "queued" -> "Queued"
    "downloading" -> "Downloading"
    "awaiting_approval" -> "Waiting for approval"
    "imported" -> "Added to library"
    "incomplete" -> "Partly found"
    "failed" -> "Failed"
    "rejected" -> "Rejected"
    "cancelled", "cancelling" -> "Cancelled"
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
