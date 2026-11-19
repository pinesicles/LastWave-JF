package com.lastwave.app.playback

import android.content.Context
import android.net.Uri
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android Auto has no lyrics screen for media apps, so the current synced lyric line is shown in the
 * artist or album line of the now-playing metadata, only while Android Auto is connected.
 */
object CarLyrics {
    const val OFF = 0
    const val ALBUM = 1
    const val ARTIST = 2

    @Volatile var line: String? = null
    @Volatile var mode: Int = OFF

    private val modeFlow = MutableStateFlow(OFF)
    val modeState: StateFlow<Int> = modeFlow.asStateFlow()
    private var loaded = false

    internal var job: Job? = null
    internal var mediaId: String? = null
    private var checkedAt = 0L
    private var connected = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        mode = try { ctx.applicationContext.getSharedPreferences("jw_carlyrics", Context.MODE_PRIVATE).getInt("mode", OFF) } catch (_: Exception) { OFF }
        modeFlow.value = mode
    }

    fun setMode(ctx: Context, value: Int) {
        load(ctx)
        mode = value; modeFlow.value = value
        if (value == OFF) line = null
        try { ctx.applicationContext.getSharedPreferences("jw_carlyrics", Context.MODE_PRIVATE).edit().putInt("mode", value).apply() } catch (_: Exception) { }
    }

    /** True while a phone is projecting to Android Auto. If it can't be told, assume yes. */
    internal fun carConnected(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - checkedAt < 5_000) return connected
        checkedAt = now
        connected = try {
            ctx.contentResolver.query(Uri.parse("content://androidx.car.app.connection"), arrayOf("CarConnectionState"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getInt(c.getColumnIndex("CarConnectionState")) != 0 else false
            } ?: true
        } catch (_: Exception) { true }
        return connected
    }

    internal fun lineAt(lines: List<LyricLine>, positionMs: Long): String? {
        var best: LyricLine? = null
        for (l in lines) { if (l.timeMs <= positionMs + 150) best = l else break }
        return best?.text?.replace('\n', ' ')?.trim()?.takeIf { it.isNotBlank() }
    }
}

/** Once per track: fetch synced lyrics, then follow the playback position and republish metadata on each new line. */
internal fun MusicPlaybackService.publishCarLyrics(state: MusicPlayerState) {
    CarLyrics.load(this)
    val track = state.current
    val mediaId = if (CarLyrics.mode == CarLyrics.OFF || track == null) null else "${state.currentIndex}|${track.title}|${track.artist}|${CarLyrics.mode}"
    if (mediaId == CarLyrics.mediaId) return
    CarLyrics.mediaId = mediaId
    CarLyrics.job?.cancel()
    if (CarLyrics.line != null) { CarLyrics.line = null; systemStateSignature = "" }
    if (track == null || mediaId == null) return
    CarLyrics.job = scope.launch {
        val result = try {
            lyricsRepository.getLyrics(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationSeconds = (state.durationMs.takeIf { it > 0 } ?: track.durationMs)?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
                wordByWord = false,
                videoId = track.videoId,
                localLrcPath = com.lastwave.app.data.generate.LocalAudioFormats
                    .importedPathOrNull(track.playbackUrl)
                    ?.let(com.lastwave.app.data.generate.LocalAudioFormats::lrcPathFor),
            )
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val ok = result as? LyricsResult.Success ?: return@launch
        if (!ok.isSynced) return@launch
        val lines = ok.lines.filter { it.timeMs >= 0 }.sortedBy { it.timeMs }
        if (lines.isEmpty()) return@launch
        while (isActive) {
            val next = if (CarLyrics.carConnected(this@publishCarLyrics) && musicPlayer.state.value.isPlaying) {
                CarLyrics.lineAt(lines, musicPlayer.currentPositionMs())
            } else null
            if (next != CarLyrics.line && (next != null || CarLyrics.line != null)) {
                CarLyrics.line = next
                systemStateSignature = ""
                publishSystemState(musicPlayer.state.value)
            }
            delay(400)
        }
    }
}
