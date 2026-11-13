package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPackageArchiveSelectionTest {
    @Test fun coreCarAndNetworkPackagesAreCollected() {
        assertTrue(SystemPackageArchive.shouldCollect("com.android.car", systemApp = true))
        assertTrue(SystemPackageArchive.shouldCollect("com.android.networkstack.tethering", systemApp = true))
        assertTrue(SystemPackageArchive.shouldCollect("com.android.settings", systemApp = true))
    }

    @Test fun namedVendorBridgesAreCollectedEvenWhenInstalledOutsideSystemPartition() {
        assertTrue(SystemPackageArchive.shouldCollect("com.reachauto.messagechannel", systemApp = false))
        assertTrue(SystemPackageArchive.shouldCollect("com.geely.gc.settingcenter", systemApp = false))
        assertFalse(SystemPackageArchive.shouldCollect("com.example.bluetooth", systemApp = false))
    }

    @Test fun messageChannelIsCapturedBeforeLargeSystemPackagesAndCurrentInventoryFits() {
        assertTrue(SystemPackageArchive.capturePriority("com.reachauto.messagechannel") <
            SystemPackageArchive.capturePriority("com.android.settings"))
        assertTrue(SystemPackageArchive.MAX_TOTAL_BYTES >= 295_113_655L)
    }
}
