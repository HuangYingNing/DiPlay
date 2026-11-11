package com.shilapi.xcertplay.transport

import android.os.Build

/** 识别星愿使用的 GeelyCloud 车机固件，避免用 Android 通用接口改动其无线开关。 */
object FlymeAutoCompatibility {
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
