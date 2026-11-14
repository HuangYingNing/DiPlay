package com.shilapi.xcertplay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class SystemPackageArchiveHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun onlyUploadedMatchingApkIsReused() {
        val directory = temporary.newFolder("packages")
        val apk = temporary.newFile("bridge.apk").apply { writeText("one") }
        val hash = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
            .joinToString("") { "%02x".format(it) }
        val archive = File(directory, "DiPlay-System-old.zip")
        val manifest = JSONObject().put("packages", org.json.JSONArray().put(
            JSONObject().put("package", "com.reachauto.messagechannel").put("files",
                org.json.JSONArray().put(JSONObject().put("status", "已收集")
                    .put("bytes", apk.length()).put("sha256", hash)))))
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
        }
        val log = temporary.newFile("system-packages.log")
        val current = File(directory, "DiPlay-System-current.zip")
        assertTrue(SystemPackageArchiveHistory.load(directory, current, log).isEmpty())
        log.writeText("SYSTEM_PACKAGE_UPLOAD completed local=${archive.absolutePath} url=https://example.test/file\n")
        val history = SystemPackageArchiveHistory.load(directory, current, log)
        assertEquals(hash, SystemPackageArchiveHistory.matchingDigest(history, "com.reachauto.messagechannel", apk))
        apk.writeText("two")
        assertNull(SystemPackageArchiveHistory.matchingDigest(history, "com.reachauto.messagechannel", apk))
    }

    @Test fun uploadedSystemFileIsReusedByPathAndContent() {
        val directory = temporary.newFolder("system-packages")
        val source = temporary.newFile("services.jar").apply { writeText("framework-v1") }
        val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { "%02x".format(it) }
        val archive = File(directory, "DiPlay-System-framework.zip")
        val manifest = JSONObject().put("systemFiles", org.json.JSONArray().put(
            JSONObject().put("path", source.absolutePath).put("status", "已收集")
                .put("bytes", source.length()).put("sha256", hash)))
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
        }
        val log = temporary.newFile("system-files.log").apply {
            writeText("SYSTEM_PACKAGE_UPLOAD completed local=${archive.absolutePath} url=https://example.test/file\n")
        }
        val history = SystemPackageArchiveHistory.load(directory, File(directory, "current.zip"), log)
        assertEquals(hash, SystemPackageArchiveHistory.matchingDigest(history, source.absolutePath, source))
        source.writeText("framework-v2")
        assertNull(SystemPackageArchiveHistory.matchingDigest(history, source.absolutePath, source))
    }
}
