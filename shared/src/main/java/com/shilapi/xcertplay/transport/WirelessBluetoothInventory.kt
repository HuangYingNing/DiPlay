package com.shilapi.xcertplay.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 记录 Android 公共蓝牙接口返回的配对设备及已连接设备数量。 */
object WirelessBluetoothInventory {
    private const val PREFERENCES = "diplay_wireless_inventory"
    private const val LAST_BLUETOOTH = "last_bluetooth"

    data class Snapshot(
        val adapter: BluetoothAdapter?,
        val state: Int?,
        val bonded: Set<BluetoothDevice>,
        val connectedByProfile: Map<String, Set<BluetoothDevice>>,
        val failures: List<String>,
        val connectPermission: Boolean?,
    ) {
        val connected: Set<BluetoothDevice> = connectedByProfile.values.flatten().toSet()
        val devices: Set<BluetoothDevice> = bonded + connected

        private fun namedLikeIphone(device: BluetoothDevice): Boolean =
            device.name?.contains("iPhone", ignoreCase = true) == true

        fun reportLine(): String {
            val stateLabel = when (state) {
                BluetoothAdapter.STATE_ON -> "ON"
                BluetoothAdapter.STATE_OFF -> "OFF"
                BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                else -> "UNKNOWN"
            }
            val profiles = connectedByProfile.entries.joinToString(",") { (name, devices) -> "$name=${devices.size}" }
            return "Bluetooth inventory adapter=${if (adapter == null) "missing" else "present"} " +
                "state=$stateLabel enabled=${runCatching { adapter?.isEnabled }.getOrNull() ?: "unknown"} " +
                "connectPermission=${connectPermission?.toString() ?: "not_required"} " +
                "bonded=${bonded.size} connectedProfiles=${profiles.ifEmpty { "none" }} " +
                "bondedIphoneNamed=${bonded.count(::namedLikeIphone)} " +
                "connectedIphoneNamed=${connected.count(::namedLikeIphone)} " +
                "connectedAlsoBonded=${connected.count { active -> bonded.any { it.address.equals(active.address, true) } }} " +
                "uniqueConnected=${connected.size} discoverableTargets=${devices.size} " +
                "queryFailures=${failures.ifEmpty { listOf("none") }.joinToString(",")}"
        }
    }

    fun capture(context: Context): Snapshot {
        val app = context.applicationContext
        val manager = runCatching { app.getSystemService(BluetoothManager::class.java) }.getOrNull()
        val adapter = runCatching { manager?.adapter ?: BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else null
        val failures = mutableListOf<String>()
        val state = runCatching { adapter?.state }
            .onFailure { failures += "adapterState:${it.javaClass.simpleName}" }
            .getOrNull()
        val bonded = runCatching { adapter?.bondedDevices.orEmpty() }
            .onFailure { failures += "bondedDevices:${it.javaClass.simpleName}" }
            .getOrDefault(emptySet())
        val profiles = linkedMapOf<String, Set<BluetoothDevice>>()
        if (manager != null && adapter != null) {
            for ((name, profile) in listOf("headset" to BluetoothProfile.HEADSET, "a2dp" to BluetoothProfile.A2DP)) {
                val devices = runCatching { manager.getConnectedDevices(profile).toSet() }
                    .onFailure { failures += "${name}Connected:${it.javaClass.simpleName}" }
                    .getOrDefault(emptySet())
                profiles[name] = devices
            }
        } else {
            failures += "bluetoothManager:missing"
        }
        return Snapshot(adapter, state, bonded, profiles, failures, permission)
    }

    fun record(context: Context, point: String): Snapshot {
        val snapshot = capture(context)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(LAST_BLUETOOTH, "$stamp point=$point ${snapshot.reportLine()}").apply()
        return snapshot
    }

    fun report(context: Context): String = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(LAST_BLUETOOTH, null)
        ?: "Bluetooth inventory: no capture recorded"
}
