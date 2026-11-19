package com.lastwave.app.playback

import android.content.Context

/**
 * Remembers shuffle per playlist or album (keyed by its play label), so shuffling one doesn't shuffle the rest.
 * Generic labels (search, all songs, radio) don't keep a setting: they only shuffle when asked to.
 */
object PlaylistShuffleMemory {
    private val generic = setOf("LastWave", "Search", "All songs", "Discover", "YouTube search")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jw_shuffle", Context.MODE_PRIVATE)

    /** Whether a queue started for [label] should be shuffled. An explicit shuffle play also saves it. */
    fun resolve(ctx: Context, label: String, startShuffled: Boolean): Boolean {
        if (label in generic) return startShuffled
        if (startShuffled) { prefs(ctx).edit().putBoolean(label, true).apply(); return true }
        return prefs(ctx).getBoolean(label, false)
    }

    /** Called when the shuffle button is toggled while [label] is playing. */
    fun remember(ctx: Context, label: String, enabled: Boolean) {
        if (label in generic) return
        prefs(ctx).edit().putBoolean(label, enabled).apply()
    }
}
