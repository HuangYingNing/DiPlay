package com.shilapi.xcertplay.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundUpdatePolicyTest {
    @Test fun anInactiveStoredSessionIsBusy() = assertTrue(BackgroundUpdatePolicy.connectionBusy(true, false))
    @Test fun aStableActiveSessionAllowsTheCheck() = assertFalse(BackgroundUpdatePolicy.connectionBusy(true, true))
    @Test fun havingNoSessionDoesNotBlockTheCheck() = assertFalse(BackgroundUpdatePolicy.connectionBusy(false, false))
}
