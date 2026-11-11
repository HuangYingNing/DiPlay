package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.shilapi.xcertplay.transport.BluetoothAclInventory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PhoneBluetoothReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val selected = "12:34:56:78:9A:BC"

    @Before fun reset() {
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, false)
        app.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_phone_wake_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_bluetooth_acl_inventory", Context.MODE_PRIVATE).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        // Saved in lowercase on purpose: the receiver must match the (always uppercase) device
        // address case-insensitively; Robolectric refuses to build a lowercase remote device.
        DiPlayPreferences.savePhone(app, selected.lowercase(), "iPhone")
    }

    private fun event(address: String, action: String = BluetoothDevice.ACTION_ACL_CONNECTED): Intent {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        return Intent(action)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
    }

    @Test fun aclBroadcastsRecordTheConnectedPhoneWithoutWakeOrAddressInTheReport() {
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().remove("phone_address").commit()
        val receiver = PhoneBluetoothReceiver()

        receiver.onReceive(app, event(selected))
        val connected = BluetoothAclInventory.report(app)
        assertTrue(connected, connected.contains("connectedEvents=1"))
        assertTrue(connected, connected.contains("recentConnected=1"))
        assertFalse(connected.contains(selected))
        assertTrue(BluetoothAclInventory.connectedDevices(app, BluetoothAdapter.getDefaultAdapter()).size == 1)

        receiver.onReceive(app, event(selected, BluetoothDevice.ACTION_ACL_DISCONNECTED))
        val disconnected = BluetoothAclInventory.report(app)
        assertTrue(disconnected, disconnected.contains("disconnectedEvents=1"))
        assertTrue(disconnected, disconnected.contains("recentConnected=0"))
        assertFalse(disconnected.contains(selected))
    }

    @Test fun onlySelectedPhoneWithOptInLaunchesOnce() {
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        val receiver = PhoneBluetoothReceiver()
        receiver.onReceive(context, event(selected))
        assertTrue(launches.isEmpty())
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        receiver.onReceive(context, event("AA:BB:CC:DD:EE:FF"))
        assertTrue(launches.isEmpty())
        receiver.onReceive(context, event(selected))
        receiver.onReceive(context, event(selected))
        assertEquals(1, launches.size)
        assertEquals(DiPlayActivity::class.java.name, launches.single().component?.className)
        assertEquals(PhoneBluetoothReceiver.launchToken, launches.single().getStringExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT))
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=startActivity-returned"))
    }
}
