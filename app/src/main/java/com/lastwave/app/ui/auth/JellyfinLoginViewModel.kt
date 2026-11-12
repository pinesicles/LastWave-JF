package com.lastwave.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.jellyfin.DiscoveredServer
import com.lastwave.app.data.jellyfin.JellyfinDiscovery
import com.lastwave.app.data.jellyfin.JellyfinException
import com.lastwave.app.data.jellyfin.JellyfinRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JellyfinLoginUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val scanning: Boolean = false,
    val servers: List<DiscoveredServer> = emptyList(),
    /** True once a scan has finished, so the UI can say "none found". */
    val scanned: Boolean = false,
)

/** Drives the Jellyfin form on the login screen. A saved session unlocks the app (see LaunchGateViewModel). */
@HiltViewModel
class JellyfinLoginViewModel @Inject constructor(
    private val repository: JellyfinRepository,
    private val discovery: JellyfinDiscovery,
) : ViewModel() {

    private val _state = MutableStateFlow(JellyfinLoginUiState())
    val state: StateFlow<JellyfinLoginUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        scan()
    }

    /** Looks for Jellyfin servers on the local network; safe to call repeatedly. */
    fun scan() {
        if (scanJob?.isActive == true) return
        _state.update { it.copy(scanning = true, scanned = false) }
        scanJob = viewModelScope.launch {
            val found = try {
                discovery.discover()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _state.update { it.copy(scanning = false, scanned = true, servers = found) }
        }
    }

    fun connect(server: String, username: String, password: String) {
        if (_state.value.busy) return
        if (server.isBlank() || username.isBlank()) {
            _state.update { it.copy(error = "Enter the server address and your username") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                repository.login(server, username, password)
                _state.update { it.copy(busy = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "Couldn't sign in") }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }
}
