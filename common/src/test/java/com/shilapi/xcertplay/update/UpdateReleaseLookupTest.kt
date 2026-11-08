package com.shilapi.xcertplay.update

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class UpdateReleaseLookupTest {
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
    fun fallsBackToThePublicFeedWhenTheApiIsRateLimited() {
        server.createContext("/api") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        server.createContext("/feed") { exchange ->
            val body = """
                <feed><entry>
                  <id>tag:github.com,2008:Repository/1449648913/v0.2.16-b261108-002</id>
                </entry></feed>
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val release = UpdateReleaseLookup.latest(
            installedVersion = "v0.2.16-b261108-001",
            userAgent = "DiPlay/test",
            releasesUrl = "$root/api",
            releasesFeedUrl = "$root/feed",
        )

        assertEquals("v0.2.16-b261108-002", requireNotNull(release).tagName)
    }
}
