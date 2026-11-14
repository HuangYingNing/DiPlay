package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydVehicleCapabilityProbe
import com.shilapi.xcertplay.hud.BydVehicleProbeOutcome

/** Test seam for the blocking ADB work launched by the host settings page. */
internal interface BydVehicleSettingsBackend {
    fun check(context: Context, mayAsk: Boolean): BydAdbAccess.Status
    fun checkState(context: Context, mayAsk: Boolean): BydAdbAccess.State
    fun probe(context: Context, persist: Boolean): BydVehicleProbeOutcome
    fun execute(name: String, block: () -> Unit)
}

internal object AndroidBydVehicleSettingsBackend : BydVehicleSettingsBackend {
    override fun check(context: Context, mayAsk: Boolean) =
        BydAdbAccess.check(context, mayAsk, diagnosticCallback(context, mayAsk, "check"))

    override fun checkState(context: Context, mayAsk: Boolean) =
        BydAdbAccess.checkState(context, mayAsk, diagnosticCallback(context, mayAsk, "check-state"))

    private fun diagnosticCallback(context: Context, mayAsk: Boolean, operation: String): ((String) -> Unit)? {
        if (!mayAsk) return null
        val attemptId = System.currentTimeMillis().toString(36)
        AdbEnableDiagnostics.append(context, attemptId, "operation", "name=$operation")
        AdbAccessEnableAttempt.logEnvironment(context, attemptId, "before")
        return { event -> AdbEnableDiagnostics.append(context, attemptId, "adb-protocol", event) }
    }

    override fun probe(context: Context, persist: Boolean) =
        BydVehicleCapabilityProbe.probe(context, persist)

    override fun execute(name: String, block: () -> Unit) {
        Thread(block, name).start()
    }
}

/** Replaced only by Robolectric tests; production always uses [AndroidBydVehicleSettingsBackend]. */
internal object BydVehicleSettingsBackendProvider {
    @Volatile var current: BydVehicleSettingsBackend = AndroidBydVehicleSettingsBackend

    fun reset() {
        current = AndroidBydVehicleSettingsBackend
    }
}
