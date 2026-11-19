package com.lastwave.app.ui.settings

/**
 * JellyWave: switches for settings that only apply to the old YouTube-based LastWave sources.
 * Hidden (not deleted) so upstream merges stay trivial. Flip to true to bring them back.
 */
internal object JellySettingsFlags {
    /** Wi-Fi / cellular streaming quality and download quality tiers (Jellyfin has its own in the Jellyfin section). */
    const val SHOW_YOUTUBE_QUALITY_TIERS = false

    /**
     * Capped (transcoded) Jellyfin streams use HLS. The default MPEG-TS segments can lose the first second or two
     * of a track in ExoPlayer; fragmented-MP4 segments don't. Flip to true to try it if intros get clipped.
     */
    const val HLS_FMP4_SEGMENTS = false
}
