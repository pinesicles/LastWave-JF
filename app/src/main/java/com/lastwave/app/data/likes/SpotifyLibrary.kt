package com.lastwave.app.data.likes

import com.lastwave.app.data.jellyfin.importer.ImportMatcher
import com.lastwave.app.data.jellyfin.importer.ImportTrack
import com.lastwave.app.data.playlist.SpotifyPlaylistImporter
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Reads and changes your Spotify Liked Songs using the Spotify session the app already has (Settings → Accounts). */
@Singleton
class SpotifyLibrary @Inject constructor(
    private val http: OkHttpClient,
    private val auth: SpotifyAuthRepository,
    private val importer: SpotifyPlaylistImporter,
) {
    /** Your Spotify Liked Songs, or null when Spotify isn't connected or can't be read. */
    suspend fun liked(): List<ImportTrack>? = try {
        if (auth.getPersonalToken() == null) null
        else importer.fetchLikedSongs(maxPages = 200).rows.map { ImportTrack(it.title, it.artist, it.album) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Spotify tracks matching a free-text search, for picking a match by hand. */
    suspend fun candidates(query: String): List<MatchCandidate> = withContext(Dispatchers.IO) {
        try {
            val token = auth.getPersonalToken() ?: return@withContext emptyList()
            val url = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("type", "track").addQueryParameter("limit", "15").addQueryParameter("q", query).build()
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()).execute().use { r ->
                if (r.code != 200) return@withContext emptyList()
                val items = JSONObject(r.body?.string().orEmpty()).optJSONObject("tracks")?.optJSONArray("items") ?: return@withContext emptyList()
                (0 until items.length()).map { items.getJSONObject(it) }.map { t ->
                    MatchCandidate(
                        t.optString("id"), t.optString("name"),
                        t.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty(),
                        t.optJSONObject("album")?.optString("name")?.ifBlank { null },
                    )
                }.filter { it.id.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun likeId(id: String, liked: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = auth.getPersonalToken() ?: return@withContext false
            val url = "https://api.spotify.com/v1/me/tracks".toHttpUrl().newBuilder().addQueryParameter("ids", id).build()
            val b = Request.Builder().url(url).header("Authorization", "Bearer $token")
            http.newCall(if (liked) b.put("".toRequestBody()).build() else b.delete().build()).execute().use { it.code in 200..299 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Adds or removes one song. Finds it on Spotify by title and artist first. Returns true when Spotify accepted it. */
    suspend fun setLiked(title: String, artist: String, liked: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = auth.getPersonalToken() ?: return@withContext false
            val search = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("type", "track").addQueryParameter("limit", "5")
                .addQueryParameter("q", "track:$title artist:$artist").build()
            val body = http.newCall(Request.Builder().url(search).header("Authorization", "Bearer $token").get().build()).execute().use { r ->
                if (r.code != 200) return@withContext false
                JSONObject(r.body?.string().orEmpty())
            }
            val items = body.optJSONObject("tracks")?.optJSONArray("items") ?: return@withContext false
            val want = ImportMatcher.norm(title)
            var id: String? = null
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                if (ImportMatcher.norm(it.optString("name")) == want) { id = it.optString("id"); break }
            }
            if (id.isNullOrBlank()) return@withContext false
            val url = "https://api.spotify.com/v1/me/tracks".toHttpUrl().newBuilder().addQueryParameter("ids", id).build()
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
            val req = if (liked) builder.put("".toRequestBody()).build() else builder.delete().build()
            http.newCall(req).execute().use { it.code in 200..299 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
