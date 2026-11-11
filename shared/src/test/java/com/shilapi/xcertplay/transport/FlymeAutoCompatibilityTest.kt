package com.shilapi.xcertplay.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlymeAutoCompatibilityTest {
    @Test
    fun recognizesTheGeelyCloudBuildFromTheDiagnosticLog() {
        assertTrue(FlymeAutoCompatibility.isGeelyHeadUnit(
            manufacturer = "GeelyCloud",
            brand = "Geely",
            model = "E22H",
            product = "p145",
            fingerprint = "GeelyCloud/p145/p145:11",
        ))
    }

    @Test
    fun leavesOtherHeadUnitsOnTheStandardWirelessPath() {
        assertFalse(FlymeAutoCompatibility.isGeelyHeadUnit(
            manufacturer = "BYD",
            brand = "BYD",
            model = "DMI",
            product = "dmi",
            fingerprint = "byd/dmi/release",
        ))
    }
}
