package com.shilapi.xcertplay.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build

/** 记录系统广播可见的蓝牙连接事件，并在本机短期保存当前连接设备地址供选择器使用。 */
object BluetoothAclInventory {
    private const val PREFERENCES = "diplay_bluetooth_acl_inventory"
    private const val CONNECTED_ADDRESSES = "connected_addresses"
    private const val LAST_EVENT_AT = "last_event_at"
    private const val LAST_EVENT = "last_event"
    private const val LAST_DEVICE_KIND = "last_device_kind"
    private const val CONNECTED_EVENTS = "connected_events"
    private const val DISCONNECTED_EVENTS = "disconnected_events"
    private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
    private val lock = Any()

    fun record(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val device = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            }
        }.getOrNull() ?: return
        val address = runCatching { device.address }.getOrNull()
            ?.takeIf(BluetoothAdapter::checkBluetoothAddress) ?: return
        val kind = runCatching { device.name }.getOrNull()?.let { name ->
            if (name.contains("iPhone", ignoreCase = true)) "iphone" else "other"
        } ?: "unknown"
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        synchronized(lock) {
            val addresses = preferences.getStringSet(CONNECTED_ADDRESSES, emptySet()).orEmpty().toMutableSet()
            if (connected) addresses += address else addresses.remove(address)
            val editor = preferences.edit()
                .putStringSet(CONNECTED_ADDRESSES, addresses)
                .putLong(LAST_EVENT_AT, System.currentTimeMillis())
                .putString(LAST_EVENT, if (connected) "connected" else "disconnected")
                .putString(LAST_DEVICE_KIND, kind)
            if (connected) {
                editor.putInt(CONNECTED_EVENTS, preferences.getInt(CONNECTED_EVENTS, 0) + 1)
            } else {
                editor.putInt(DISCONNECTED_EVENTS, preferences.getInt(DISCONNECTED_EVENTS, 0) + 1)
            }
            editor.apply()
        }
    }

    fun connectedDevices(context: Context, adapter: BluetoothAdapter?): Set<BluetoothDevice> {
        if (adapter == null) return emptySet()
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val lastEventAt = preferences.getLong(LAST_EVENT_AT, 0L)
        if (lastEventAt <= 0L || System.currentTimeMillis() - lastEventAt > MAX_AGE_MILLIS) return emptySet()
        val addresses = synchronized(lock) {
            preferences.getStringSet(CONNECTED_ADDRESSES, emptySet()).orEmpty().toSet()
        }
        return addresses.mapNotNull { address ->
            runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        }.toSet()
    }

    fun report(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val lastEventAt = preferences.getLong(LAST_EVENT_AT, 0L)
        val ageSeconds = if (lastEventAt > 0L) {
            ((System.currentTimeMillis() - lastEventAt).coerceAtLeast(0L) / 1000L).toString()
        } else "unknown"
        val connectedCount = synchronized(lock) {
            preferences.getStringSet(CONNECTED_ADDRESSES, emptySet()).orEmpty().size
        }
        return "Bluetooth ACL listener connectedEvents=${preferences.getInt(CONNECTED_EVENTS, 0)} " +
            "disconnectedEvents=${preferences.getInt(DISCONNECTED_EVENTS, 0)} " +
            "recentConnected=$connectedCount lastEvent=${preferences.getString(LAST_EVENT, "none") ?: "none"} " +
            "lastDeviceKind=${preferences.getString(LAST_DEVICE_KIND, "unknown") ?: "unknown"} " +
            "lastEventAgeSeconds=$ageSeconds"
    }
}
