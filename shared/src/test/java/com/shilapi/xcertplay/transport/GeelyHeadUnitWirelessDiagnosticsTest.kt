package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.adb.LocalAdb
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyHeadUnitWirelessDiagnosticsTest {
    @Test
    fun summarizesHeadUnitBroadcastAndSystemAccessWithoutCopyingPrivateValues() {
        val report = GeelyHeadUnitWirelessDiagnostics.summarize(
            LocalAdb.Access.READY,
            "service list: ecarx_bluetooth_service",
            "mState: ON\nBonded devices: 1\nConnected devices: 1\nAA:BB:CC:DD:EE:FF iPhone",
            "mWifiEnabled=true\nsoftap state",
            "com.nforetek.bt.service",
        )

        assertTrue(report, report.contains("systemBluetoothState=ON"))
        assertTrue(report, report.contains("ecarxBluetoothService=true"))
        assertTrue(report, report.contains("wifiEnabledHint=true"))
        assertTrue(report, report.contains("nforetekBluetoothProcess=true"))
        assertTrue(report, !report.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(report, !report.contains("iPhone"))
    }

    @Test
    fun reportsMissingAdbAuthorizationWithoutRunningSystemQueries() {
        val report = GeelyHeadUnitWirelessDiagnostics.summarize(
            LocalAdb.Access.NOT_APPROVED,
            services = null,
            bluetooth = null,
            wifi = null,
            processes = null,
        )

        assertTrue(report, report.contains("headUnitAdb=NOT_APPROVED"))
        assertTrue(report, report.contains("systemQueries=not_run"))
    }
}
