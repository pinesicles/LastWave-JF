package com.lastwave.app.data.jellyfin

import android.content.Context
import android.net.Uri
import com.lastwave.app.data.jellyfin.download.JfTrackEntity
import com.lastwave.app.data.localmusic.LocalContainerLyricsReader
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the best lyrics for a Jellyfin song and hands the player an `.lrc` file path, which
 * LastWave's lyrics screen already treats as "local lyrics" (shown ahead of its online providers).
 *
 * Order, preferring synced (timed) lyrics over plain text at every step:
 *  1. a saved `.lrc` next to the downloaded file, or a cached copy (works offline)
 *  2. lyrics embedded in the audio file (downloaded files only)
 *  3. lyrics from your Jellyfin server (it reads embedded tags and .lrc files)
 *  4. LRCLIB (https://lrclib.net), a free public lyrics database
 * Plain text is only used when no synced version turns up anywhere. Downloads save the result
 * next to the song so it's there offline.
 */
@Singleton
class JellyLyrics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: JellyfinApi,
    private val store: JellyfinSessionStore,
    private val http: OkHttpClient,
) {
    private val misses = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun cacheFile(id: String) = File(File(context.cacheDir, "jf-lyrics"), "$id.lrc")
    private fun sidecar(audio: File) = File(audio.parentFile, audio.nameWithoutExtension + ".lrc")

    /** Path of an `.lrc` file for [track], or null when nothing was found (LastWave's providers take over). */
    suspend fun pathFor(track: PlayableTrack): String? = withContext(Dispatchers.IO) {
        val id = track.jellyfinItemIdOrNull() ?: return@withContext null
        val audio = track.playbackUrl?.takeIf { it.startsWith("file://") }
            ?.let { Uri.parse(it).path }?.let(::File)?.takeIf { it.exists() }
        if (audio != null) sidecar(audio).takeIf { it.length() > 0 }?.let { return@withContext it.path }
        cacheFile(id).takeIf { it.length() > 0 }?.let { return@withContext it.path }
        if (id in misses) return@withContext null
        val text = resolve(id, track.title, track.artist, track.album, track.durationMs, audio)
        if (text.isNullOrBlank()) {
            misses += id
            return@withContext null
        }
        val dest = if (audio != null) sidecar(audio) else cacheFile(id)
        dest.parentFile?.mkdirs()
        dest.writeText(text)
        dest.path
    }

    /** Called after a download finishes so the lyrics are saved next to the song. */
    suspend fun saveSidecar(t: JfTrackEntity, audio: File) = withContext(Dispatchers.IO) {
        val dest = sidecar(audio)
        if (dest.length() > 0) return@withContext
        val text = resolve(t.itemId, t.title, t.artist, t.album, t.durationMs, audio) ?: return@withContext
        dest.writeText(text)
    }

    private suspend fun resolve(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        audio: File?,
    ): String? {
        var plain: String? = null
        if (audio != null) {
            val embedded = runCatching { LocalContainerLyricsReader.extract(context, audio.absolutePath) }.getOrNull()
            if (embedded != null && embedded.text.isNotBlank()) {
                if (embedded.isLrcFormatted) return embedded.text
                plain = embedded.text
            }
        }
        jellyfinLyrics(id)?.let { (text, synced) ->
            if (synced) return text
            if (plain == null) plain = text
        }
        lrclib(title, artist, album, durationMs)?.let { (synced, plainText) ->
            if (!synced.isNullOrBlank()) return synced
            if (plain == null && !plainText.isNullOrBlank()) plain = plainText
        }
        return plain
    }

    private fun stamp(ms: Long): String {
        val total = (ms / 10).coerceAtLeast(0)
        return "[%02d:%02d.%02d]".format(total / 6000, (total / 100) % 60, total % 100)
    }

    /** Lyrics from the Jellyfin server (10.9+). Returns text and whether it is timed. */
    private suspend fun jellyfinLyrics(id: String): Pair<String, Boolean>? {
        val session = store.current() ?: return null
        val body = try {
            api.lyricsJson(session, id)
        } catch (e: JellyfinException) {
            return null
        }
        val lines: JsonArray = try {
            JellyfinJson.parseToJsonElement(body).jsonObject["Lyrics"]?.jsonArray
        } catch (e: Exception) {
            null
        } ?: return null
        var synced = false
        val sb = StringBuilder()
        lines.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val text = o["Text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val start = o["Start"]?.jsonPrimitive?.longOrNull
            if (start != null) {
                synced = true
                sb.append(stamp(start / 10_000))
            }
            sb.append(text).append('\n')
        }
        val text = sb.toString().trim()
        return if (text.isBlank()) null else text to synced
    }

    private fun getJson(url: okhttp3.HttpUrl): kotlinx.serialization.json.JsonElement? = try {
        val request = Request.Builder().url(url).header("User-Agent", "JellyWave/0.1 (personal Jellyfin client)").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) null else JellyfinJson.parseToJsonElement(r.body?.string().orEmpty())
        }
    } catch (e: IOException) {
        null
    } catch (e: Exception) {
        null
    }

    /** Returns (synced, plain) from LRCLIB, or null when it has nothing or is unreachable. */
    private fun lrclib(title: String, artist: String, album: String?, durationMs: Long?): Pair<String?, String?>? {
        fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val get = "https://lrclib.net/api/get".toHttpUrl().newBuilder().apply {
            addQueryParameter("track_name", title)
            addQueryParameter("artist_name", artist.substringBefore(',').trim())
            if (!album.isNullOrBlank()) addQueryParameter("album_name", album)
            if (durationMs != null && durationMs > 0) addQueryParameter("duration", (durationMs / 1000).toString())
        }.build()
        (getJson(get) as? JsonObject)?.let { o ->
            val synced = o.str("syncedLyrics")
            val plain = o.str("plainLyrics")
            if (synced != null || plain != null) return synced to plain
        }
        val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("track_name", title)
            addQueryParameter("artist_name", artist.substringBefore(',').trim())
        }.build()
        val results = (getJson(search) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val same = results.filter { it.str("trackName")?.trim().equals(title.trim(), ignoreCase = true) }
        val best = same.firstOrNull { it.str("syncedLyrics") != null } ?: same.firstOrNull() ?: return null
        return best.str("syncedLyrics") to best.str("plainLyrics")
    }
}
