package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateFeedCatalogTest {
    @Test
    fun buildsTrustedReleaseAssetUrlsFromTheLatestFeedEntry() {
        val release = UpdateFeedCatalog.parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>tag:github.com,2008:Repository/1449648913/v0.2.16-b261108-002</id>
                <title>DiPlay v0.2.16-b261108-002</title>
              </entry>
            </feed>
            """.trimIndent(),
        )

        requireNotNull(release)
        assertEquals("v0.2.16-b261108-002", release.tagName)
        assertEquals("DiPlay-v0.2.16-b261108-002.apk", release.apkName)
        assertEquals(
            "https://github.com/HuangYingNing/DiPlay/releases/download/v0.2.16-b261108-002/DiPlay-v0.2.16-b261108-002.apk",
            release.apkUrl,
        )
        assertEquals(
            "https://github.com/HuangYingNing/DiPlay/releases/download/v0.2.16-b261108-002/SHA256SUMS.txt",
            release.checksumsUrl,
        )
    }

    @Test
    fun ignoresAnUntrustedTagInsteadOfPuttingItInAUrl() {
        val feed = "<feed><entry><id>tag:github.com,2008:Repository/1/../../outside</id></entry></feed>"
        assertNull(UpdateFeedCatalog.parse(feed))
    }
}
