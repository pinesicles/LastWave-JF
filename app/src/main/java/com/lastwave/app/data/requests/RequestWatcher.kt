package com.lastwave.app.data.requests

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lastwave.app.data.jellyfin.JellyfinRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** The playlist page currently open (id to name), so a request made there can add the album to it later. */
object RequestContext {
    @Volatile var playlist: Pair<String, String>? = null
}

/**
 * Follows requested albums until they show up in Jellyfin. Once DroppedNeedle reports an album as
 * imported it asks Jellyfin to rescan, waits for the album to appear, and adds it to the playlist it
 * was requested from (if any). Watches are saved, so they resume the next time the app opens.
 */
@Singleton
class RequestWatcher @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dn: DroppedNeedleClient,
    private val jelly: JellyfinRepository,
) {
    private data class Watch(
        val mbid: String, val title: String, val artist: String,
        val playlistId: String?, val playlistName: String?,
        val since: Long, val imported: Boolean,
    )

    private val prefs = ctx.getSharedPreferences("jw_request_watch", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val lock = Any()

    fun watch(album: DnAlbum) {
        val target = RequestContext.playlist
        synchronized(lock) {
            val list = load().filter { it.mbid != album.mbid }.toMutableList()
            list += Watch(album.mbid, album.title, album.artist, target?.first, target?.second, System.currentTimeMillis(), false)
            save(list)
        }
        start()
    }

    fun start() {
        synchronized(lock) {
            if (job?.isActive == true || load().isEmpty()) return
            job = scope.launch {
                while (true) {
                    if (synchronized(lock) { load() }.isEmpty()) break
                    try { tick() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    delay(POLL_MS)
                }
            }
        }
    }

    private suspend fun tick() {
        val reqs = dn.myRequests()
        var rescanned = false
        val keep = mutableListOf<Watch>()
        val now = System.currentTimeMillis()
        for (w in synchronized(lock) { load() }) {
            val status = reqs.firstOrNull { it.mbid == w.mbid }?.status
            if (status in DEAD || now - w.since > MAX_AGE_MS) continue
            var cur = w
            if (status == "imported") cur = w.copy(imported = true)
            if (!cur.imported) { keep += cur; continue }
            if (!rescanned) { rescanned = true; runCatching { jelly.refreshLibrary() } }
            val found = runCatching { jelly.findAlbum(cur.title, cur.artist) }.getOrNull()
            if (found == null) { keep += cur; continue }
            if (cur.playlistId != null) {
                val ok = runCatching { jelly.addToPlaylist(cur.playlistId, jelly.albumTrackIds(found.id)) }.isSuccess
                if (ok) toast("Added ${cur.title} to ${cur.playlistName ?: "your playlist"}") else keep += cur
            } else {
                toast("${cur.title} is now in your library")
            }
        }
        synchronized(lock) {
            // Keep watches added while this pass was running.
            val fresh = load().filter { n -> keep.none { it.mbid == n.mbid } && n.since > System.currentTimeMillis() - POLL_MS }
            save(keep + fresh)
        }
    }

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }

    private fun load(): List<Watch> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let {
                Watch(
                    it.getString("mbid"), it.optString("title"), it.optString("artist"),
                    if (it.isNull("pl")) null else it.getString("pl"),
                    if (it.isNull("pn")) null else it.getString("pn"),
                    it.optLong("since"), it.optBoolean("imp"),
                )
            }
        }
    } catch (_: Exception) { emptyList() }

    private fun save(list: List<Watch>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("mbid", it.mbid).put("title", it.title).put("artist", it.artist)
                    .put("pl", it.playlistId ?: JSONObject.NULL).put("pn", it.playlistName ?: JSONObject.NULL)
                    .put("since", it.since).put("imp", it.imported),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "watches"
        const val POLL_MS = 45_000L
        const val MAX_AGE_MS = 3L * 24 * 3600 * 1000
        val DEAD = setOf("failed", "rejected", "cancelled", "cancelling")
    }
}
