package com.shilapi.xcertplay.transport

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/** ADB 已获授权时，只读取车机蓝牙、Wi‑Fi 系统服务状态，不执行开关或配对操作。 */
internal object GeelyHeadUnitWirelessDiagnostics {
    fun capture(context: Context): String {
        LocalAdb(AdbKeys.load(context.applicationContext)).use { adb ->
            val access = adb.connect(mayAsk = false)
            if (access != LocalAdb.Access.READY) return "headUnitAdb=$access systemQueries=not_run"

            val services = adb.shell("service list", SYSTEM_QUERY_TIMEOUT_MILLIS)
            val bluetooth = adb.shell("dumpsys bluetooth_manager", SYSTEM_QUERY_TIMEOUT_MILLIS)
            val wifi = adb.shell("dumpsys wifi", SYSTEM_QUERY_TIMEOUT_MILLIS)
            val processes = adb.shell("ps -A", SYSTEM_QUERY_TIMEOUT_MILLIS)
            return summarize(access, services, bluetooth, wifi, processes)
        }
    }

    internal fun summarize(
        access: LocalAdb.Access,
        services: String?,
        bluetooth: String?,
        wifi: String?,
        processes: String?,
    ): String {
        if (access != LocalAdb.Access.READY) return "headUnitAdb=$access systemQueries=not_run"
        val serviceText = services.orEmpty()
        val bluetoothText = bluetooth.orEmpty()
        val wifiText = wifi.orEmpty()
        val processText = processes.orEmpty()
        val adapterState = when {
            "STATE_ON" in bluetoothText || regexFind(STATE_LINE, bluetoothText) in setOf("ON", "12") -> "ON"
            "STATE_OFF" in bluetoothText || regexFind(STATE_LINE, bluetoothText) in setOf("OFF", "10") -> "OFF"
            bluetooth == null -> "unavailable"
            else -> "unknown"
        }
        return "headUnitAdb=READY serviceList=${if (services == null) "unavailable" else "readable"} " +
            "bluetoothManager=${if (bluetooth == null) "unavailable" else "readable"} " +
            "systemBluetoothState=$adapterState " +
            "systemConnectedHints=${countLines(bluetoothText, CONNECTED_LINE)} " +
            "systemBondHints=${countLines(bluetoothText, BONDED_LINE)} " +
            "ecarxBluetoothService=${serviceText.contains("ecarx_bluetooth_service", ignoreCase = true)} " +
            "anwSdkService=${serviceText.contains("com.anwsdk", ignoreCase = true)} " +
            "wifiDump=${if (wifi == null) "unavailable" else "readable"} " +
            "wifiEnabledHint=${booleanHint(wifiText, WIFI_ENABLED_LINE)} " +
            "softApMentioned=${wifiText.contains("softap", ignoreCase = true)} " +
            "ecarxBluetoothProcess=${processText.contains("ecarx.bluetooth.service", ignoreCase = true)} " +
            "geelyBluetoothPhoneProcess=${processText.contains("geely.btphone", ignoreCase = true)} " +
            "nforetekBluetoothProcess=${processText.contains("com.nforetek.bt", ignoreCase = true)}"
    }

    private fun booleanHint(text: String, pattern: Regex): String =
        regexFind(pattern, text)?.lowercase() ?: "unknown"

    private fun countLines(text: String, pattern: Regex): Int =
        text.lineSequence().count { pattern.containsMatchIn(it) }

    private fun regexFind(pattern: Regex, text: String): String? =
        pattern.find(text)?.groupValues?.getOrNull(1)

    private val STATE_LINE = Regex("(?im)\\b(?:m?state)\\s*[:=]\\s*(?:STATE_)?(ON|OFF|12|10)\\b")
    private val CONNECTED_LINE = Regex("(?i)connected")
    private val BONDED_LINE = Regex("(?i)bonded")
    private val WIFI_ENABLED_LINE = Regex("(?im)\\b(?:mWifiEnabled|wifiEnabled|enabled)\\s*[:=]\\s*(true|false)")

    private const val SYSTEM_QUERY_TIMEOUT_MILLIS = 3_000
}
