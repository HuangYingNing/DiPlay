package com.shilapi.xcertplay.update

import java.net.URI

/** Parses GitHub's public releases feed without using the rate-limited REST API. */
internal object UpdateFeedCatalog {
    private const val DOWNLOAD_ROOT = "https://github.com/HuangYingNing/DiPlay/releases/download"
    private val ENTRY = Regex("<entry(?:\\s[^>]*)?>(.*?)</entry>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ID = Regex("<id(?:\\s[^>]*)?>tag:github\\.com,2008:[^<]*/([^/<]+)</id>", RegexOption.IGNORE_CASE)
    private val RELEASE_TAG = Regex("v?\\d+\\.\\d+\\.\\d+(?:(?:-build\\d{8}|-b\\d{6})-\\d{3})?")

    internal fun parse(xml: String): UpdateRelease? {
        for (entry in ENTRY.findAll(xml)) {
            val tag = ID.find(entry.groupValues[1])?.groupValues?.get(1) ?: continue
            if (!RELEASE_TAG.matches(tag)) continue
            val encodedTag = URI(null, null, tag, null).rawPath
            val apkName = "DiPlay-$tag.apk"
            val encodedApkName = URI(null, null, apkName, null).rawPath
            return UpdateRelease(
                tagName = tag,
                apkName = apkName,
                apkUrl = "$DOWNLOAD_ROOT/$encodedTag/$encodedApkName",
                checksumsUrl = "$DOWNLOAD_ROOT/$encodedTag/${UpdateCatalog.CHECKSUMS_FILE}",
            )
        }
        return null
    }
}
