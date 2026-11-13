package com.lastwave.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.repository.HomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Last.fm profile picture, loaded the same way the stats page loads it
 * (HomeRepository.fetchStats, which shares one in-flight request). Null when
 * Last.fm isn't connected, so the header shows the placeholder icon.
 */
@HiltViewModel
class ProfileAvatarViewModel @Inject constructor(
    private val homeRepository: HomeRepository,
) : ViewModel() {
    private val _avatarUrl = MutableStateFlow<String?>(null)
    val avatarUrl: StateFlow<String?> = _avatarUrl.asStateFlow()

    init {
        viewModelScope.launch {
            _avatarUrl.value = runCatching { homeRepository.fetchStats().getOrNull()?.avatarUrl }.getOrNull()
        }
    }
}
