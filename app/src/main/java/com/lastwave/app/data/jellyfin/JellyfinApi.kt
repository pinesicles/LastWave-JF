package com.lastwave.app.data.jellyfin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal val JellyfinJson = Json {
    ignoreUnknownKeys = true
    // Jellyfin sends explicit nulls for many fields; fall back to our defaults.
    coerceInputValues = true
}

/** How to play one Jellyfin track. [mimeType] is only set when the player needs a hint (HLS). */
data class JfStreamTarget(val url: String, val mimeType: String?, val transcoded: Boolean)

/**
 * Thin Jellyfin REST client (no SDK): just the calls a music app needs.
 * Uses endpoints that exist across Jellyfin 10.8+.
 */
@Singleton
class JellyfinApi @Inject constructor(
    private val http: OkHttpClient,
) {
    companion object {
        const val CLIENT_NAME = "JellyWave"
        const val CLIENT_VERSION = "0.1.0"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val HLS_MIME = "application/x-mpegURL"
        private const val ITEM_FIELDS = "MediaSources,ProviderIds,ChildCount"

        /** "192.168.1.5:8096/" -> "http://192.168.1.5:8096". A typed scheme is kept. */
        fun normalizeServerUrl(input: String): String {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return s
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
                s = "http://$s"
            }
            return s
        }

        fun authHeader(deviceId: String, token: String?): String = buildString {
            append("MediaBrowser Client=\"").append(CLIENT_NAME).append("\", ")
            append("Device=\"Android\", ")
            append("DeviceId=\"").append(deviceId).append("\", ")
            append("Version=\"").append(CLIENT_VERSION).append('"')
            if (!token.isNullOrBlank()) append(", Token=\"").append(token).append('"')
        }
    }

    // ---- requests -------------------------------------------------------

    private fun urlBuilder(baseUrl: String, vararg segments: String): HttpUrl.Builder {
        val base = baseUrl.toHttpUrlOrNull() ?: throw JellyfinException("Not a valid server address")
        return base.newBuilder().apply { segments.forEach { addPathSegment(it) } }
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = when (r.code) {
                        401 -> "Login rejected (wrong username or password?)"
                        403 -> "Access denied by the server"
                        404 -> "Not found on the server"
                        else -> "Server returned HTTP ${r.code}"
                    }
                    throw JellyfinException(msg, r.code)
                }
                body
            }
        } catch (e: JellyfinException) {
            throw e
        } catch (e: IOException) {
            throw JellyfinException("Couldn't reach the server: ${e.message ?: "network error"}", cause = e)
        }
    }

    private fun authed(session: JellyfinSession, url: HttpUrl) = Request.Builder()
        .url(url)
        .header("Authorization", authHeader(session.deviceId, session.token))

    private suspend fun getItems(session: JellyfinSession, url: HttpUrl): JfPage {
        val body = execute(authed(session, url).get().build())
        val parsed = try {
            JellyfinJson.decodeFromString(JfItemsResult.serializer(), body)
        } catch (e: Exception) {
            throw JellyfinException("Unexpected response from server", cause = e)
        }
        return JfPage(parsed.items, parsed.totalRecordCount, parsed.startIndex)
    }

    // ---- login ----------------------------------------------------------

    /** Unauthenticated; also a quick "is this a Jellyfin server" check. */
    suspend fun publicInfo(serverUrl: String): JfPublicInfo {
        val url = urlBuilder(serverUrl, "System", "Info", "Public").build()
        val body = execute(Request.Builder().url(url).get().build())
        return try {
            JellyfinJson.decodeFromString(JfPublicInfo.serializer(), body)
        } catch (e: Exception) {
            throw JellyfinException("That doesn't look like a Jellyfin server", cause = e)
        }
    }

    suspend fun authenticate(serverUrl: String, username: String, password: String, deviceId: String): JfAuthResult {
        val url = urlBuilder(serverUrl, "Users", "AuthenticateByName").build()
        val payload = JellyfinJson.encodeToString(JfAuthRequest.serializer(), JfAuthRequest(username, password))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(deviceId, null))
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val body = execute(request)
        return try {
            JellyfinJson.decodeFromString(JfAuthResult.serializer(), body)
        } catch (e: Exception) {
            throw JellyfinException("Unexpected login response", cause = e)
        }
    }

    // ---- library --------------------------------------------------------

    suspend fun albums(
        session: JellyfinSession,
        startIndex: Int = 0,
        limit: Int = 100,
        favoritesOnly: Boolean = false,
    ): JfPage = getItems(
        session,
        urlBuilder(session.serverUrl, "Items").apply {
            addQueryParameter("userId", session.userId)
            addQueryParameter("includeItemTypes", "MusicAlbum")
            addQueryParameter("recursive", "true")
            addQueryParameter("sortBy", "SortName")
            addQueryParameter("sortOrder", "Ascending")
            addQueryParameter("fields", "ChildCount,ProviderIds")
            addQueryParameter("startIndex", startIndex.toString())
            addQueryParameter("limit", limit.toString())
            addQueryParameter("enableTotalRecordCount", "true")
            if (favoritesOnly) addQueryParameter("isFavorite", "true")
        }.build(),
    )

    suspend fun albumArtists(session: JellyfinSession, startIndex: Int = 0, limit: Int = 100): JfPage = getItems(
        session,
        urlBuilder(session.serverUrl, "Artists", "AlbumArtists").apply {
            addQueryParameter("userId", session.userId)
            addQueryParameter("sortBy", "SortName")
            addQueryParameter("sortOrder", "Ascending")
            addQueryParameter("startIndex", startIndex.toString())
            addQueryParameter("limit", limit.toString())
            addQueryParameter("enableTotalRecordCount", "true")
        }.build(),
    )

    suspend fun artistAlbums(session: JellyfinSession, artistId: String): JfPage = getItems(
        session,
        urlBuilder(session.serverUrl, "Items").apply {
            addQueryParameter("userId", session.userId)
            addQueryParameter("albumArtistIds", artistId)
            addQueryParameter("includeItemTypes", "MusicAlbum")
            addQueryParameter("recursive", "true")
            addQueryParameter("sortBy", "ProductionYear,SortName")
            addQueryParameter("sortOrder", "Descending")
        }.build(),
    )

    suspend fun albumTracks(session: JellyfinSession, albumId: String): JfPage = getItems(
        session,
        urlBuilder(session.serverUrl, "Items").apply {
            addQueryParameter("userId", session.userId)
            addQueryParameter("parentId", albumId)
            addQueryParameter("includeItemTypes", "Audio")
            addQueryParameter("sortBy", "ParentIndexNumber,IndexNumber,SortName")
            addQueryParameter("fields", ITEM_FIELDS)
        }.build(),
    )

    suspend fun playlists(session: JellyfinSession): JfPage = getItems(
        session,
        urlBuilder(session.serverUrl, "Items").apply {
            addQueryParameter("userId", session.userId)
            addQueryParameter("includeItemTypes", "Playlist")
            addQueryParameter("recursive", "true")
            addQueryParameter("sortBy", "SortName")
            addQueryParameter("fields", "ChildCount")
        }.build(),
    )

    suspend fun playlistTracks(session: JellyfinSession, playlistId: String): JfPage = getItems(
        session,
        urlBuilder(session.serverUrl, "Playlists", playlistId, "Items").apply {
            addQueryParameter("userId", session.userId)
            addQueryParameter("fields", ITEM_FIELDS)
        }.build(),
    )

    suspend fun favoriteTracks(session: JellyfinSession, startIndex: Int = 0, limit: Int = 200): JfPage = getItems(
        session,
        urlBuilder(session.serverUrl, "Items").apply {
            addQueryParameter("userId", session.userId)
            addQueryParameter("includeItemTypes", "Audio")
            addQueryParameter("recursive", "true")
            addQueryParameter("isFavorite", "true")
            addQueryParameter("sortBy", "SortName")
            addQueryParameter("fields", ITEM_FIELDS)
            addQueryParameter("startIndex", startIndex.toString())
            addQueryParameter("limit", limit.toString())
            addQueryParameter("enableTotalRecordCount", "true")
        }.build(),
    )

    suspend fun search(session: JellyfinSession, term: String, limit: Int = 50): JfPage = getItems(
        session,
        urlBuilder(session.serverUrl, "Items").apply {
            addQueryParameter("userId", session.userId)
            addQueryParameter("searchTerm", term)
            addQueryParameter("includeItemTypes", "Audio,MusicAlbum,MusicArtist")
            addQueryParameter("recursive", "true")
            addQueryParameter("fields", ITEM_FIELDS)
            addQueryParameter("limit", limit.toString())
        }.build(),
    )

    /** Favourites live on the server, so they follow you across devices. */
    suspend fun setFavorite(session: JellyfinSession, itemId: String, favorite: Boolean) {
        val url = urlBuilder(session.serverUrl, "Users", session.userId, "FavoriteItems", itemId).build()
        val builder = authed(session, url)
        val request = if (favorite) {
            builder.post("".toRequestBody(JSON_MEDIA)).build()
        } else {
            builder.delete().build()
        }
        execute(request)
    }

    // ---- URL builders (no network) ---------------------------------------

    fun imageUrl(session: JellyfinSession, itemId: String, tag: String?, size: Int = 600): String =
        urlBuilder(session.serverUrl, "Items", itemId, "Images", "Primary").apply {
            addQueryParameter("fillWidth", size.toString())
            addQueryParameter("fillHeight", size.toString())
            addQueryParameter("quality", "90")
            if (!tag.isNullOrBlank()) addQueryParameter("tag", tag)
        }.build().toString()

    /** The original file, untouched. Supports seeking (range requests). */
    fun originalUrl(session: JellyfinSession, itemId: String): String =
        urlBuilder(session.serverUrl, "Audio", itemId, "stream").apply {
            addQueryParameter("static", "true")
            addQueryParameter("deviceId", session.deviceId)
            addQueryParameter("api_key", session.token)
        }.build().toString()

    /** What "download" fetches: the original file. */
    fun downloadUrl(session: JellyfinSession, itemId: String): String =
        urlBuilder(session.serverUrl, "Items", itemId, "Download").apply {
            addQueryParameter("api_key", session.token)
        }.build().toString()

    /** Server-side transcode to AAC over HLS, capped at [maxKbps]. */
    fun transcodedHlsUrl(session: JellyfinSession, itemId: String, maxKbps: Int): String =
        urlBuilder(session.serverUrl, "Audio", itemId, "universal").apply {
            addQueryParameter("userId", session.userId)
            addQueryParameter("deviceId", session.deviceId)
            addQueryParameter("maxStreamingBitrate", (maxKbps * 1000L).toString())
            addQueryParameter("container", "aac")
            addQueryParameter("audioCodec", "aac")
            addQueryParameter("transcodingContainer", "ts")
            addQueryParameter("transcodingProtocol", "hls")
            addQueryParameter("api_key", session.token)
        }.build().toString()

    /**
     * Picks the original when no cap is set or the file already fits under it,
     * otherwise a capped transcode. [sourceKbps] is the file's bitrate, if known.
     */
    fun streamTarget(session: JellyfinSession, itemId: String, maxKbps: Int, sourceKbps: Int?): JfStreamTarget {
        val fits = maxKbps <= 0 || (sourceKbps != null && sourceKbps <= maxKbps)
        return if (fits) {
            JfStreamTarget(originalUrl(session, itemId), mimeType = null, transcoded = false)
        } else {
            JfStreamTarget(transcodedHlsUrl(session, itemId, maxKbps), HLS_MIME, transcoded = true)
        }
    }
}
