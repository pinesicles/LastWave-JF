package com.lastwave.app.data.jellyfin

import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.PlaybackTrackHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the stream quality at the moment a Jellyfin song is queued, so "Auto" reacts to what you're
 * connected to now (mobile data, Bluetooth, wired) instead of when the list was loaded.
 */
@Singleton
class JellyPlayTimeQuality @Inject constructor(
    private val api: JellyfinApi,
    store: JellyfinSessionStore,
    private val autoQuality: JellyAutoQuality,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var session: JellyfinSession? = null
    @Volatile private var auto = false
    @Volatile private var fixedKbps = 0
    private val sourceKbps = ConcurrentHashMap<String, Int>()

    init {
        scope.launch { store.session.collect { session = it } }
        scope.launch { store.autoQuality.collect { auto = it } }
        scope.launch { store.streamKbps.collect { fixedKbps = it } }
        PlaybackTrackHook.transform = ::retarget
    }

    /** Remembered when a track is mapped, so the choice can be redone later without asking the server. */
    fun remember(itemId: String, kbps: Int?) {
        if (kbps != null) sourceKbps[itemId] = kbps
    }

    private fun retarget(track: PlayableTrack): PlayableTrack {
        val id = track.jellyfinItemIdOrNull() ?: return track
        val url = track.playbackUrl ?: return track
        if (!url.startsWith("http")) return track // downloaded copy: nothing to choose
        val s = session ?: return track
        var cap = if (auto) autoQuality.capKbps() else fixedKbps
        // Data saver: nothing above 128 kbps.
        if (com.lastwave.app.data.network.DataMeter.savingNow() && (cap <= 0 || cap > 128)) cap = 128
        val target = api.streamTarget(s, id, cap, sourceKbps[id])
        return track.copy(playbackUrl = target.url, playbackMimeType = target.mimeType)
    }
}
