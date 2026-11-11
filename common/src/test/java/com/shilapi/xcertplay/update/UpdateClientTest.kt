package com.shilapi.xcertplay.update

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var root: String

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        root = "http://${server.address.hostString}:${server.address.port}"
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    // Fork installations must not switch to the original project's APK/update channel.
    @Test
    fun updatesUseTheForkReleaseChannel() {
        assertEquals("https://api.github.com/repos/HuangYingNing/DiPlay/releases?per_page=3", UpdateClient.RELEASES_URL)
        assertEquals("https://github.com/HuangYingNing/DiPlay/releases.atom", UpdateClient.RELEASES_FEED_URL)
    }

    @Test
    fun fetchTextReturnsTheBody() {
        val body = "[{\"tag_name\": \"v0.2.14\"}]"
        server.createContext("/releases") { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        assertEquals(body, UpdateClient.fetchText("$root/releases", "application/vnd.github+json", "DiPlay/test"))
    }

    @Test
    fun fetchTextUsesTheSuppliedNetworkConnectionFactory() {
        server.createContext("/selected-network") { exchange ->
            val bytes = "[]".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val selected = AtomicBoolean()

        UpdateClient.fetchText("$root/selected-network", null, "DiPlay/test") { url ->
            selected.set(true)
            url.openConnection()
        }

        assertTrue(selected.get())
    }

    @Test
    fun fetchTextFailsOnHttpErrors() {
        server.createContext("/releases") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        try {
            UpdateClient.fetchText("$root/releases", null, "DiPlay/test")
            throw AssertionError("expected an IOException")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("403"))
        }
    }

    @Test
    fun downloadWritesTheFileAndReportsProgress() {
        val payload = ByteArray(200 * 1024) { (it % 251).toByte() }
        server.createContext("/apk") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        val destination = File(temporaryFolder.root, "update/DiPlay.apk")
        val reports = mutableListOf<Pair<Long, Long?>>()
        UpdateClient.download("$root/apk", destination) { written, total -> reports.add(written to total) }
        assertEquals(payload.toList(), destination.readBytes().toList())
        assertEquals(payload.size.toLong(), reports.last().first)
        assertEquals(payload.size.toLong(), reports.last().second)
    }

    @Test
    fun failedDownloadsKeepPartialBytesForResuming() {
        val payload = ByteArray(200 * 1024) { (it % 239).toByte() }
        server.createContext("/apk") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        val destination = File(temporaryFolder.root, "update/DiPlay.apk")
        try {
            UpdateClient.download("$root/apk", destination) { written, _ ->
                if (written > 0L) throw java.io.IOException("simulated interrupted transfer")
            }
            throw AssertionError("expected an IOException")
        } catch (expected: java.io.IOException) {
            assertEquals(64L * 1024, destination.length())
        }
    }

    @Test
    fun downloadResumesFromTheExistingFileLength() {
        val payload = ByteArray(128 * 1024) { (it % 239).toByte() }
        val offset = 17 * 1024
        val requestedRange = AtomicBoolean()
        server.createContext("/resumable") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            requestedRange.set(range == "bytes=$offset-")
            val remaining = payload.copyOfRange(offset, payload.size)
            exchange.responseHeaders.add("Content-Range", "bytes $offset-${payload.size - 1}/${payload.size}")
            exchange.sendResponseHeaders(206, remaining.size.toLong())
            exchange.responseBody.use { it.write(remaining) }
        }
        val destination = File(temporaryFolder.root, "update/DiPlay.apk").apply {
            parentFile!!.mkdirs()
            writeBytes(payload.copyOfRange(0, offset))
        }

        UpdateClient.download("$root/resumable", destination) { _, _ -> }

        assertTrue(requestedRange.get())
        assertEquals(payload.toList(), destination.readBytes().toList())
    }

    @Test
    fun downloadRestartsWhenServerIgnoresRange() {
        val payload = "complete APK".toByteArray()
        val requestedRange = AtomicBoolean()
        server.createContext("/ignore-range") { exchange ->
            requestedRange.set(exchange.requestHeaders.getFirst("Range") != null)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        val destination = File(temporaryFolder.root, "update/DiPlay.apk").apply {
            parentFile!!.mkdirs()
            writeBytes("stale partial".toByteArray())
        }

        UpdateClient.download("$root/ignore-range", destination) { _, _ -> }

        assertTrue(requestedRange.get())
        assertEquals(payload.toList(), destination.readBytes().toList())
    }
    private fun assertRefusedAsTooLarge(path: String, length: Long) {
        val chunk = ByteArray(1024 * 1024)
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, length)
            exchange.responseBody.use { out -> repeat(70) { out.write(chunk) } }
        }
        val destination = File(temporaryFolder.root, "update/DiPlay.apk")
        try {
            UpdateClient.download("$root$path", destination) { _, _ -> }
            throw AssertionError("expected an IOException, got ${destination.length()} bytes")
        } catch (expected: java.io.IOException) {
            assertTrue(!destination.exists())
        }
    }

    @Test
    fun downloadRefusesABodyBeyondTheLimitWhenItsLengthIsKnown() = assertRefusedAsTooLarge("/big", 70L * 1024 * 1024)

    @Test
    fun downloadRefusesABodyBeyondTheLimitWhenItsLengthIsNotKnown() = assertRefusedAsTooLarge("/chunked", 0L)
}
