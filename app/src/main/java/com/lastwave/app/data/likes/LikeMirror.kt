package com.lastwave.app.data.likes

import android.content.Context
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.jellyfin.JellyFavourites
import com.lastwave.app.data.jellyfin.JellyfinRepository
import com.lastwave.app.data.jellyfin.importer.ImportMatcher
import com.lastwave.app.data.jellyfin.importer.ImportTrack
import com.lastwave.app.data.jellyfin.jellyfinItemIdOrNull
import com.lastwave.app.data.listenbrainz.ListenBrainzClient
import com.lastwave.app.data.network.DataMeter
import com.lastwave.app.data.playlist.LikedSongsManager
import com.lastwave.app.data.playlist.PlaylistRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Same song on different services: the title without "(Remastered)" or "- Live" style tails, plus the first credited
 * artist (Spotify lists every artist, others only one).
 */
fun likeKey(title: String, artist: String): String {
    val first = artist.split(Regex("\\s*(,|;|&|\\band\\b|feat\\.?|ft\\.?|\\bx\\b|/)\\s*", RegexOption.IGNORE_CASE)).firstOrNull().orEmpty()
    return ImportMatcher.baseTitle(title) + "|" + ImportMatcher.norm(first)
}

/** Which way to sync: [source] null means two-way merge. One way copies [source] to [targets], and with [mirror] also removes from them what the source lacks. */
class SyncMode(val source: LikeNode?, val targets: Set<LikeNode> = emptySet(), val mirror: Boolean = false) {
    /** Text form for WorkManager input, so a background run knows what to do after a restart. */
    fun encode(): String = if (source == null) "merge" else "oneway:${source.name}:${targets.joinToString(",") { it.name }}:$mirror"

    companion object {
        val MERGE = SyncMode(null)
        fun decode(s: String?): SyncMode = try {
            val p = s.orEmpty().split(":")
            if (p.firstOrNull() == "oneway") {
                SyncMode(LikeNode.valueOf(p[1]), p[2].split(",").filter { it.isNotBlank() }.map { LikeNode.valueOf(it) }.toSet(), p[3].toBoolean())
            } else MERGE
        } catch (_: Exception) { MERGE }
    }
}

enum class LikeNode(val label: String) {
    JELLYFIN("Jellyfin"), SPOTIFY("Spotify"), LISTENBRAINZ("ListenBrainz"), YOUTUBE("YouTube / app likes"),
}

/** A song a service offered as a possible match for a failed sync. [id] is that service's own id. */
class MatchCandidate(val id: String, val title: String, val artist: String, val album: String?)

/** A song that couldn't be added to [node] during a sync. */
class SyncFailure(val track: ImportTrack, val node: LikeNode) {
    val key: String get() = likeKey(track.title, track.artist) + "|" + node.name
}

/** What a sync would do, shown before anything is changed. */
class LikePlan internal constructor(
    val adds: Map<LikeNode, List<ImportTrack>>,
    val removes: Map<LikeNode, List<ImportTrack>>,
    val available: Set<LikeNode>,
    internal val present: Map<LikeNode, Map<String, ImportTrack>>,
    /** For each song key, the services it was found on (where an addition comes from). */
    internal val sources: Map<String, Set<LikeNode>>,
    internal val raw: Map<LikeNode, Int> = emptyMap(),
    val mode: SyncMode = SyncMode.MERGE,
) {
    /** Likes the service held when the sync read it, counting a song once. */
    fun count(n: LikeNode): Int = present[n]?.size ?: 0

    /** Likes the service returned in all, before the same song listed twice is counted once. */
    fun rawCount(n: LikeNode): Int = raw[n] ?: count(n)

    /** The songs this plan adds to [n], each with the services they come from. */
    fun addsWithSources(n: LikeNode): List<Pair<ImportTrack, List<LikeNode>>> =
        adds[n].orEmpty().map { t -> t to sources[likeKey(t.title, t.artist)].orEmpty().toList() }

    val isEmpty: Boolean get() = adds.values.all { it.isEmpty() } && removes.values.all { it.isEmpty() }
}

/**
 * Keeps likes in step across Jellyfin favourites, Spotify Liked Songs, ListenBrainz loves and the app's own likes.
 * It remembers what each service held after the last sync, so it can tell an addition from a removal. Additions
 * always spread to the others; removals only when "sync removals" is on. Songs Jellyfin doesn't have go to the
 * "not on your server" list so they can be requested.
 */
@Singleton
class LikeMirror @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val listenBrainz: ListenBrainzClient,
    private val spotify: SpotifyLibrary,
    private val jelly: JellyfinRepository,
    private val jellyFavourites: JellyFavourites,
    private val playlists: PlaylistRepository,
    private val liked: LikedSongsManager,
) {
    private val prefs = appContext.getSharedPreferences("jw_likemirror", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var soon: Job? = null

    /** Set by [LikeSync]: songs that couldn't be favourited because Jellyfin doesn't have them. */
    @Volatile var onNotOnServer: (List<ImportTrack>) -> Unit = {}

    private val _removals = MutableStateFlow(prefs.getBoolean("removals", false))
    val syncRemovals: StateFlow<Boolean> = _removals.asStateFlow()
    private val _auto = MutableStateFlow(prefs.getBoolean("auto", true))
    val autoSync: StateFlow<Boolean> = _auto.asStateFlow()
    private fun loadFailures(): List<SyncFailure> = try {
        val arr = org.json.JSONArray(prefs.getString("failures", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            SyncFailure(ImportTrack(it.getString("t"), it.getString("a"), it.optString("al").ifBlank { null }), LikeNode.valueOf(it.getString("n")))
        }
    } catch (_: Exception) { emptyList() }

    private val _failures = MutableStateFlow(loadFailures())
    /** Songs the last syncs couldn't place, waiting for you to pick a match, request them or ignore them. */
    val failures: StateFlow<List<SyncFailure>> = _failures.asStateFlow()
    private fun ignored(): Set<String> = prefs.getStringSet("ignored", null) ?: emptySet()

    private fun setFailures(list: List<SyncFailure>) {
        _failures.value = list
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("t", it.track.title).put("a", it.track.artist).put("al", it.track.album.orEmpty()).put("n", it.node.name)) }
        prefs.edit().putString("failures", arr.toString()).apply()
    }

    fun ignore(f: SyncFailure) {
        prefs.edit().putStringSet("ignored", HashSet(ignored() + f.key)).apply()
        setFailures(_failures.value.filterNot { it.key == f.key })
    }

    suspend fun candidates(node: LikeNode, query: String): List<MatchCandidate> = try {
        when (node) {
            LikeNode.JELLYFIN -> jelly.searchSongs(query, 20).mapNotNull { t ->
                t.jellyfinItemIdOrNull()?.let { MatchCandidate(it, t.title, t.artist, t.album) }
            }
            LikeNode.SPOTIFY -> spotify.candidates(query)
            else -> emptyList()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** You picked [c] as the right match for [f]: like it there and clear the failure. */
    /** Searches Jellyfin again for every song that failed to match there, likes what it finds. Returns how many. */
    suspend fun researchAll(): Int {
        var found = 0
        for (f in _failures.value.filter { it.node == LikeNode.JELLYFIN }) {
            val id = try { jelly.findSong(f.track.title, f.track.artist)?.jellyfinItemIdOrNull() } catch (e: CancellationException) { throw e } catch (e: Exception) { null } ?: continue
            val ok = try { jelly.setFavorite(id, true); true } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (ok) {
                val k = likeKey(f.track.title, f.track.artist)
                prefs.edit().putStringSet("snap2_${f.node.name}", HashSet(snap(f.node) + k)).apply()
                setFailures(_failures.value.filterNot { it.key == f.key })
                found++
            }
        }
        if (found > 0) runCatching { jellyFavourites.refresh() }
        return found
    }

    suspend fun chooseMatch(f: SyncFailure, c: MatchCandidate): Boolean {
        val ok = try {
            when (f.node) {
                LikeNode.JELLYFIN -> { jelly.setFavorite(c.id, true); true }
                LikeNode.SPOTIFY -> spotify.likeId(c.id, true)
                else -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) {
            val k = likeKey(f.track.title, f.track.artist)
            prefs.edit().putStringSet("snap2_${f.node.name}", HashSet(snap(f.node) + k)).apply()
            setFailures(_failures.value.filterNot { it.key == f.key })
            if (f.node == LikeNode.JELLYFIN) runCatching { jellyFavourites.refresh() }
        }
        return ok
    }

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val firstSyncDone: Boolean get() = prefs.getBoolean("done", false)
    fun setSyncRemovals(on: Boolean) { _removals.value = on; prefs.edit().putBoolean("removals", on).apply() }
    fun setAutoSync(on: Boolean) { _auto.value = on; prefs.edit().putBoolean("auto", on).apply() }

    private fun key(t: ImportTrack) = likeKey(t.title, t.artist)
    private fun snap(n: LikeNode): Set<String> = prefs.getStringSet("snap2_${n.name}", null) ?: emptySet()
    private fun hasSnap(n: LikeNode) = prefs.contains("snap2_${n.name}")

    private suspend fun read(n: LikeNode): List<ImportTrack>? = try {
        when (n) {
            LikeNode.JELLYFIN -> jelly.favoriteTracks().map { ImportTrack(it.title, it.artist, it.album) }
            LikeNode.SPOTIFY -> spotify.liked()
            LikeNode.LISTENBRAINZ -> listenBrainz.lovedTracks()?.map { ImportTrack(it.second, it.first) }
            LikeNode.YOUTUBE -> playlists.getLikedSongs()?.tracks.orEmpty().map { ImportTrack(it.name, it.artist, it.album) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Reads every connected service and works out what to add and remove. Changes nothing. */
    suspend fun plan(mode: SyncMode = SyncMode.MERGE): LikePlan {
        _status.value = "Reading your likes…"
        val present = LinkedHashMap<LikeNode, Map<String, ImportTrack>>()
        val raw = HashMap<LikeNode, Int>()
        val nodes = if (mode.source == null) LikeNode.values().toList() else listOf(mode.source) + mode.targets.filter { it != mode.source }
        for (n in nodes) {
            val list = read(n) ?: continue
            raw[n] = list.size
            present[n] = list.associateBy { key(it) }
        }
        if (mode.source != null) {
            val src = present[mode.source]
            val adds1 = LikeNode.values().associateWith { mutableListOf<ImportTrack>() }
            val removes1 = LikeNode.values().associateWith { mutableListOf<ImportTrack>() }
            val sources1 = HashMap<String, Set<LikeNode>>()
            if (src != null) {
                val skip = ignored()
                for ((n, set) in present) {
                    if (n == mode.source) continue
                    for ((k, t) in src) if (k !in set && (k + "|" + n.name) !in skip) { adds1.getValue(n) += t; sources1[k] = setOf(mode.source) }
                    if (mode.mirror) for ((k, t) in set) if (k !in src) removes1.getValue(n) += t
                }
            }
            _status.value = if (src == null) "Couldn't read ${mode.source.label}" else null
            return LikePlan(adds1, removes1, if (src == null) emptySet() else present.keys, present, sources1, raw, mode)
        }
        val removals = _removals.value && firstSyncDone
        val addedSomewhere = LinkedHashMap<String, ImportTrack>()
        val sources = HashMap<String, MutableSet<LikeNode>>()
        val removedSomewhere = HashSet<String>()
        for ((n, set) in present) {
            val old = snap(n)
            val first = !hasSnap(n)
            set.forEach { (k, t) ->
                if (first || k !in old) { addedSomewhere.putIfAbsent(k, t); sources.getOrPut(k) { LinkedHashSet() } += n }
            }
            if (!first) old.forEach { k -> if (k !in set) removedSomewhere += k }
        }
        val adds = LikeNode.values().associateWith { mutableListOf<ImportTrack>() }
        val removes = LikeNode.values().associateWith { mutableListOf<ImportTrack>() }
        val skip = ignored()
        for ((k, t) in addedSomewhere) for ((n, set) in present) {
            if (k !in set && (k + "|" + n.name) !in skip) adds.getValue(n) += t
        }
        if (removals) for (k in removedSomewhere) {
            if (k in addedSomewhere) continue
            for ((n, set) in present) set[k]?.let { removes.getValue(n) += it }
        }
        _status.value = null
        return LikePlan(adds, removes, present.keys, present, sources, raw, mode)
    }

    /** Carries out [plan]. */
    suspend fun apply(plan: LikePlan, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }) = mutex.withLock {
        _busy.value = true
        try {
            val notOnServer = mutableListOf<ImportTrack>()
            val done = HashMap<LikeNode, MutableSet<String>>()
            for (n in plan.available) done[n] = plan.present.getValue(n).keys.toMutableSet()
            val total = plan.adds.values.sumOf { it.size } + plan.removes.values.sumOf { it.size }
            var step = 0
            val failed = _failures.value.associateBy { it.key }.toMutableMap()
            for ((n, list) in plan.adds) for (t in list) {
                _status.value = "Syncing ${++step} of $total…"; onProgress(step, total)
                val f = SyncFailure(t, n)
                if (write(n, t, true)) { done[n]?.add(key(t)); failed.remove(f.key) }
                else { failed[f.key] = f; if (n == LikeNode.JELLYFIN) notOnServer += t }
            }
            setFailures(failed.values.toList())
            for ((n, list) in plan.removes) for (t in list) {
                _status.value = "Syncing ${++step} of $total…"; onProgress(step, total)
                if (write(n, t, false)) done[n]?.remove(key(t))
            }
            val e = prefs.edit()
            done.forEach { (n, keys) -> e.putStringSet("snap2_${n.name}", HashSet(keys)) }
            e.putBoolean("done", true).putLong("last", System.currentTimeMillis()).apply()
            if (notOnServer.isNotEmpty()) onNotOnServer(notOnServer)
            runCatching { jellyFavourites.refresh() }
            val bad = _failures.value.size
            _status.value = "Synced ${step - bad.coerceAtMost(step)} changes" + if (bad > 0) ", $bad need a look" else ""
        } finally {
            _busy.value = false
        }
    }

    private suspend fun write(n: LikeNode, t: ImportTrack, on: Boolean): Boolean {
        delay(150)
        return try {
            when (n) {
                LikeNode.JELLYFIN -> {
                    val id = jelly.findSong(t.title, t.artist)?.jellyfinItemIdOrNull() ?: return false
                    jelly.setFavorite(id, on)
                    true
                }
                LikeNode.SPOTIFY -> spotify.setLiked(t.title, t.artist, on)
                LikeNode.LISTENBRAINZ -> listenBrainz.setLove(t.artist, t.title, on)
                LikeNode.YOUTUBE -> {
                    liked.setLiked(GeneratedTrack(name = t.title, artist = t.artist, album = t.album), on)
                    true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** A background sync, at most every few hours, once you've done the first one by hand. */
    fun autoSyncIfDue() {
        if (!_auto.value || !firstSyncDone || DataMeter.savingNow()) return
        if (System.currentTimeMillis() - prefs.getLong("last", 0) < 6 * 3600_000L) return
        enqueue(SyncMode.MERGE, 0, "jelly-like-sync-auto", replace = false)
    }

    /**
     * Runs a sync in the background so it carries on when you leave the screen or close the app. It shows a notification
     * while it works. Safe to repeat: whatever already went through is skipped next time.
     */
    fun enqueue(mode: SyncMode, delayMs: Long = 0, unique: String = "jelly-like-sync", replace: Boolean = false) {
        val request = androidx.work.OneTimeWorkRequestBuilder<LikeSyncWorker>()
            .setInputData(androidx.work.workDataOf("mode" to mode.encode()))
            .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        androidx.work.WorkManager.getInstance(appContext).enqueueUniqueWork(
            unique,
            if (replace) androidx.work.ExistingWorkPolicy.REPLACE else androidx.work.ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** What the background worker runs. */
    internal suspend fun runJob(mode: SyncMode, onProgress: suspend (Int, Int) -> Unit) = apply(plan(mode), onProgress)

    /** After a heart tap: sync shortly, so other services follow. */
    fun syncSoon() {
        if (!_auto.value || !firstSyncDone) return
        enqueue(SyncMode.MERGE, 30_000, "jelly-like-sync-soon", replace = true)
    }
}
