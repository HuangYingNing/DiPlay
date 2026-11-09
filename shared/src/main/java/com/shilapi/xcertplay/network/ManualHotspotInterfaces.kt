package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

internal class ManualHotspotInterfaces(
    private val context: Context,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val publicTethering = if (Build.VERSION.SDK_INT >= 36) PublicTethering(context) else null
    // carlito | One reader belongs to this manager; closing it never changes the factory AP.
    private val vendor = EcarxHotspotReader(context, onDiagnostic)
    private var lastLegacyDiagnostic: String? = null

    fun sample(): HotspotNetworkSnapshot {
        val vendorState = vendor.snapshot()
        val observed = publicTethering?.interfaces ?: legacyApInterfaces()
        val before = runCatching { connectivity?.activeNetwork }
        val upstreams = runCatching {
            checkNotNull(connectivity)
            connectivity.allNetworks.mapNotNull { network ->
                val caps = checkNotNull(connectivity.getNetworkCapabilities(network))
                val links = checkNotNull(connectivity.getLinkProperties(network))
                links.interfaceName?.takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            }.toSet()
        }.getOrNull()
        val defaultName = runCatching {
            before.getOrNull()?.let { connectivity?.getLinkProperties(it)?.interfaceName }
        }.getOrNull()
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).mapNotNull { iface ->
                runCatching {
                    if (iface.isLoopback) null else HotspotInterfaceSnapshot(
                        iface.name, iface.index, iface.isUp, Collections.list(iface.inetAddresses),
                        wirelessInterfaceName(iface.name) || File("/sys/class/net/${iface.name}/wireless").isDirectory,
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        val ap = apInterfaces(observed, interfaces, CarHotspotStatus.androidEnabled(context), upstreams, null)
        val after = runCatching { connectivity?.activeNetwork }
        return HotspotNetworkSnapshot(
            interfaces, ap, upstreams, defaultName,
            consistent = before.isSuccess && after.isSuccess && before.getOrNull() == after.getOrNull(),
            // carlito | Only a verified factory route may override Android AP off.
            apEnabled = CarHotspotStatus.androidEnabled(context),
            vendorHostAddresses = vendorState.routedHosts,
        )
    }

    fun vendorDiagnosticSnapshot(): String = vendor.diagnosticSnapshot()

    private fun apInterfaces(
        observed: Set<String>?,
        interfaces: List<HotspotInterfaceSnapshot>,
        apEnabled: Boolean?,
        upstreams: Set<String>?,
        defaultInterface: String?,
    ): Set<String>? {
        if (observed?.isNotEmpty() != false || apEnabled != true) return observed
        val candidates = interfaces.filter { iface ->
            iface.up && iface.index > 0 && !iface.wireless && iface.name !in upstreams.orEmpty() &&
                iface.name != defaultInterface && iface.name !in EXCLUDED_INTERFACES &&
                !mobileDataInterfaceName(iface.name) && iface.addresses.any(::publishesHotspotAddress)
        }.map { it.name }
        return when {
            DEFAULT_CAR_HOTSPOT_INTERFACE in candidates -> setOf(DEFAULT_CAR_HOTSPOT_INTERFACE)
            candidates.isNotEmpty() -> candidates.toSet()
            else -> observed
        }
    }

    private companion object {
        const val DEFAULT_CAR_HOTSPOT_INTERFACE = "eth0.3"
        val EXCLUDED_INTERFACES = setOf("dummy0", "sit0", "bond0", "ip6tnl0", "tunl0", "rmnet0")
    }

    // 旧平台只使用允许读取的结果；接口归属读不到时保持 unknown，不放宽普通网卡资格。
    @SuppressLint("PrivateApi")
    private fun legacyApInterfaces(): Set<String>? = runCatching {
        val manager = checkNotNull(connectivity)
        val tethered = ConnectivityManager::class.java.getMethod("getTetheredIfaces")
            .invoke(manager) as Array<*>
        val regexes = ConnectivityManager::class.java.getMethod("getTetherableWifiRegexs")
            .invoke(manager) as Array<*>
        val names = tethered.filterIsInstance<String>()
        val patterns = regexes.filterIsInstance<String>()
        val ap = legacyHotspotInterfaces(names, patterns)
        val diagnostic = "legacy hotspot ownership=${if (ap == null) "unobservable" else "observed"} " +
            "tethered=$names wifiRegexes=$patterns matched=$ap"
        if (diagnostic != lastLegacyDiagnostic) {
            lastLegacyDiagnostic = diagnostic
            onDiagnostic(diagnostic)
        }
        ap
    }.getOrNull()

    override fun close() {
        publicTethering?.close()
        vendor.close()
    }

    @RequiresApi(36)
    private class PublicTethering(context: Context) : Closeable {
        @Volatile var interfaces: Set<String>? = null
            private set
        private val manager = context.getSystemService(TetheringManager::class.java)
        private val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                this@PublicTethering.interfaces = interfaces.filter { it.type == TetheringManager.TETHERING_WIFI }
                    .map { it.getInterface() }.toSet()
            }
        }
        private val registered = runCatching {
            checkNotNull(manager).registerTetheringEventCallback(context.mainExecutor, callback)
        }.isSuccess

        override fun close() {
            if (registered) runCatching { manager?.unregisterTetheringEventCallback(callback) }
        }
    }
}

internal fun legacyHotspotInterfaces(tethered: List<String>, wifiRegexes: List<String>): Set<String>? {
    if (wifiRegexes.isEmpty()) return null
    val patterns = wifiRegexes.map(::Regex)
    val matched = tethered.filter { name -> patterns.any { it.matches(name) } }.toSet()
    // 厂商可能动态使用 wlan0，而静态配置仍写 softap0；匹配失败不能证明没有热点。
    return matched.takeUnless { tethered.isNotEmpty() && it.isEmpty() }
}

internal fun wirelessInterfaceName(name: String): Boolean =
    name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") ||
        name.contains("softap", ignoreCase = true) || name.startsWith("wifi") || name.startsWith("p2p")

internal fun publishesHotspotAddress(address: InetAddress): Boolean = when (address) {
    is Inet4Address -> !address.isLoopbackAddress && !address.isAnyLocalAddress &&
        !address.isLinkLocalAddress && !address.isMulticastAddress
    is Inet6Address -> address.isLinkLocalAddress
    else -> false
}

internal fun mobileDataInterfaceName(name: String): Boolean =
    name.startsWith("rmnet") || name.startsWith("r_rmnet") || name.startsWith("ccmni")
