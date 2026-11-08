package com.shilapi.xcertplay

import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AdbEnableDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clearLog() {
        File(context.filesDir, "logs/adb-enable.log").delete()
        File(context.filesDir, "logs/adb-enable-previous.log").delete()
    }

    @Test
    fun attemptLogIsIncludedWithoutAConnectedCarPlaySession() {
        AdbEnableDiagnostics.append(context, "attempt-1", "probe-local-5555", "open=false")

        val report = AdbEnableDiagnostics.report(context)

        assertTrue(report.contains("attempt=attempt-1"))
        assertTrue(report.contains("stage=probe-local-5555"))
        assertTrue(report.contains("open=false"))
        assertFalse(report.contains(context.filesDir.absolutePath))
    }
}
