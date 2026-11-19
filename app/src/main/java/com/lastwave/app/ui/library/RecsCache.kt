package com.lastwave.app.ui.library

import android.content.Context
import com.lastwave.app.data.generate.GeneratedTrack
import org.json.JSONArray
import org.json.JSONObject

/** Keeps the Feed's YouTube recommendations for 12 hours so opening the app doesn't re-fetch them every time. */
internal object RecsCache {
    private const val TTL_MS = 12L * 60 * 60 * 1000
    private fun prefs(c: Context) = c.getSharedPreferences("jw_recs_cache", Context.MODE_PRIVATE)

    fun load(c: Context): List<GeneratedTrack>? = runCatching {
        val p = prefs(c)
        if (System.currentTimeMillis() - p.getLong("at", 0L) > TTL_MS) return null
        val a = JSONArray(p.getString("list", null) ?: return null)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            GeneratedTrack(
                name = o.getString("n"), artist = o.getString("a"),
                artworkUrl = o.optString("art").ifBlank { null }, url = o.optString("u"),
                album = o.optString("al").ifBlank { null }, durationMs = o.optLong("d").takeIf { it > 0 },
            )
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun save(c: Context, list: List<GeneratedTrack>) {
        if (list.isEmpty()) return
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("n", it.name).put("a", it.artist).put("art", it.artworkUrl ?: "").put("u", it.url)
                .put("al", it.album ?: "").put("d", it.durationMs ?: 0L))
        }
        prefs(c).edit().putString("list", a.toString()).putLong("at", System.currentTimeMillis()).apply()
    }
}
