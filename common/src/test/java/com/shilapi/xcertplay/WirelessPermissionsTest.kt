package com.shilapi.xcertplay

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessPermissionsTest {
    @Test fun wifiAndBluetoothOperationsRequestVersionSpecificRuntimePermissions() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ),
            WirelessPermissions.required(37),
        )
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(29),
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(31),
        )
        assertEquals(
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            WirelessPermissions.required(30),
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ),
            WirelessPermissions.required(33),
        )
    }
}
