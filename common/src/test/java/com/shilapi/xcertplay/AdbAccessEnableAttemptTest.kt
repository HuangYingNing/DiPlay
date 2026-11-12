package com.shilapi.xcertplay

import com.shilapi.xcertplay.adb.LocalAdb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbAccessEnableAttemptTest {
    @Test
    fun deniedSecureWriteFallsBackToSettingsAndLogsEveryStage() {
        val logs = mutableListOf<String>()
        var authorizationRequested = false
        val result = AdbAccessEnableAttempt.run(
            operations(
                permission = false,
                before = 0,
                write = { throw SecurityException("denied") },
                after = 0,
                portOpen = false,
                authorize = { authorizationRequested = true; LocalAdb.Access.READY },
            ),
        ) { stage, detail -> logs += "$stage $detail" }

        assertEquals(AdbAccessEnableAttempt.Outcome.SETTINGS_REQUIRED, result.outcome)
        assertFalse(authorizationRequested)
        assertTrue(logs.any { it.startsWith("permission ") })
        assertTrue(logs.any { it.contains("write-global-adb-enabled failed type=SecurityException") })
        assertTrue(logs.any { it == "probe-local-5555-result open=false" })
        assertTrue(logs.any { it.contains("skipped=local-port-closed") })
        assertTrue(logs.any { it.startsWith("finish outcome=SETTINGS_REQUIRED") })
    }

    @Test
    fun openLocalAdbRequestsAuthorizationAndReportsReady() {
        val result = AdbAccessEnableAttempt.run(
            operations(
                permission = true,
                before = 0,
                write = { true },
                after = 1,
                portOpen = true,
                authorize = { LocalAdb.Access.READY },
            ),
        ) { _, _ -> }

        assertEquals(AdbAccessEnableAttempt.Outcome.READY, result.outcome)
        assertEquals(1, result.enabledAfter)
        assertTrue(result.localPortOpen)
        assertEquals(LocalAdb.Access.READY, result.localAccess)
    }

    @Test
    fun unapprovedRequestNeedsSettingsFallbackAfterPromptTimeout() {
        val result = AdbAccessEnableAttempt.run(
            operations(
                permission = false,
                before = 1,
                write = { false },
                after = 1,
                portOpen = true,
                authorize = { LocalAdb.Access.NOT_APPROVED },
            ),
        ) { _, _ -> }

        assertEquals(AdbAccessEnableAttempt.Outcome.AUTHORIZATION_REQUIRED, result.outcome)
        assertTrue(AdbAccessEnableAttempt.requiresSettingsFallback(result.outcome))
        assertTrue(AdbAccessEnableAttempt.requiresSettingsFallback(AdbAccessEnableAttempt.Outcome.SETTINGS_REQUIRED))
        assertFalse(AdbAccessEnableAttempt.requiresSettingsFallback(AdbAccessEnableAttempt.Outcome.READY))
    }

    private fun operations(
        permission: Boolean,
        before: Int,
        write: () -> Boolean,
        after: Int,
        portOpen: Boolean,
        authorize: () -> LocalAdb.Access?,
    ): AdbAccessEnableAttempt.Operations {
        var reads = 0
        return AdbAccessEnableAttempt.Operations(
            hasSecureSettingsPermission = { permission },
            readEnabled = { if (reads++ == 0) before else after },
            writeEnabled = write,
            localPortOpen = { portOpen },
            requestAuthorization = authorize,
        )
    }
}
