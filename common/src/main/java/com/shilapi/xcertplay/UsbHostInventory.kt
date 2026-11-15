package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.transport.IphoneUsbMatcher

/** 在 VPN 授权前只读检查 USB 主机侧设备，区分未枚举与后续连接失败。 */
internal object UsbHostInventory {
    fun capture(context: Context): String {
        val feature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        val manager = context.getSystemService(UsbManager::class.java)
            ?: return "USB host inventory feature=$feature manager=unavailable"
        val devices = runCatching { manager.deviceList.values.toList() }.getOrElse { error ->
            return "USB host inventory feature=$feature query=${error.javaClass.simpleName}"
        }
        val apple = devices.count { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }
        val details = devices.sortedWith(compareBy({ it.vendorId }, { it.productId }))
            .take(16).joinToString(",") { device ->
                "${device.vendorId.toString(16)}:${device.productId.toString(16)}" +
                    "/class=${device.deviceClass}/interfaces=${device.interfaceCount}" +
                    "/permission=${runCatching { manager.hasPermission(device) }.getOrNull() ?: "unknown"}"
            }
        return "USB host inventory feature=$feature devices=${devices.size} apple=$apple " +
            "listed=[$details] truncated=${devices.size > 16}"
    }
}
