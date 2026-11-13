package com.shilapi.xcertplay

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 从车机本地旧系统包读取摘要，避免再次采集未变化的 APK。 */
internal object SystemPackageArchiveHistory {
    private const val MAX_ARCHIVES = 32
    private const val MAX_MANIFEST_BYTES = 1024 * 1024L
    private val validDigest = Regex("[0-9a-fA-F]{64}")
    private val uploadedArchive = Regex("SYSTEM_PACKAGE_UPLOAD completed local=([^\\s]+)")

    data class Digest(val bytes: Long, val sha256: String)

    fun load(directory: File, current: File, uploadLog: File): Map<String, Set<Digest>> {
        val found = mutableMapOf<String, MutableSet<Digest>>()
        val uploadedNames = runCatching {
            uploadLog.useLines { lines -> lines.mapNotNull { line ->
                uploadedArchive.find(line)?.groupValues?.getOrNull(1)?.substringAfterLast('/')
            }.filter { it.startsWith("DiPlay-System-") && it.endsWith(".zip") }.toSet() }
        }.getOrDefault(emptySet())
        directory.listFiles().orEmpty().asSequence()
            .filter { it != current && it.isFile && it.name in uploadedNames }
            .sortedByDescending(File::lastModified).take(MAX_ARCHIVES).forEach { archive ->
                runCatching {
                    ZipFile(archive).use { zip ->
                        val entry = zip.getEntry("manifest.json") ?: return@use
                        if (entry.size !in 1L..MAX_MANIFEST_BYTES) return@use
                        val manifest = JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                        val packages = manifest.optJSONArray("packages") ?: return@use
                        for (index in 0 until packages.length()) {
                            val item = packages.optJSONObject(index) ?: continue
                            val packageName = item.optString("package")
                            val files = item.optJSONArray("files") ?: continue
                            for (fileIndex in 0 until files.length()) {
                                val file = files.optJSONObject(fileIndex) ?: continue
                                if (file.optString("status") != "已收集") continue
                                val hash = file.optString("sha256")
                                val bytes = file.optLong("bytes")
                                if (bytes > 0L && validDigest.matches(hash))
                                    found.getOrPut(packageName) { mutableSetOf() }.add(Digest(bytes, hash.lowercase()))
                            }
                        }
                    }
                }
            }
        return found
    }

    fun matchingDigest(history: Map<String, Set<Digest>>, packageName: String, file: File): String? {
        val candidates = history[packageName].orEmpty().filter { it.bytes == file.length() }
        if (candidates.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex.takeIf { hash -> candidates.any { it.sha256 == hash } }
    }
}
