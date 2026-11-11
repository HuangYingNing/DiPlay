package com.shilapi.xcertplay.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

/** 采集 Wi-Fi、热点、定位及附近设备权限状态，不记录 SSID、密码或网络地址。 */
object WirelessNetworkInventory {
    fun report(context: Context): String {
        val app = context.applicationContext
        val failures = mutableListOf<String>()
        val wifi = runCatching { app.getSystemService(WifiManager::class.java) }
            .onFailure { failures += "wifiManager:${it.javaClass.simpleName}" }.getOrNull()
        val wifiEnabled = runCatching { wifi?.isWifiEnabled }
            .onFailure { failures += "wifiEnabled:${it.javaClass.simpleName}" }.getOrNull()
        val networks = mutableListOf<String>()
        val connectivity = runCatching { app.getSystemService(ConnectivityManager::class.java) }
            .onFailure { failures += "connectivityManager:${it.javaClass.simpleName}" }.getOrNull()
        runCatching {
            val activeConnectivity = connectivity ?: return@runCatching
            activeConnectivity.allNetworks.forEach { network ->
                val capabilities = activeConnectivity.getNetworkCapabilities(network)
                val links = activeConnectivity.getLinkProperties(network)
                val transports = buildList {
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
                }.ifEmpty { listOf("unknown") }.joinToString("+")
                networks += "$transports:${links?.interfaceName ?: "no-interface"}:addresses=${links?.linkAddresses?.size ?: 0}"
            }
        }.onFailure { failures += "networkList:${it.javaClass.simpleName}" }
        @Suppress("DEPRECATION")
        val station = runCatching { wifi?.connectionInfo }
            .onFailure { failures += "stationInfo:${it.javaClass.simpleName}" }.getOrNull()
        val ssidReadable = station?.ssid?.let { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID } ?: false
        val frequency = station?.frequency?.takeIf { it > 0 }?.toString() ?: "unknown"
        val locationEnabled = runCatching { app.getSystemService(LocationManager::class.java)?.isLocationEnabled }
            .onFailure { failures += "locationServices:${it.javaClass.simpleName}" }.getOrNull()
        val fineLocation = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseLocation = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else null
        val hotspotState = runCatching { CarHotspotStatus.isEnabled(app) }
            .onFailure { failures += "softApState:${it.javaClass.simpleName}" }.getOrNull()
        return "Wi-Fi inventory radio=${wifiEnabled ?: "unknown"} " +
            "locationServices=${locationEnabled ?: "unknown"} fineLocation=$fineLocation " +
            "coarseLocation=$coarseLocation nearbyWifi=${nearbyWifi?.toString() ?: "not_available"} " +
            "connectedSsidReadable=$ssidReadable frequencyMHz=$frequency " +
            "reportedSoftAp=${hotspotState ?: "unknown"} networks=${networks.size} " +
            "links=${networks.ifEmpty { listOf("none") }.joinToString(",")} " +
            "queryFailures=${failures.ifEmpty { listOf("none") }.joinToString(",")}"
    }
}
