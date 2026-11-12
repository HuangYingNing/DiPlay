package com.shilapi.xcertplay

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 验证系统包上传的请求内容、返回链接及服务端校验。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FilebinPackageUploadTest {
    @Test fun uploadsZipAndChecksServerDigest() {
        val bytes = "测试用系统包，不含车机文件".toByteArray()
        val file = kotlin.io.path.createTempFile("system-", ".zip").toFile().apply { writeBytes(bytes) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/") { exchange ->
                assertEquals("POST", exchange.requestMethod)
                assertEquals("application/zip", exchange.requestHeaders.getFirst("Content-Type"))
                assertArrayEquals(bytes, exchange.requestBody.readBytes())
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertEquals(digest, exchange.requestHeaders.getFirst("Content-SHA256"))
                val parts = exchange.requestURI.path.trim('/').split('/')
                val body = JSONObject().put("bin", JSONObject().put("id", parts[0]).put("expired_at", "2026-11-20T00:00:00Z"))
                    .put("file", JSONObject().put("filename", parts[1]).put("sha256", digest))
                    .toString().toByteArray()
                exchange.sendResponseHeaders(201, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val result = FilebinPackageUpload.upload(file, "http://127.0.0.1:${server.address.port}")
            assertEquals(file, result.localFile)
            assertTrue(result.downloadUrl.startsWith("http://127.0.0.1:${server.address.port}/diplayz"))
            assertTrue(result.downloadUrl.endsWith("/${file.name}"))
            assertEquals("2026-11-20T00:00:00Z", result.expiresAt)
        } finally {
            server.stop(0)
            file.delete()
        }
    }

    @Test fun rejectsMismatchedServerDigest() {
        val file = kotlin.io.path.createTempFile("system-", ".zip").toFile().apply { writeText("probe") }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                val parts = exchange.requestURI.path.trim('/').split('/')
                val body = JSONObject().put("bin", JSONObject().put("id", parts[0]))
                    .put("file", JSONObject().put("filename", parts[1]).put("sha256", "invalid"))
                    .toString().toByteArray()
                exchange.sendResponseHeaders(201, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val error = runCatching { FilebinPackageUpload.upload(file, "http://127.0.0.1:${server.address.port}") }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("SHA-256"))
        } finally {
            server.stop(0)
            file.delete()
        }
    }

    @Test fun uploadedPathsRemainInDiagnosticLog() {
        val folder = kotlin.io.path.createTempDirectory("system-upload-log-").toFile()
        try {
            val file = java.io.File(folder, "system-packages.log")
            val local = "/storage/emulated/0/Android/data/com.example/files/diagnostic-reports/DiPlay-System-1.zip"
            val remote = "https://filebin.net/diplayzz01z02z03/probe.zip"
            SessionLogFile(file).use {
                it.appendNow("SYSTEM_PACKAGE_UPLOAD completed local=$local url=$remote")
            }
            assertTrue(file.readText().contains(local))
            assertTrue(file.readText().contains(remote))
        } finally {
            folder.deleteRecursively()
        }
    }
}
