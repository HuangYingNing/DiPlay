package com.shilapi.xcertplay.update

import java.io.IOException
import java.net.URL
import java.net.URLConnection

internal object UpdateReleaseLookup {
    fun latest(
        installedVersion: String,
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
        releasesUrl: String = UpdateClient.RELEASES_URL,
        releasesFeedUrl: String = UpdateClient.RELEASES_FEED_URL,
    ): UpdateRelease? {
        val release = try {
            val json = UpdateClient.fetchText(
                releasesUrl,
                "application/vnd.github+json",
                userAgent,
                openConnection,
            )
            UpdateCatalog.parse(json)
        } catch (apiFailure: IOException) {
            try {
                val feed = UpdateClient.fetchText(
                    releasesFeedUrl,
                    "application/atom+xml",
                    userAgent,
                    openConnection,
                )
                UpdateFeedCatalog.parse(feed)
            } catch (feedFailure: IOException) {
                feedFailure.addSuppressed(apiFailure)
                throw feedFailure
            }
        } ?: return null
        return release.takeIf { UpdateVersion.isNewer(it.tagName, installedVersion) }
    }
}
