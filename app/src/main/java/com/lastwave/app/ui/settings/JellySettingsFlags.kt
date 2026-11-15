package com.lastwave.app.ui.settings

/**
 * JellyWave: switches for settings that only apply to the old YouTube-based LastWave sources.
 * Hidden (not deleted) so upstream merges stay trivial. Flip to true to bring them back.
 */
internal object JellySettingsFlags {
    /** Wi-Fi / cellular streaming quality and download quality tiers (Jellyfin has its own in the Jellyfin section). */
    const val SHOW_YOUTUBE_QUALITY_TIERS = false
}
