package com.lastwave.app.data.requests

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lastwave.app.data.jellyfin.JellyfinRepository
import com.lastwave.app.data.jellyfin.importer.ImportTrack
import com.lastwave.app.data.jellyfin.jellyfinItemIdOrNull
import com.lastwave.app.data.likes.likeKey
import com.lastwave.app.data.playlist.SpotifyPlaylistImporter
import com.lastwave.app.ui.library.ImportLeftovers
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A saved playlist sync. A Jellyfin playlist is the hub; it can be linked to a playlist on the request server
 * (DroppedNeedle) in either or both directions, and to a Spotify playlist it reads from. With several links this
 * is an "all way" sync: everything flows into the Jellyfin playlist and back out to the writable side.
 *
 * [dnDir] is OFF, TO (Jellyfin to server), FROM (server to Jellyfin) or BOTH. [spDir] is OFF or FROM (Spotify is read-only).
 * [removals]: a song removed at a source since the last run is removed from Jellyfin too (the request server has no
 * remove call, so removals only ever land on Jellyfin).
 */
data class PlaylistLink(
    val id: String,
    val jfId: String,
    val jfName: String,
    val dnDir: String = "TO",
    val spId: String? = null,
    val spName: String? = null,
    val spDir: String = "OFF",
    val removals: Boolean = false,
    val last: String? = null,
)

@Singleton
class PlaylistLinks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jelly: JellyfinRepository,
    private val dn: DroppedNeedleClient,
    private val spotify: SpotifyPlaylistImporter,
) {
    private val prefs = context.getSharedPreferences("jw_plink", Context.MODE_PRIVATE)
    private val _links = MutableStateFlow(load())
    val links: StateFlow<List<PlaylistLink>> = _links
    val status = MutableStateFlow<String?>(null)

    private fun load(): List<PlaylistLink> = runCatching {
        val a = JSONArray(prefs.getString("links", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            PlaylistLink(
                o.getString("id"), o.getString("jf"), o.optString("jfn"), o.optString("dn", "TO"),
                o.optString("sp").ifBlank { null }, o.optString("spn").ifBlank { null }, o.optString("spd", "OFF"),
                o.optBoolean("rm"), o.optString("last").ifBlank { null },
            )
        }
    }.getOrDefault(emptyList())

    private fun persist(list: List<PlaylistLink>) {
        _links.value = list
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("jf", it.jfId).put("jfn", it.jfName).put("dn", it.dnDir)
                .put("sp", it.spId ?: "").put("spn", it.spName ?: "").put("spd", it.spDir).put("rm", it.removals).put("last", it.last ?: ""))
        }
        prefs.edit().putString("links", a.toString()).apply()
    }

    fun save(l: PlaylistLink) = persist(_links.value.filterNot { it.id == l.id } + l)
    fun delete(id: String) {
        persist(_links.value.filterNot { it.id == id })
        prefs.edit().remove("s_${id}_jf").remove("s_${id}_dn").remove("s_${id}_sp").apply()
    }

    var auto: Boolean
        get() = prefs.getBoolean("auto", false)
        set(v) {
            prefs.edit().putBoolean("auto", v).apply()
            val wm = WorkManager.getInstance(context)
            if (v) wm.enqueueUniquePeriodicWork(
                "plink_auto", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PlaylistLinkWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            ) else wm.cancelUniqueWork("plink_auto")
        }

    fun syncInBackground() {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<PlaylistLinkWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    private fun snap(id: String, node: String): Set<String>? =
        prefs.getString("s_${id}_$node", null)?.let { s -> runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull() }

    private fun putSnap(id: String, node: String, keys: Set<String>) =
        prefs.edit().putString("s_${id}_$node", JSONArray(keys.toList()).toString()).apply()

    suspend fun runAll(): String {
        val out = _links.value.map { l ->
            status.value = "Syncing ${l.jfName}…"
            val r = try { run(l) } catch (e: CancellationException) { throw e } catch (e: Exception) { "failed: ${e.message}" }
            save(l.copy(last = r))
            "${l.jfName}: $r"
        }
        status.value = null
        return if (out.isEmpty()) "No syncs set up" else out.joinToString("\n")
    }

    suspend fun run(l: PlaylistLink): String {
        val entries = jelly.playlistEntries(l.jfId)
        val keyOf = { t: ImportTrack -> likeKey(t.title, t.artist) }
        var jf = entries.associateBy { likeKey(it.track.title, it.track.artist) }.toMutableMap()
        val prevJf = snap(l.id, "jf")

        // What each source has now.
        val dnTracks = LinkedHashMap<String, ImportTrack>()
        var dnId: String? = null
        if (l.dnDir != "OFF") {
            val existing = dn.playlists().firstOrNull { it.name.trim().equals(l.jfName.trim(), true) }
            dnId = existing?.id ?: if (l.dnDir == "TO" || l.dnDir == "BOTH") dn.createPlaylist(l.jfName, "jellyfin:${l.jfId}") else null
            if (existing != null) dn.playlistSongs(existing.id).forEach { (t, a) -> dnTracks.putIfAbsent(likeKey(t, a), ImportTrack(t, a)) }
        }
        val spTracks = LinkedHashMap<String, ImportTrack>()
        if (l.spDir == "FROM" && l.spId != null) {
            spotify.fetchPlaylist("https://open.spotify.com/playlist/${l.spId}").rows
                .forEach { spTracks.putIfAbsent(likeKey(it.title, it.artist), ImportTrack(it.title, it.artist, it.album)) }
        }
        val fromDn = l.dnDir == "FROM" || l.dnDir == "BOTH"

        // Songs the user took out of Jellyfin themselves shouldn't be re-added from the other side.
        val localRemoved: Set<String> = if (l.removals && prevJf != null) prevJf - jf.keys else emptySet()

        // Removals at a source since last run.
        var removed = 0
        if (l.removals) {
            val gone = HashSet<String>()
            if (fromDn) snap(l.id, "dn")?.let { gone += it - dnTracks.keys }
            if (l.spDir == "FROM") snap(l.id, "sp")?.let { gone += it - spTracks.keys }
            for (k in gone) {
                val e = jf[k] ?: continue
                val eid = e.entryId ?: continue
                runCatching { jelly.removeFromPlaylist(l.jfId, eid) }.onSuccess { jf.remove(k); removed++ }
            }
        }
        // Incoming songs.
        val incoming = LinkedHashMap<String, ImportTrack>()
        if (fromDn) dnTracks.forEach { (k, t) -> incoming[k] = t }
        spTracks.forEach { (k, t) -> incoming.putIfAbsent(k, t) }
        val toAdd = incoming.filter { (k, _) -> k !in jf && k !in localRemoved }
        val addIds = mutableListOf<String>()
        val addedKeys = mutableListOf<String>()
        val unmatched = mutableListOf<ImportTrack>()
        toAdd.forEach { (k, t) ->
            val hit = runCatching { jelly.findSong(t.title, t.artist) }.getOrNull()
            val id = hit?.jellyfinItemIdOrNull()
            if (id != null && id !in addIds) { addIds += id; addedKeys += k } else if (id == null) unmatched += t
        }
        if (addIds.isNotEmpty()) jelly.addToPlaylist(l.jfId, addIds)
        if (unmatched.isNotEmpty()) {
            val keep = ImportLeftovers.load(context, l.jfId)
            ImportLeftovers.save(context, l.jfId, (keep + unmatched).distinctBy { likeKey(it.title, it.artist) })
        }
        val jfKeys = jf.keys + addedKeys

        // Outgoing to the request server.
        var pushed = 0
        val dnKeys = HashSet(dnTracks.keys)
        if (dnId != null && (l.dnDir == "TO" || l.dnDir == "BOTH")) {
            val send = jf.filter { (k, _) -> k !in dnKeys }.map { (k, e) -> k to ImportTrack(e.track.title, e.track.artist, e.track.album) }
            if (send.isNotEmpty()) {
                dn.addPlaylistSongs(dnId, send.map { it.second })
                dnKeys += send.map { it.first }
                pushed = send.size
            }
        }
        putSnap(l.id, "jf", jfKeys.toSet()); putSnap(l.id, "dn", dnKeys); putSnap(l.id, "sp", spTracks.keys)
        return "+${addIds.size} here, −$removed here, +$pushed on server" +
            if (unmatched.isNotEmpty()) ", ${unmatched.size} not in library" else ""
    }

}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaylistLinksEntryPoint { fun links(): PlaylistLinks }

class PlaylistLinkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        EntryPointAccessors.fromApplication(applicationContext, PlaylistLinksEntryPoint::class.java).links().runAll()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
