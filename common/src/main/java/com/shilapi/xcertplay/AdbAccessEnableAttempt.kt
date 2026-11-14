package com.shilapi.xcertplay

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.UserManager
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

    internal fun requiresSettingsFallback(outcome: Outcome): Boolean =
        outcome == Outcome.AUTHORIZATION_REQUIRED || outcome == Outcome.SETTINGS_REQUIRED

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
        logEnvironment(appContext, attemptId, "before")
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
                    LocalAdb(AdbKeys.load(appContext), onHandshakeEvent = { event ->
                        log("adb-protocol", event)
                    }).use { it.connect(mayAsk = true) }
                },
            ),
            log,
        )
    }

    fun logEnvironment(context: Context, attemptId: String, stage: String) {
        val appContext = context.applicationContext
        val global = appContext.contentResolver
        fun setting(name: String): String = runCatching {
            Settings.Global.getInt(global, name, -1).toString()
        }.getOrDefault("unknown")
        val user = appContext.getSystemService(Context.USER_SERVICE) as? UserManager
        AdbEnableDiagnostics.append(appContext, attemptId, "$stage-policy",
            "adbEnabled=${setting(Settings.Global.ADB_ENABLED)} " +
                "adbWifiEnabled=${setting("adb_wifi_enabled")} " +
                "developerOptions=${setting(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)} " +
                "deviceProvisioned=${setting(Settings.Global.DEVICE_PROVISIONED)} " +
                "adbEnabledReadMayBeMasked=true " +
                "adminUser=${runCatching { user?.isAdminUser }.getOrNull() ?: "unknown"} " +
                "debuggingRestricted=${runCatching {
                    user?.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES)
                }.getOrNull() ?: "unknown"} uid=${android.os.Process.myUid()}")
        val properties = listOf("init.svc.adbd", "ro.adb.secure", "ro.debuggable", "ro.build.type",
            "service.adb.tcp.port", "persist.adb.tcp.port", "service.adb.tls.port",
            "persist.sys.usb.config", "sys.usb.config", "sys.usb.state")
        AdbEnableDiagnostics.append(appContext, attemptId, "$stage-properties",
            properties.joinToString(" ") { name -> "$name=${readSystemProperty(name)}" })
        val usb = runCatching {
            appContext.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        }.getOrNull()
        val tcpPort = readSystemProperty("service.adb.tcp.port")
        val usbConnected = usb?.getBooleanExtra("connected", false)
        AdbEnableDiagnostics.append(appContext, attemptId, "$stage-usb",
            if (usb == null) "state=unavailable" else
                "connected=$usbConnected " +
                    "configured=${usb.getBooleanExtra("configured", false)} " +
                    "adbFunction=${usb.getBooleanExtra("adb", false)}")
        // 星愿 SystemUI 在非 TCP ADB 模式下监听 USB 断开；只记录可能导致弹窗关闭的条件。
        AdbEnableDiagnostics.append(appContext, attemptId, "$stage-dialog-condition",
            "tcpPort=$tcpPort usbConnected=${usbConnected ?: "unknown"} " +
                "possibleUsbDisconnectDismissal=${(tcpPort == "unset" || tcpPort.toIntOrNull() == 0) && usbConnected == false}")
        logConfirmationRoute(appContext, attemptId)
    }

    private fun logConfirmationRoute(context: Context, attemptId: String) {
        val resources = Resources.getSystem()
        for (name in listOf("config_customAdbPublicKeyConfirmationComponent",
            "config_customAdbPublicKeyConfirmationSecondaryUserComponent")) {
            val id = runCatching { resources.getIdentifier(name, "string", "android") }.getOrDefault(0)
            val route = if (id == 0) null else runCatching { resources.getString(id) }.getOrNull()
            val component = route?.let(ComponentName::unflattenFromString)
            val activity = component?.let { target ->
                runCatching {
                    context.packageManager.getActivityInfo(target, PackageManager.MATCH_DISABLED_COMPONENTS)
                }.getOrNull()
            }
            val service = component?.let { target ->
                runCatching {
                    context.packageManager.getServiceInfo(target, PackageManager.MATCH_DISABLED_COMPONENTS)
                }.getOrNull()
            }
            val status = when {
                activity != null -> "activity enabled=${activity.enabled} exported=${activity.exported} " +
                    "permission=${activity.permission ?: "none"}"
                service != null -> "service enabled=${service.enabled} exported=${service.exported} " +
                    "permission=${service.permission ?: "none"}"
                component != null -> "component-not-visible"
                else -> "resource-unavailable"
            }
            AdbEnableDiagnostics.append(context, attemptId, "confirmation-route",
                "resource=$name target=${component?.flattenToShortString() ?: "none"} $status")
        }
        val fallback = ComponentName("com.android.systemui", "com.android.systemui.usb.UsbDebuggingActivity")
        val fallbackInfo = runCatching {
            context.packageManager.getActivityInfo(fallback, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrNull()
        AdbEnableDiagnostics.append(context, attemptId, "systemui-confirmation",
            "component=${fallback.flattenToShortString()} visible=${fallbackInfo != null} " +
                "enabled=${fallbackInfo?.enabled ?: "unknown"} exported=${fallbackInfo?.exported ?: "unknown"} " +
                "permission=${fallbackInfo?.permission ?: "unknown"}")
    }

    private fun readSystemProperty(name: String): String {
        val process = runCatching { ProcessBuilder("getprop", name).start() }.getOrNull() ?: return "unavailable"
        return try {
            if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                "timeout"
            } else {
                process.inputStream.bufferedReader().use { it.readLine()?.take(16) }.orEmpty().ifEmpty { "unset" }
            }
        } catch (_: Exception) {
            "unavailable"
        } finally {
            process.destroy()
        }
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
