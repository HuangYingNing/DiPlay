package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A bounded, user-triggered attempt to enable standard Android ADB on the head unit. */
internal object AdbAccessEnableAttempt {
    enum class Outcome { READY, AUTHORIZATION_REQUIRED, SETTINGS_REQUIRED }

    data class Result(
        val outcome: Outcome,
        val enabledBefore: Int?,
        val enabledAfter: Int?,
        val localPortOpen: Boolean,
        val localAccess: LocalAdb.Access?,
    )

    internal data class Operations(
        val hasSecureSettingsPermission: () -> Boolean,
        val readEnabled: () -> Int?,
        val writeEnabled: () -> Boolean,
        val localPortOpen: () -> Boolean,
        val requestAuthorization: () -> LocalAdb.Access?,
    )

    fun run(context: Context): Result {
        val appContext = context.applicationContext
        val attemptId = System.currentTimeMillis().toString(36)
        val log = { stage: String, detail: String ->
            AdbEnableDiagnostics.append(appContext, attemptId, stage, detail)
        }
        log(
            "start",
            "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} product=${Build.PRODUCT} " +
                "sdk=${Build.VERSION.SDK_INT} uid=${android.os.Process.myUid()}",
        )
        return run(
            Operations(
                hasSecureSettingsPermission = {
                    appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                        PackageManager.PERMISSION_GRANTED
                },
                readEnabled = {
                    Settings.Global.getInt(appContext.contentResolver, Settings.Global.ADB_ENABLED, 0)
                },
                writeEnabled = {
                    Settings.Global.putInt(appContext.contentResolver, Settings.Global.ADB_ENABLED, 1)
                },
                localPortOpen = {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", 5555), 600)
                        true
                    }
                },
                requestAuthorization = {
                    LocalAdb(AdbKeys.load(appContext)).use { it.connect(mayAsk = true) }
                },
            ),
            log,
        )
    }

    internal fun run(operations: Operations, log: (String, String) -> Unit): Result {
        val securePermission = runStep(log, "permission", operations.hasSecureSettingsPermission)
        log("permission-result", "writeSecureSettings=${securePermission == true}")
        val enabledBefore = runStep(log, "read-before", operations.readEnabled)
        log("read-before-result", "adbEnabled=${enabledBefore ?: "unknown"}")
        val writeAccepted = runStep(log, "write-global-adb-enabled", operations.writeEnabled)
        log("write-result", "accepted=${writeAccepted == true}")
        val enabledAfter = runStep(log, "read-after", operations.readEnabled)
        log("read-after-result", "adbEnabled=${enabledAfter ?: "unknown"}")
        val portOpen = runStep(log, "probe-local-5555", operations.localPortOpen) == true
        log("probe-local-5555-result", "open=$portOpen")
        val access = if (portOpen) {
            runStep(log, "request-diplay-authorization", operations.requestAuthorization)
        } else {
            log("request-diplay-authorization", "skipped=local-port-closed")
            null
        }
        log("authorization-result", "access=${access?.name ?: "not-attempted"}")
        val outcome = when (access) {
            LocalAdb.Access.READY -> Outcome.READY
            LocalAdb.Access.NOT_APPROVED -> Outcome.AUTHORIZATION_REQUIRED
            else -> Outcome.SETTINGS_REQUIRED
        }
        log(
            "finish",
            "outcome=$outcome enabledBefore=${enabledBefore ?: "unknown"} " +
                "enabledAfter=${enabledAfter ?: "unknown"} localPortOpen=$portOpen",
        )
        return Result(outcome, enabledBefore, enabledAfter, portOpen, access)
    }

    private fun <T> runStep(log: (String, String) -> Unit, stage: String, block: () -> T): T? {
        val started = System.nanoTime()
        log(stage, "started")
        return try {
            block().also {
                log(stage, "completed elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            }
        } catch (failure: Throwable) {
            log(
                stage,
                "failed type=${failure.javaClass.simpleName} elapsedMs=${(System.nanoTime() - started) / 1_000_000}",
            )
            null
        }
    }
}

/** Stored separately so a failed ADB attempt is available even without a CarPlay session. */
internal object AdbEnableDiagnostics {
    private const val CURRENT = "adb-enable.log"
    private const val PREVIOUS = "adb-enable-previous.log"
    private const val MAX_BYTES = 128 * 1024L
    private val lock = Any()

    fun append(context: Context, attemptId: String, stage: String, detail: String) = synchronized(lock) {
        val safe = DiagnosticRedactor.redact("ADB allow attempt=$attemptId stage=$stage $detail") ?: return@synchronized
        val directory = File(context.filesDir, "logs")
        val current = File(directory, CURRENT)
        runCatching {
            directory.mkdirs()
            if (current.length() >= MAX_BYTES) {
                val previous = File(directory, PREVIOUS)
                previous.delete()
                current.renameTo(previous)
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            current.appendText("$timestamp  $safe\n")
            android.util.Log.i("DiPlay-ADB-Allow", safe)
        }
        Unit
    }

    fun report(context: Context): String = synchronized(lock) {
        listOf(PREVIOUS, CURRENT).mapNotNull { name ->
            File(File(context.filesDir, "logs"), name).takeIf(File::isFile)?.let { file ->
                "--- $name ---\n" + file.useLines { lines ->
                    lines.mapNotNull(DiagnosticRedactor::redact).joinToString("\n")
                }
            }
        }.joinToString("\n")
    }
}
