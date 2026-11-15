package com.lastwave.app.data.jellyfin

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Auto" streaming quality. The original file (FLAC etc.) is used whenever it makes sense; a 320 kbps
 * transcode is used when the original would be wasted or costly:
 *  - on a metered connection (mobile data), or
 *  - when the only output is a Bluetooth headset, which compresses the audio anyway.
 * Wired, USB and speaker output always get the original.
 */
@Singleton
class JellyAutoQuality @Inject constructor(@ApplicationContext private val context: Context) {

    /** 0 = the original is fine; otherwise the bitrate cap in kbps. */
    fun capKbps(): Int {
        val metered = runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
        }.getOrDefault(false)
        if (metered) return LOSSY_CAP
        val outputs = runCatching {
            context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList()
        }.getOrNull().orEmpty()
        if (outputs.any { it.type in WIRED }) return 0
        val bluetooth = outputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        return if (bluetooth) LOSSY_CAP else 0
    }

    private companion object {
        const val LOSSY_CAP = 320
        val WIRED = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_HDMI,
        )
    }
}
