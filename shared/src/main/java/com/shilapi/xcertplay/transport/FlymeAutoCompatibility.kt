package com.shilapi.xcertplay.transport

import android.content.Context
import android.os.Build

/** 识别星愿使用的 GeelyCloud 车机固件，避免用 Android 通用接口改动其无线开关。 */
object FlymeAutoCompatibility {
    fun usesBroadcastBluetooth(context: Context): Boolean {
        val selectedGeneration = context.applicationContext
            .getSharedPreferences("diplay", Context.MODE_PRIVATE)
            .getString("setup_dilink_generation", null)
        return usesBroadcastBluetooth(isGeelyHeadUnit(), selectedGeneration)
    }

    fun usesBroadcastBluetooth(isGeelyHeadUnit: Boolean, selectedGeneration: String?): Boolean =
        isGeelyHeadUnit || selectedGeneration == "unsupported"

    fun isGeelyHeadUnit(
        manufacturer: String = Build.MANUFACTURER,
        brand: String = Build.BRAND,
        model: String = Build.MODEL,
        product: String = Build.PRODUCT,
        fingerprint: String = Build.FINGERPRINT,
    ): Boolean {
        val identity = listOf(manufacturer, brand, model, product, fingerprint).joinToString(" ").lowercase()
        return "geelycloud" in identity || "flyme auto" in identity || "flymeauto" in identity
    }
}
