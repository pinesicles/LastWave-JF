package com.lastwave.app.playback

/**
 * JellyWave: lets a source adjust a track right when it is queued for playback (for example Jellyfin
 * picking the stream quality for the output device at that moment). Null means no change.
 */
object PlaybackTrackHook {
    @Volatile
    var transform: ((PlayableTrack) -> PlayableTrack)? = null

    fun apply(track: PlayableTrack): PlayableTrack = transform?.invoke(track) ?: track

    fun apply(tracks: List<PlayableTrack>): List<PlayableTrack> =
        transform?.let { f -> tracks.map(f) } ?: tracks
}
