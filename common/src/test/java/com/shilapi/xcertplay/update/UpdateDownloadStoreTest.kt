package com.shilapi.xcertplay.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun retainingNewReleaseRemovesPreviousReleaseFiles() {
        val root = File(temporaryFolder.root, "update-downloads")
        val old = release("v0.2.22-b261111-001")
        val current = release("v0.2.22-b261111-002")
        val oldPartial = File(UpdateDownloadStore.directory(root, old), old.apkName).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val currentPartial = File(UpdateDownloadStore.directory(root, current), current.apkName).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }

        UpdateDownloadStore.retainVersion(root, current)

        assertFalse(oldPartial.exists())
        assertTrue(currentPartial.exists())
    }

    @Test
    fun sameReleaseRetainsItsPartialDownload() {
        val root = File(temporaryFolder.root, "update-downloads")
        val release = release("v0.2.22-b261111-001")
        val partial = File(UpdateDownloadStore.directory(root, release), release.apkName).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        UpdateDownloadStore.retainVersion(root, release)

        assertTrue(partial.exists())
    }

    private fun release(tag: String) = UpdateRelease(
        tagName = tag,
        apkName = "DiPlay-$tag.apk",
        apkUrl = "https://github.com/HuangYingNing/DiPlay/releases/download/$tag/app.apk",
        checksumsUrl = "https://github.com/HuangYingNing/DiPlay/releases/download/$tag/SHA256SUMS.txt",
    )
}
