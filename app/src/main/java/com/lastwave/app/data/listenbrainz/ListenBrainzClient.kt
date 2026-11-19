package com.lastwave.app.data.listenbrainz

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Sends your plays to ListenBrainz (MusicBrainz's scrobbling service): a "now playing" ping when a song
 * starts and a listen once it counts as played. Listens that can't be sent (offline, weak Wi-Fi, server down)
 * wait in a small saved queue and go out with the next successful send.
 * Needs your user token from https://listenbrainz.org/settings/.
 */
@Singleton
class ListenBrainzClient @Inject constructor(
    @ApplicationContext context: Context,
    private val http: OkHttpClient,
) {
    private val prefs = context.getSharedPreferences("jw_listenbrainz", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val queueLock = Any()

    private val _user = MutableStateFlow(prefs.getString(KEY_USER, null))
    /** The ListenBrainz username while connected, else null. */
    val user: StateFlow<String?> = _user

    @Volatile private var lastNowPlayingKey: String? = null
    @Volatile private var lastListenKey: String? = null

    private fun token(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Checks the token with ListenBrainz and saves it. Returns an error message, or null on success. */
    suspend fun connect(rawToken: String): String? = withContext(Dispatchers.IO) {
        val token = rawToken.trim()
        if (token.isBlank()) return@withContext "Paste your user token"
        try {
            val request = Request.Builder().url("$API/validate-token").header("Authorization", "Token $token").get().build()
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) return@withContext "ListenBrainz answered ${r.code}"
                val json = JSONObject(text)
                if (!json.optBoolean("valid")) return@withContext "That token isn't valid"
                val name = json.optString("user_name").ifBlank { "connected" }
                prefs.edit().putString(KEY_TOKEN, token).putString(KEY_USER, name).apply()
                _user.value = name
                scope.launch { flush() }
                null
            }
        } catch (e: IOException) {
            "Couldn't reach ListenBrainz"
        }
    }

    fun disconnect() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).remove(KEY_QUEUE).apply()
        _user.value = null
    }

    /** A song just started. Fire and forget. */
    fun nowPlaying(artist: String, track: String, album: String?) {
        val token = token() ?: return
        val key = "${artist.lowercase()}|${track.lowercase()}"
        if (key == lastNowPlayingKey) return
        lastNowPlayingKey = key
        scope.launch {
            val payload = JSONObject().put("track_metadata", metadata(artist, track, album))
            val body = JSONObject().put("listen_type", "playing_now").put("payload", JSONArray().put(payload))
            runCatching { post(token, body) }
        }
    }

    /** A song counted as played. Queued first, so it survives being offline. */
    fun scrobble(artist: String, track: String, album: String?, timestampSec: Long) {
        if (token() == null) return
        val key = "$timestampSec|${artist.lowercase()}|${track.lowercase()}"
        if (key == lastListenKey) return // the caller retries on failure; never send the same listen twice
        lastListenKey = key
        synchronized(queueLock) {
            val queue = readQueue()
            queue.put(
                JSONObject().put("listened_at", timestampSec).put("track_metadata", metadata(artist, track, album)),
            )
            writeQueue(queue)
        }
        scope.launch { flush() }
    }

    private fun metadata(artist: String, track: String, album: String?): JSONObject {
        val info = JSONObject().put("media_player", "JellyWave").put("submission_client", "JellyWave")
        val meta = JSONObject().put("artist_name", artist).put("track_name", track).put("additional_info", info)
        if (!album.isNullOrBlank()) meta.put("release_name", album)
        return meta
    }

    private fun readQueue(): JSONArray = runCatching { JSONArray(prefs.getString(KEY_QUEUE, "[]")) }.getOrDefault(JSONArray())

    private fun writeQueue(queue: JSONArray) {
        var q = queue
        if (q.length() > MAX_QUEUE) {
            val trimmed = JSONArray()
            for (i in q.length() - MAX_QUEUE until q.length()) trimmed.put(q.get(i))
            q = trimmed
        }
        prefs.edit().putString(KEY_QUEUE, q.toString()).apply()
    }

    private suspend fun flush() {
        sendMutex.withLock {
        val token = token() ?: return
        while (true) {
            val batch = synchronized(queueLock) {
                val q = readQueue()
                val take = JSONArray()
                for (i in 0 until minOf(q.length(), BATCH)) take.put(q.get(i))
                take
            }
            if (batch.length() == 0) return
            val body = JSONObject()
                .put("listen_type", if (batch.length() == 1) "single" else "import")
                .put("payload", batch)
            val code = try { post(token, body) } catch (e: IOException) { return }
            // 2xx: sent. 400-class (other than rate limit / auth) means ListenBrainz will never accept it: drop it.
            val done = code in 200..299 || (code in 400..499 && code != 401 && code != 429)
            if (!done) return
            synchronized(queueLock) {
                val q = readQueue()
                val rest = JSONArray()
                for (i in batch.length() until q.length()) rest.put(q.get(i))
                writeQueue(rest)
            }
        }
        }
    }

    /** Loves (or un-loves) a song on ListenBrainz. Finds its recording id from artist and title. Returns true when sent. */
    suspend fun setLove(artist: String, title: String, loved: Boolean): Boolean = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext false
        try {
            val lookup = Request.Builder()
                .url("$API/metadata/lookup/".toHttpUrl().newBuilder()
                    .addQueryParameter("artist_name", artist).addQueryParameter("recording_name", title).build())
                .get().build()
            val mbid = http.newCall(lookup).execute().use { r ->
                if (r.code != 200) return@withContext false
                JSONObject(r.body?.string().orEmpty()).optString("recording_mbid")
            }
            if (mbid.isBlank()) return@withContext false
            val body = JSONObject().put("recording_mbid", mbid).put("score", if (loved) 1 else 0).toString()
            val send = Request.Builder().url("$API/feedback/recording-feedback").header("Authorization", "Token $t")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            http.newCall(send).execute().use { it.code == 200 }
        } catch (e: IOException) {
            false
        }
    }

    /** Every song you've loved on ListenBrainz as (artist, title). Null when not connected or it can't be read. */
    suspend fun lovedTracks(): List<Pair<String, String>>? = withContext(Dispatchers.IO) {
        val user = _user.value ?: return@withContext null
        val auth = token()?.let { "Token $it" }
        val out = mutableListOf<Pair<String, String>>()
        try {
            var offset = 0
            while (offset < 5000) {
                val url = "$API/feedback/user/$user/get-feedback".toHttpUrl().newBuilder()
                    .addQueryParameter("score", "1").addQueryParameter("count", "100")
                    .addQueryParameter("offset", offset.toString()).addQueryParameter("metadata", "true").build()
                val page = http.newCall(Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }.get().build()).execute().use { r ->
                    if (r.code != 200) return@withContext null
                    JSONObject(r.body?.string().orEmpty()).optJSONArray("feedback") ?: JSONArray()
                }
                for (i in 0 until page.length()) {
                    val m = page.getJSONObject(i).optJSONObject("track_metadata") ?: continue
                    val a = m.optString("artist_name"); val t = m.optString("track_name")
                    if (a.isNotBlank() && t.isNotBlank()) out += a to t
                }
                if (page.length() < 100) break
                offset += 100
            }
            out
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** A song ListenBrainz suggests for you, with a cover from the Cover Art Archive when it knows the release. */
    data class Rec(val title: String, val artist: String, val album: String?, val coverUrl: String?)

    /** Your personal recording recommendations. Empty until ListenBrainz has enough of your listens to compute them. */
    suspend fun recommendations(count: Int = 24): List<Rec> = withContext(Dispatchers.IO) {
        val user = _user.value ?: return@withContext emptyList()
        try {
            val auth = token()?.let { "Token $it" }
            fun req(url: String) = Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }
            val ids = http.newCall(req("$API/cf/recommendation/user/$user/recording?count=$count").get().build()).execute().use { r ->
                if (r.code != 200) return@withContext emptyList()
                val arr = JSONObject(r.body?.string().orEmpty()).optJSONObject("payload")?.optJSONArray("mbids") ?: return@withContext emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it).optString("recording_mbid") }.filter { it.isNotBlank() }
            }
            if (ids.isEmpty()) return@withContext emptyList()
            val body = JSONObject().put("recording_mbids", JSONArray(ids)).put("inc", "artist release").toString()
            val meta = http.newCall(req("$API/metadata/recording/").post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (r.code != 200) return@withContext emptyList()
                JSONObject(r.body?.string().orEmpty())
            }
            ids.mapNotNull { id ->
                val m = meta.optJSONObject(id) ?: return@mapNotNull null
                val title = m.optJSONObject("recording")?.optString("name").orEmpty()
                val artist = m.optJSONObject("artist")?.optString("name").orEmpty()
                if (title.isBlank() || artist.isBlank()) return@mapNotNull null
                val rel = m.optJSONObject("release")
                val caa = rel?.optString("caa_release_mbid").orEmpty().ifBlank { rel?.optString("mbid").orEmpty() }
                Rec(title, artist, rel?.optString("name")?.takeIf { it.isNotBlank() },
                    if (caa.isBlank()) null else "https://coverartarchive.org/release/$caa/front-250")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun post(token: String, body: JSONObject): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$API/submit-listens").header("Authorization", "Token $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { it.code }
    }

    private companion object {
        const val API = "https://api.listenbrainz.org/1"
        const val KEY_TOKEN = "token"
        const val KEY_USER = "user"
        const val KEY_QUEUE = "queue"
        const val MAX_QUEUE = 500
        const val BATCH = 50
    }
}
