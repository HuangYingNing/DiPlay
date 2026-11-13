package com.shilapi.xcertplay.network

import java.io.Closeable
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong

/** 在指定网卡上被动统计 mDNS 包，不保存包内容、设备名称或网络地址。 */
internal class MdnsPacketObserver(
    private val interfaceName: String?,
) : Closeable {
    private val received = AtomicLong()
    private val peerPackets = AtomicLong()
    private val responses = AtomicLong()
    private val peerResponses = AtomicLong()
    private val queries = AtomicLong()
    private val carPlayControlHints = AtomicLong()
    private val peerCarPlayControlHints = AtomicLong()
    private val airPlayHints = AtomicLong()
    private val peerAirPlayHints = AtomicLong()
    @Volatile private var state = "not_started"
    @Volatile private var closed = false
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var worker: Thread? = null

    fun start() {
        if (worker != null || closed) return
        worker = Thread(::observe, "diplay-mdns-observer").apply {
            isDaemon = true
            start()
        }
    }

    fun snapshot(): String =
        "mDNS observer state=$state received=${received.get()} peerPackets=${peerPackets.get()} " +
            "responses=${responses.get()} peerResponses=${peerResponses.get()} queries=${queries.get()} " +
            "carPlayControlHints=${carPlayControlHints.get()} " +
            "peerCarPlayControlHints=${peerCarPlayControlHints.get()} airPlayHints=${airPlayHints.get()} " +
            "peerAirPlayHints=${peerAirPlayHints.get()}"

    fun hasPeerPackets(): Boolean = peerPackets.get() > 0L

    private fun observe() {
        var activeSocket: MulticastSocket? = null
        try {
            val name = interfaceName?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,15}")) }
                ?: throw IllegalArgumentException("interface_unavailable")
            val network = NetworkInterface.getByName(name) ?: throw IllegalStateException("interface_missing")
            val group = InetAddress.getByName(MDNS_GROUP)
            activeSocket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(MDNS_PORT))
                networkInterface = network
                soTimeout = RECEIVE_TIMEOUT_MILLIS
                joinGroup(InetSocketAddress(group, MDNS_PORT), network)
            }
            socket = activeSocket
            state = "joined"
            val bytes = ByteArray(MAX_PACKET_BYTES)
            while (!closed) {
                val packet = DatagramPacket(bytes, bytes.size)
                try {
                    activeSocket.receive(packet)
                    if (packet.length < DNS_HEADER_BYTES) continue
                    received.incrementAndGet()
                    val response = packet.data[2].toInt() and DNS_RESPONSE_MASK != 0
                    if (response) responses.incrementAndGet() else queries.incrementAndGet()
                    val fromPeer = !isLocalAddress(network, packet.address)
                    if (fromPeer) peerPackets.incrementAndGet()
                    if (fromPeer && response) peerResponses.incrementAndGet()
                    val hasCarPlayControl = containsAscii(packet.data, packet.length, CARPLAY_CONTROL_LABEL)
                    val hasAirPlay = containsAscii(packet.data, packet.length, AIRPLAY_LABEL)
                    if (hasCarPlayControl) {
                        carPlayControlHints.incrementAndGet()
                        if (fromPeer) peerCarPlayControlHints.incrementAndGet()
                    }
                    if (hasAirPlay) {
                        airPlayHints.incrementAndGet()
                        if (fromPeer) peerAirPlayHints.incrementAndGet()
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    Unit
                }
            }
        } catch (error: Exception) {
            if (!closed) state = "unavailable_${error.javaClass.simpleName}"
        } finally {
            runCatching { activeSocket?.leaveGroup(InetSocketAddress(InetAddress.getByName(MDNS_GROUP), MDNS_PORT), activeSocket.networkInterface) }
            runCatching { activeSocket?.close() }
            socket = null
            if (closed) state = "closed"
        }
    }

    private fun isLocalAddress(network: NetworkInterface, address: InetAddress): Boolean =
        network.interfaceAddresses.any { it.address == address }

    private fun containsAscii(packet: ByteArray, length: Int, label: ByteArray): Boolean {
        if (label.size > length) return false
        for (start in 0..length - label.size) {
            var matched = true
            for (offset in label.indices) {
                val actual = packet[start + offset].toInt() and 0xff
                val expected = label[offset].toInt() and 0xff
                if (actual != expected && actual != expected - LOWERCASE_DELTA) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        worker?.takeIf { it !== Thread.currentThread() }?.join(CLOSE_WAIT_MILLIS)
        if (state == "joined") state = "closed"
    }

    private companion object {
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val DNS_HEADER_BYTES = 12
        const val DNS_RESPONSE_MASK = 0x80
        const val MAX_PACKET_BYTES = 9000
        const val RECEIVE_TIMEOUT_MILLIS = 1_000
        const val CLOSE_WAIT_MILLIS = 1_200L
        const val LOWERCASE_DELTA = 32
        val CARPLAY_CONTROL_LABEL = "_carplay-ctrl".toByteArray(Charsets.US_ASCII)
        val AIRPLAY_LABEL = "_airplay".toByteArray(Charsets.US_ASCII)
    }
}
