package com.lastwave.app.data.jellyfin.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lastwave.app.data.jellyfin.JellyfinApi
import com.lastwave.app.data.jellyfin.JellyfinException
import com.lastwave.app.data.jellyfin.JellyfinSession
import com.lastwave.app.data.jellyfin.JellyfinSessionStore
import com.lastwave.app.data.jellyfin.JfItem
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

const val COLLECTION_ALBUM = "album"
const val COLLECTION_PLAYLIST = "playlist"

/**
 * Offline copies of Jellyfin albums and playlists.
 *
 * Every song is stored once, at `<Music>/JellyWave/<Album artist>/<Album>/<NN - Title>.<ext>`, and
 * albums/playlists only point at songs, so a song in an album and a playlist is never stored twice.
 * A song's file is deleted only when no downloaded album or playlist still uses it.
 *
 * Files go in the app's own storage, so they stay out of the Local files tab. They are removed if the
 * app is uninstalled.
 */
@Singleton
class JellyDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: JellyDownloadDao,
    private val api: JellyfinApi,
    private val store: JellyfinSessionStore,
    private val lyrics: com.lastwave.app.data.jellyfin.JellyLyrics,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val collections: Flow<List<JfCollectionEntity>> = dao.collections()
    val progress: Flow<Map<String, CollectionProgress>> = dao.progress().map { list -> list.associateBy { it.collectionId } }

    private val tracks: StateFlow<Map<String, JfTrackEntity>> = dao.allTracks()
        .map { list -> list.associateBy { it.itemId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Live byte progress (0..1) of songs being fetched right now. */
    private val live = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Per-song state for the little download indicators in track lists. */
    data class TrackDlState(val done: Boolean = false, val fraction: Float = 0f, val failed: Boolean = false)

    val trackStates: StateFlow<Map<String, TrackDlState>> =
        kotlinx.coroutines.flow.combine(tracks, live) { all, progress ->
            all.mapValues { (id, t) ->
                when (t.state) {
                    JF_STATE_DONE -> TrackDlState(done = true)
                    JF_STATE_FAILED -> TrackDlState(failed = true)
                    else -> TrackDlState(fraction = progress[id] ?: 0f)
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        // Pick up anything left unfinished when the app was closed.
        scope.launch { if (dao.pending().isNotEmpty()) enqueue() }
    }

    // ---- lookups used while mapping server items to playable songs (no I/O beyond File.exists) ----

    /** `file://` URI of a finished download, or null. */
    fun localUri(itemId: String): String? {
        val t = tracks.value[itemId] ?: return null
        val path = t.filePath ?: return null
        if (t.state != JF_STATE_DONE) return null
        val f = File(path)
        return if (f.exists()) Uri.fromFile(f).toString() else null
    }

    /** Saved cover for an album or playlist id, or null. */
    fun localCover(id: String?): String? {
        if (id == null) return null
        val f = coverFile(id)
        return if (f.exists()) Uri.fromFile(f).toString() else null
    }

    suspend fun collection(id: String) = dao.collection(id)
    suspend fun tracksOf(id: String) = dao.tracksOf(id)

    // ---- storage layout ----

    private fun root() = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "JellyWave")
    /** A new cover was uploaded: if a downloaded copy exists, replace it so the old one doesn't win after a restart. */
    fun replaceCover(id: String, bytes: ByteArray) {
        val f = coverFile(id)
        if (f.exists()) runCatching { f.writeBytes(bytes) }
    }

    private fun coverFile(id: String) = File(File(root(), ".covers"), "$id.jpg")

    private fun safe(name: String?): String {
        val cleaned = (name ?: "").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        return cleaned.ifBlank { "Unknown" }.take(80)
    }

    internal fun trackFile(t: JfTrackEntity): File {
        val dir = File(File(root(), safe(t.albumArtist ?: t.artist)), safe(t.album))
        val number = "%02d".format(t.trackNumber ?: 0)
        return File(dir, "$number - ${safe(t.title)}.${t.container ?: "audio"}")
    }

    private fun extensionOf(item: JfItem): String? {
        val raw = item.mediaSources.firstOrNull()?.container?.substringBefore(',')?.lowercase() ?: return null
        return when (raw) {
            "mov", "mp4" -> "m4a"
            "matroska" -> "mka"
            "" -> null
            else -> raw
        }
    }

    // ---- collections ----

    private suspend fun requireSession(): JellyfinSession =
        store.current() ?: throw JellyfinException("Not signed in to Jellyfin")

    private suspend fun register(items: List<JfItem>) {
        val wantKbps = store.currentDownloadKbps()
        items.forEach { item ->
            val existing = dao.track(item.id)
            val sourceKbps = item.mediaSources.firstOrNull()?.bitrate?.let { (it / 1000L).toInt() }
            // Only transcode when the original is bigger than the chosen quality.
            val transcode = if (wantKbps > 0 && (sourceKbps == null || sourceKbps > wantKbps)) wantKbps else 0
            if (existing == null) {
                dao.insertTrackIfAbsent(
                    JfTrackEntity(
                        itemId = item.id,
                        title = item.name.orEmpty().ifBlank { "Unknown track" },
                        artist = item.artists.joinToString(", ").ifBlank { item.albumArtist.orEmpty() }.ifBlank { "Unknown artist" },
                        albumArtist = item.albumArtist,
                        album = item.album,
                        albumId = item.albumId,
                        trackNumber = item.trackNumber,
                        durationMs = item.durationMs,
                        container = if (transcode > 0) "mp3" else extensionOf(item),
                        filePath = null,
                        sizeBytes = if (transcode > 0) 0L else item.mediaSources.firstOrNull()?.size ?: 0L,
                        state = JF_STATE_QUEUED,
                        transcodeKbps = transcode,
                    ),
                )
            } else if (existing.state == JF_STATE_FAILED) {
                dao.requeueFailed()
            }
        }
    }

    private suspend fun fetchItems(session: JellyfinSession, type: String, id: String): List<JfItem> =
        if (type == COLLECTION_PLAYLIST) api.playlistTracks(session, id).items else api.albumTracks(session, id).items

    /** Saves an album or playlist for offline use. Playlists start in "keep in sync" mode. */
    suspend fun download(item: JfItem, type: String) {
        val session = requireSession()
        val items = fetchItems(session, type, item.id)
        register(items)
        val existing = dao.collection(item.id)
        dao.replaceCollection(
            JfCollectionEntity(
                id = item.id,
                type = type,
                name = item.name.orEmpty(),
                subtitle = item.albumArtist,
                autoSync = existing?.autoSync ?: (type == COLLECTION_PLAYLIST),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            ),
            items.mapIndexed { i, t -> JfCollectionTrackEntity(item.id, i, t.id) },
        )
        item.imageTags["Primary"]?.let { tag ->
            runCatching { saveCover(session, item.id, tag) }
        }
        enqueue()
    }

    /** Re-reads a "keep in sync" playlist from the server and downloads/drops songs to match. */
    suspend fun sync(playlistId: String) {
        val existing = dao.collection(playlistId) ?: return
        if (!existing.autoSync || existing.type != COLLECTION_PLAYLIST) return
        val session = requireSession()
        val items = fetchItems(session, COLLECTION_PLAYLIST, playlistId)
        register(items)
        dao.replaceCollection(existing, items.mapIndexed { i, t -> JfCollectionTrackEntity(playlistId, i, t.id) })
        deleteOrphans()
        enqueue()
    }

    suspend fun setAutoSync(id: String, on: Boolean) {
        dao.setAutoSync(id, on)
        if (on) sync(id)
    }

    suspend fun remove(id: String) {
        dao.deleteCollection(id)
        dao.clearLinks(id)
        deleteOrphans()
        coverFile(id).delete()
    }

    private suspend fun deleteOrphans() = withContext(Dispatchers.IO) {
        dao.orphans().forEach { t ->
            t.filePath?.let { path ->
                val f = File(path)
                f.delete()
                File(path + ".part").delete()
                File(f.parentFile, f.nameWithoutExtension + ".lrc").delete()
                // Tidy up empty album/artist folders.
                var dir = f.parentFile
                repeat(2) {
                    if (dir != null && dir!!.exists() && dir!!.list().isNullOrEmpty() && dir != root()) {
                        dir!!.delete()
                        dir = dir!!.parentFile
                    }
                }
            }
            dao.deleteTrack(t.itemId)
        }
    }

    private suspend fun saveCover(session: JellyfinSession, id: String, tag: String?) {
        val dest = coverFile(id)
        dest.parentFile?.mkdirs()
        api.downloadToFile(session, api.imageUrl(session, id, tag, 600), dest) {}
    }

    // ---- background work ----

    fun enqueue(replace: Boolean = false) {
        scope.launch {
            val network = if (store.currentWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<JellyDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "jelly-downloads",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    val wifiOnly: Flow<Boolean> get() = store.wifiOnly

    /** 0..1 progress of one album/playlist, counting the song being fetched right now. */
    fun collectionFraction(id: String): Flow<Float> =
        kotlinx.coroutines.flow.combine(dao.trackIds(id), trackStates) { ids, states ->
            if (ids.isEmpty()) 0f
            else ids.sumOf { itemId ->
                val s = states[itemId]
                (if (s?.done == true) 1f else s?.fraction ?: 0f).toDouble()
            }.toFloat() / ids.size
        }

    /** (finished songs, total bytes) for the settings page. */
    val summary: StateFlow<Pair<Int, Long>> = tracks
        .map { all ->
            val done = all.values.filter { it.state == JF_STATE_DONE }
            done.size to done.sumOf { it.sizeBytes }
        }
        .stateIn(scope, SharingStarted.Eagerly, 0 to 0L)

    suspend fun removeAll() {
        dao.collections().first().forEach { remove(it.id) }
    }

    suspend fun setWifiOnly(on: Boolean) {
        store.setWifiOnly(on)
        if (dao.pending().isNotEmpty()) enqueue(replace = true)
    }

    /** Downloads queued songs one at a time. Returns false if any failed. Called by the worker. */
    internal suspend fun runQueue(onStatus: suspend (done: Int, total: Int, title: String) -> Unit): Boolean {
        val session = store.current() ?: return false
        var done = 0
        var allOk = true
        while (true) {
            val queue = dao.pending()
            val next = queue.firstOrNull() ?: break
            onStatus(done, done + queue.size, next.title)
            val ok = try {
                downloadOne(session, next)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                dao.markFailed(next.itemId, e.message ?: "Download failed")
                false
            }
            if (ok) done++ else allOk = false
        }
        return allOk
    }

    private suspend fun downloadOne(session: JellyfinSession, t: JfTrackEntity) {
        val file = trackFile(t)
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        var last = -1
        try {
            val url = if (t.transcodeKbps > 0) api.transcodedFileUrl(session, t.itemId, t.transcodeKbps)
            else api.downloadUrl(session, t.itemId)
            // A transcode has no known size up front, so estimate it from the length and bitrate.
            val expected = if (t.sizeBytes > 0) t.sizeBytes
            else (t.durationMs ?: 0L) / 1000 * t.transcodeKbps * 125L
            api.downloadToFile(session, url, part) { bytes ->
                if (expected > 0) {
                    val pct = (bytes * 100 / expected).toInt().coerceIn(0, 99)
                    if (pct != last) {
                        last = pct
                        live.update { it + (t.itemId to pct / 100f) }
                    }
                }
            }
        } finally {
            live.update { it - t.itemId }
        }
        if (t.sizeBytes > 0 && part.length() != t.sizeBytes) {
            part.delete()
            throw JellyfinException("Download was incomplete, will retry")
        }
        if (file.exists()) file.delete()
        if (!part.renameTo(file)) throw JellyfinException("Couldn't save the file")
        dao.markDone(t.itemId, file.absolutePath, file.length())
        runCatching { lyrics.saveSidecar(t, file) }
        // One cover per album, shared by all its songs.
        t.albumId?.let { albumId ->
            if (!coverFile(albumId).exists()) runCatching { saveCover(session, albumId, null) }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface JellyDownloadsEntryPoint {
    fun downloads(): JellyDownloads
}

class JellyDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private fun notification(text: String, progress: Pair<Int, Int>? = null): android.app.Notification {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.createNotificationChannel(
            android.app.NotificationChannel("jelly_downloads", "Downloads", android.app.NotificationManager.IMPORTANCE_LOW),
        )
        return androidx.core.app.NotificationCompat.Builder(applicationContext, "jelly_downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading music")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (progress != null) setProgress(progress.second, progress.first, false) }
            .build()
    }

    private fun info(text: String, progress: Pair<Int, Int>? = null) = ForegroundInfo(
        4711,
        notification(text, progress),
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Starting…")

    override suspend fun doWork(): Result {
        val downloads = EntryPointAccessors.fromApplication(applicationContext, JellyDownloadsEntryPoint::class.java).downloads()
        runCatching { setForeground(info("Starting…")) }
        val ok = downloads.runQueue { done, total, title ->
            runCatching { setForeground(info("$title (${done + 1} of $total)", done to total)) }
        }
        return if (ok) Result.success() else Result.retry()
    }
}
