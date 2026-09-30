package com.ck.orbiteq.global

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

object DeviceUtil {
    private val HEADPHONE_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    fun isHeadphone(d: AudioDeviceInfo) = d.isSink && d.type in HEADPHONE_TYPES

    fun label(d: AudioDeviceInfo): String {
        val name = d.productName?.toString()?.trim().orEmpty()
        if (name.isNotEmpty()) return name
        return when (d.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            else -> "Bluetooth headphones"
        }
    }

    /** Stable key used to remember which profile belongs to which headphones. */
    fun key(d: AudioDeviceInfo) = "${d.type}:${label(d)}"

    fun currentHeadphone(ctx: Context): AudioDeviceInfo? {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isHeadphone(it) }
    }
}
