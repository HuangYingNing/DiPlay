package com.shilapi.xcertplay.transport

import android.bluetooth.BluetoothAdapter
import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WirelessBluetoothInventoryTest {
    @Test
    fun reportShowsRadioStateAndPairedAndConnectedCountsWithoutDeviceIdentity() {
        val context = RuntimeEnvironment.getApplication() as Context
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(false)

        val snapshot = WirelessBluetoothInventory.capture(context)
        val report = snapshot.reportLine()

        assertTrue(report, report.contains("state=OFF"))
        assertTrue(report, report.contains("bonded="))
        assertTrue(report, report.contains("connectedProfiles="))
        assertTrue(report, !report.contains("address="))
        assertTrue(report, !report.contains("name="))
    }
}
