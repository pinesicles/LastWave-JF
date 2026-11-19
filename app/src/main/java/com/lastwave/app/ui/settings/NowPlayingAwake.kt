package com.lastwave.app.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** "Keep screen on in Now Playing" setting, kept in its own small prefs file. */
object NowPlayingAwake {
    private val state = MutableStateFlow<Boolean?>(null)
    private val out = MutableStateFlow(false)

    fun flow(ctx: Context): StateFlow<Boolean> {
        if (state.value == null) {
            val v = try { ctx.applicationContext.getSharedPreferences("jw_awake", Context.MODE_PRIVATE).getBoolean("now_playing", false) } catch (_: Exception) { false }
            state.value = v; out.value = v
        }
        return out.asStateFlow()
    }

    fun set(ctx: Context, on: Boolean) {
        state.value = on; out.value = on
        try { ctx.applicationContext.getSharedPreferences("jw_awake", Context.MODE_PRIVATE).edit().putBoolean("now_playing", on).apply() } catch (_: Exception) { }
    }
}
