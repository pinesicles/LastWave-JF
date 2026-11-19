package com.lastwave.app.data.network

/**
 * How often YouTube Music's background syncs may run. Upstream polls every minute while the app is open (re-downloading the
 * whole liked list each time); on weak Wi-Fi that adds up to tens of MB an hour, so JellyWave stretches it and pauses it in
 * the data saver. Changes you make (a heart, a playlist edit) still sync straight away.
 */
object YtPolling {
    const val INTERVAL_MS = 20 * 60_000L

    fun allowed(): Boolean = !DataMeter.savingNow()
}
