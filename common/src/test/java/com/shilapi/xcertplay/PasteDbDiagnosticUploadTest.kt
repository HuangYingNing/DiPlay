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
        var keyRequests = 0
        var method = ""
        var apiKey = ""
        var body = ""
        server.createContext("/key.htm") { exchange ->
            keyRequests++
            val response = ("pdb_" + "a".repeat(64) + "\n").toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
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

        val receipt = PasteDbDiagnosticUpload.uploadWithApiKey(
            appVersion = "v0.2.16-b261108-002",
            sourceRevision = "abc123def456",
            fileName = "DiPlay-20261108-120000-123.txt",
            issueDescription = "Wireless connection failed",
            report = "first log line\nlast log line",
            endpoint = "$root/api/paste/diplay-geely-xingyuan",
            submittedAt = 0,
            apiKey = "pdb_${"a".repeat(64)}",
        )

        assertEquals("mongo-id", receipt)
        assertEquals("PUT", method)
        assertTrue(apiKey.matches(Regex("pdb_[0-9a-f]{64}")))
        assertEquals(0, keyRequests)
        assertTrue(body.contains("\"syntax\":\"text\""))
        assertTrue(body.contains("\"content\":\"DiPlay version: v0.2.16-b261108-002\\n"))
        assertTrue(body.contains("Source commit: abc123def456\\n"))
        assertTrue(body.contains("Wireless connection failed"))
        assertTrue(body.contains("first log line\\nlast log line"))
        assertTrue(body.contains("1970-01-01T00:00:00.000Z"))
    }

}
