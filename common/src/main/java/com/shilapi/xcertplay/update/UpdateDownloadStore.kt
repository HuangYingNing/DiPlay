package com.shilapi.xcertplay.update

import java.io.File
import java.security.MessageDigest

/** 按版本保存可续传的新版本下载文件。 */
internal object UpdateDownloadStore {
    fun directory(root: File, release: UpdateRelease): File = File(root, "version-${releaseKey(release.tagName)}")

    /** 仅保留当前可用版本的下载文件。 */
    fun retainVersion(root: File, release: UpdateRelease) {
        val current = directory(root, release).canonicalFile
        if (!root.exists()) root.mkdirs()
        root.listFiles()?.filter { it.canonicalFile != current }?.forEach { it.deleteRecursively() }
    }

    private fun releaseKey(tag: String): String = MessageDigest.getInstance("SHA-256")
        .digest(tag.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
