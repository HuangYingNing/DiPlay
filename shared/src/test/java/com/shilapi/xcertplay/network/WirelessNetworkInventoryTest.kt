package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WirelessNetworkInventoryTest {
    @Test
    fun reportContainsPermissionAndRadioStateWithoutNetworkSecrets() {
        val report = WirelessNetworkInventory.report(RuntimeEnvironment.getApplication())

        assertTrue(report, report.contains("fineLocation="))
        assertTrue(report, report.contains("reportedSoftAp="))
        assertTrue(report, report.contains("networks="))
        assertTrue(report, report.contains("interfaces="))
        assertTrue(report, report.contains("validated="))
        assertFalse(report, report.contains("password"))
        assertFalse(report, report.contains("ssid=\""))
    }
}
