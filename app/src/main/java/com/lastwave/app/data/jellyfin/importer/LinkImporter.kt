package com.lastwave.app.data.jellyfin.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class LinkKind { SPOTIFY, YOUTUBE, APPLE, TIDAL, UNKNOWN }

/**
 * Reads public playlists through the services' official APIs, using your own free keys:
 *  - Spotify: a client id and secret from developer.spotify.com (public playlists only)
 *  - YouTube: a Data API key from console.cloud.google.com
 * Apple Music and Tidal only offer developer-token APIs, so for those export a CSV instead.
 */
@Singleton
class LinkImporter @Inject constructor(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun kindOf(link: String): LinkKind = when {
        "spotify" in link.lowercase() -> LinkKind.SPOTIFY
        "youtube.com" in link.lowercase() || "youtu.be" in link.lowercase() -> LinkKind.YOUTUBE
        "music.apple.com" in link.lowercase() -> LinkKind.APPLE
        "tidal.com" in link.lowercase() -> LinkKind.TIDAL
        else -> LinkKind.UNKNOWN
    }

    private fun get(request: Request): String = try {
        http.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ImportException(httpMessage(r.code))
            body
        }
    } catch (e: IOException) {
        throw ImportException("Couldn't reach the service: ${e.message ?: "network error"}")
    }

    private fun httpMessage(code: Int) = when (code) {
        400, 401 -> "The service rejected your key (HTTP $code). Check it in the key settings."
        403 -> "Access denied (HTTP 403). The playlist may be private, or the key isn't allowed to read it."
        404 -> "Playlist not found (HTTP 404). It may be private or removed; try exporting a CSV."
        429 -> "Rate limited by the service. Try again in a minute."
        else -> "The service returned HTTP $code"
    }

    // ---- Spotify ----

    suspend fun spotify(link: String, clientId: String, clientSecret: String): ParsedPlaylist = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw ImportException("Add your Spotify client id and secret under “Keys for link import”.")
        }
        val id = Regex("playlist[/:]([A-Za-z0-9]{22})").find(link)?.groupValues?.get(1)
            ?: link.trim().takeIf { Regex("[A-Za-z0-9]{22}").matches(it) }
            ?: throw ImportException("That doesn't look like a Spotify playlist link.")
        val token = get(
            Request.Builder().url("https://accounts.spotify.com/api/token")
                .header("Authorization", Credentials.basic(clientId.trim(), clientSecret.trim()))
                .post(FormBody.Builder().add("grant_type", "client_credentials").build())
                .build(),
        ).let { json.parseToJsonElement(it).jsonObject["access_token"]?.jsonPrimitive?.contentOrNull }
            ?: throw ImportException("Spotify didn't return a token.")
        fun api(url: okhttp3.HttpUrl) = get(Request.Builder().url(url).header("Authorization", "Bearer $token").build())

        val name = runCatching {
            json.parseToJsonElement(
                api("https://api.spotify.com/v1/playlists/$id".toHttpUrl().newBuilder().addQueryParameter("fields", "name").build()),
            ).jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val tracks = ArrayList<ImportTrack>()
        var offset = 0
        while (true) {
            val body = api(
                "https://api.spotify.com/v1/playlists/$id/tracks".toHttpUrl().newBuilder()
                    .addQueryParameter("limit", "100").addQueryParameter("offset", offset.toString()).build(),
            )
            tracks += ImportParsers.parseJson(body).tracks
            val next = json.parseToJsonElement(body).jsonObject["next"]
            if (next == null || next is kotlinx.serialization.json.JsonNull || offset > 5_000) break
            offset += 100
        }
        ParsedPlaylist(name, tracks)
    }

    // ---- YouTube ----

    private val videoNoise = Regex(
        "\\s*[\\(\\[][^\\)\\]]*(official|video|lyric|audio|visuali[sz]er|\\bhd\\b|4k|m/v)[^\\)\\]]*[\\)\\]]",
        RegexOption.IGNORE_CASE,
    )

    suspend fun youtube(link: String, apiKey: String): ParsedPlaylist = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ImportException("Add your YouTube API key under “Keys for link import”.")
        val id = Regex("[?&]list=([A-Za-z0-9_-]+)").find(link)?.groupValues?.get(1)
            ?: throw ImportException("That YouTube link has no playlist (list=…) in it.")
        fun api(path: String, vararg params: Pair<String, String>) = json.parseToJsonElement(
            get(
                Request.Builder().url(
                    "https://www.googleapis.com/youtube/v3/$path".toHttpUrl().newBuilder().apply {
                        params.forEach { (k, v) -> addQueryParameter(k, v) }
                        addQueryParameter("key", apiKey.trim())
                    }.build(),
                ).build(),
            ),
        ).jsonObject
        val name = runCatching {
            api("playlists", "part" to "snippet", "id" to id)["items"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("snippet")?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val tracks = ArrayList<ImportTrack>()
        var page: String? = null
        do {
            val root = if (page == null) api("playlistItems", "part" to "snippet", "maxResults" to "50", "playlistId" to id)
            else api("playlistItems", "part" to "snippet", "maxResults" to "50", "playlistId" to id, "pageToken" to page)
            root["items"]?.jsonArray?.forEach { item ->
                val snippet = (item as? JsonObject)?.get("snippet") as? JsonObject ?: return@forEach
                val title = snippet["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (title.isBlank() || title == "Deleted video" || title == "Private video") return@forEach
                val channel = snippet["videoOwnerChannelTitle"]?.jsonPrimitive?.contentOrNull.orEmpty()
                tracks += youtubeTrack(title, channel)
            }
            page = root["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (page != null && tracks.size < 5_000)
        ParsedPlaylist(name, tracks)
    }

    /** "Artist - Topic" channels are auto-generated music uploads; otherwise guess from "Artist - Title". */
    internal fun youtubeTrack(videoTitle: String, channel: String): ImportTrack {
        val clean = videoTitle.replace(videoNoise, "").trim()
        if (channel.endsWith(" - Topic")) return ImportTrack(clean, channel.removeSuffix(" - Topic").trim())
        val parts = clean.split(" - ", limit = 2)
        return if (parts.size == 2) ImportTrack(parts[1].trim(), parts[0].trim())
        else ImportTrack(clean, channel.removeSuffix("VEVO").trim())
    }
}
