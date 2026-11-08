package com.shilapi.xcertplay

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PasteDbDiagnosticUploadTest {
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

    @Test
    fun uploadsCompleteReportToFixedPasteWithApiKey() {
        var method = ""
        var apiKey = ""
        var body = ""
        server.createContext("/api/paste/diplay-geely-xingyuan") { exchange ->
            method = exchange.requestMethod
            apiKey = exchange.requestHeaders.getFirst("x-api-key")
            body = exchange.requestBody.bufferedReader().use { it.readText() }
            val response = """{"status":"success","paste_id":"mongo-id","custom_id":"diplay-geely-xingyuan"}"""
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        val receipt = PasteDbDiagnosticUpload.upload(
            appVersion = "0.2.16+abc123def456",
            fileName = "DiPlay-20261108-120000-123.txt",
            issueDescription = "Wireless connection failed",
            report = "first log line\nlast log line",
            apiKey = "test-api-key",
            endpoint = "$root/api/paste/diplay-geely-xingyuan",
            submittedAt = 0,
        )

        assertEquals("mongo-id", receipt)
        assertEquals("PUT", method)
        assertEquals("test-api-key", apiKey)
        assertTrue(body.contains("\"syntax\":\"text\""))
        assertTrue(body.contains("\"content\":\"DiPlay version: 0.2.16+abc123def456\\n"))
        assertTrue(body.contains("Wireless connection failed"))
        assertTrue(body.contains("first log line\\nlast log line"))
        assertTrue(body.contains("1970-01-01T00:00:00.000Z"))
    }

    @Test
    fun refusesUploadWithoutConfiguredApiKey() {
        try {
            PasteDbDiagnosticUpload.upload(
                appVersion = "0.2.16+abc123def456",
                fileName = "DiPlay-20261108-120000-123.txt",
                issueDescription = "",
                report = "log",
                apiKey = "",
                endpoint = "$root/api/paste/diplay-geely-xingyuan",
            )
            throw AssertionError("expected missing API key to fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("API key"))
        }
    }
}
