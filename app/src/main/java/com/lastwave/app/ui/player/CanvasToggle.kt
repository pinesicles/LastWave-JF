package com.lastwave.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.local.SettingsPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Flips the same "Canvas" setting as Settings, from the Now Playing menu. Canvas code itself is untouched. */
@HiltViewModel
class CanvasToggleViewModel @Inject constructor(private val settings: SettingsPreferences) : ViewModel() {
    fun set(on: Boolean) { viewModelScope.launch { settings.setCanvasEnabled(on) } }
}
