package com.lastwave.app.data.jellyfin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the phone has a working internet connection right now, kept up to date live. */
@Singleton
class JellyConnectivity @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun check(): Boolean = runCatching {
        val network = cm?.activeNetwork ?: return@runCatching false
        cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    private val _online = MutableStateFlow(check())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = true }
                override fun onLost(network: Network) { _online.value = check() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        }
    }
}
