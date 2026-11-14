package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 分批收集车机可读取的系统程序与配置；不读取应用私有数据或受保护的系统分区。 */
internal object SystemPackageArchive {
    internal const val MAX_TOTAL_BYTES = 384L * 1024 * 1024
    private const val MAX_FILE_BYTES = 192L * 1024 * 1024
    private const val MAX_SYSTEM_FILE_BYTES = 192L * 1024 * 1024
    private val relatedBinaryNames = Regex("adb|bluetooth|wifi|wificond|hostapd|supplicant|usb|carplay|flyme|geely|reachauto|dilink")
    private val keywords = listOf("flyme", "meizu", "geely", "ecarx", "autolink", "carplay", "bluetooth", "wifi", "connect")
    private val corePackages = setOf(
        "android", "com.android.car", "com.android.car.frameworkpackagestubs", "com.android.settings",
        "com.android.systemui", "com.android.phone", "com.android.server.telecom",
        "com.android.providers.settings", "com.android.networkstack",
        "com.android.networkstack.permissionconfig", "com.android.networkstack.tethering",
        "com.geely.gc.expand", "com.geely.information", "com.gc.dialog.server",
        "cn.cmvideo.car.play", "com.reachauto.messagechannel",
    )
    private val vendorBridgePackages = setOf("com.geely.gc.settingcenter", "com.reachauto.messagechannel")

    data class Result(val file: File, val copied: Int, val reused: Int, val skipped: Int)

    fun matches(packageName: String): Boolean = packageName in corePackages ||
        keywords.any { it in packageName.lowercase() }

    fun shouldCollect(packageName: String, systemApp: Boolean): Boolean =
        systemApp || packageName in vendorBridgePackages || packageName in corePackages

    internal fun shouldCollectSystemFile(path: String): Boolean {
        if (!listOf("/system/", "/system_ext/", "/product/", "/vendor/", "/odm/", "/apex/")
                .any(path::startsWith)) return false
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        if (lower.contains("/etc/security/") || name.endsWith(".key") || name.endsWith(".pem")) return false
        if (listOf(".apk", ".jar", ".xml", ".rc", ".prop", ".conf", ".cil")
                .any(name::endsWith)) return true
        if (listOf(".so", ".odex", ".vdex").any(name::endsWith)) return true
        return relatedBinaryNames.containsMatchIn(name) &&
            (name.endsWith(".so") || lower.contains("/bin/"))
    }

    internal fun systemFilePriority(path: String): Int {
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        return when {
            name == "services.jar" || name == "framework.jar" || name == "adbd" -> -2
            name.contains("adb") || name.contains("bluetooth") || name.contains("wifi") -> 0
            lower.contains("/framework/") || lower.contains("/etc/init/") ||
                lower.contains("/etc/permissions/") || lower.contains("/etc/vintf/") -> 1
            name.endsWith(".apk") -> 3
            name.endsWith(".odex") || name.endsWith(".vdex") -> 4
            name.endsWith(".so") -> 5
            else -> 2
        }
    }

    internal fun capturePriority(packageName: String): Int = when (packageName) {
        "com.reachauto.messagechannel" -> 0
        "com.geely.gc.settingcenter" -> 1
        in corePackages -> 2
        "com.android.webview" -> 5
        else -> if (matches(packageName)) 3 else 4
    }

    @Suppress("DEPRECATION")
    fun create(context: Context): Result {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostic-reports")
        if (!directory.isDirectory && !directory.mkdirs()) error("无法创建分析包目录")
        val file = File.createTempFile("DiPlay-System-${System.currentTimeMillis()}-", ".zip", directory)
        var copied = 0
        var reused = 0
        var skipped = 0
        try {
            val installed = context.packageManager.getInstalledPackages(0)
            val systemFiles = SystemFileInventory.scan()
            val inventory = installed.sortedBy(PackageInfo::packageName).flatMap { info ->
                val app = info.applicationInfo ?: return@flatMap emptyList<String>()
                val systemApp = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                (listOfNotNull(app.publicSourceDir) + (app.splitPublicSourceDirs?.toList() ?: emptyList()))
                    .distinct().map { path ->
                        JSONObject().put("package", info.packageName).put("system", systemApp)
                            .put("path", path).put("bytes", File(path).length()).toString()
                    }
            }
            val history = SystemPackageArchiveHistory.load(directory, file,
                File(context.filesDir, "logs/system-packages.log"))
            val installedPaths = installed.flatMap { info ->
                val app = info.applicationInfo ?: return@flatMap emptyList<String>()
                listOfNotNull(app.publicSourceDir) + (app.splitPublicSourceDirs?.toList() ?: emptyList())
            }.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()
            val extraSystemFiles = systemFiles.paths.asSequence()
                .filter(::shouldCollectSystemFile)
                .filter { path -> runCatching { File(path).canonicalPath !in installedPaths }.getOrDefault(false) }
                .sortedWith(compareBy<String>(::systemFilePriority).thenBy { it })
                .toList()
            val packages = installed
                .filter { info -> info.applicationInfo?.let { app ->
                    shouldCollect(info.packageName,
                        app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                } == true }
                .sortedWith(compareBy<PackageInfo> { capturePriority(it.packageName) }.thenBy(PackageInfo::packageName))
            val entries = JSONArray()
            val systemEntries = JSONArray()
            var totalBytes = 0L
            var systemFileBytes = 0L
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                for (path in extraSystemFiles) {
                    val source = File(path)
                    val size = source.length()
                    val record = JSONObject().put("path", path).put("bytes", size)
                    val knownDigest = if (source.isFile && source.canRead())
                        SystemPackageArchiveHistory.matchingDigest(history, path, source) else null
                    val reason = when {
                        !source.isFile || !source.canRead() -> "不可读取"
                        size <= 0L -> "空文件"
                        knownDigest != null -> "本机已有相同文件"
                        size > MAX_FILE_BYTES -> "单文件超过限制"
                        systemFileBytes + size > MAX_SYSTEM_FILE_BYTES || totalBytes + size > MAX_TOTAL_BYTES ->
                            "本批次容量已满"
                        else -> null
                    }
                    if (reason != null) {
                        record.put("status", reason)
                        if (knownDigest != null) {
                            record.put("sha256", knownDigest)
                            reused++
                        } else skipped++
                    } else {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val entry = "system/${path.trimStart('/')}"
                        FileInputStream(source).use { input ->
                            zip.putNextEntry(ZipEntry(entry))
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                zip.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                            zip.closeEntry()
                        }
                        record.put("status", "已收集").put("entry", entry)
                            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                        systemFileBytes += size
                        totalBytes += size
                        copied++
                    }
                    systemEntries.put(record)
                }
                for (info in packages) {
                    val app = info.applicationInfo ?: continue
                    val item = JSONObject().put("package", info.packageName)
                        .put("version", info.versionName ?: "")
                        .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
                        .put("sourceDir", app.sourceDir)
                    val files = JSONArray()
                    val sources = listOfNotNull(app.publicSourceDir) + (app.splitPublicSourceDirs?.toList() ?: emptyList())
                    for ((index, path) in sources.distinct().withIndex()) {
                        val source = File(path)
                        val record = JSONObject().put("path", path).put("bytes", source.length())
                        val size = source.length()
                        val knownDigest = if (source.isFile && source.canRead())
                            SystemPackageArchiveHistory.matchingDigest(history, info.packageName, source) else null
                        val reason = when {
                            !source.isFile || !source.canRead() -> "不可读取"
                            size <= 0L -> "空文件"
                            knownDigest != null -> "本机已有相同文件"
                            size > MAX_FILE_BYTES -> "单文件超过限制"
                            totalBytes + size > MAX_TOTAL_BYTES -> "分析包超过限制"
                            else -> null
                        }
                        if (reason != null) {
                            record.put("status", reason)
                            if (knownDigest != null) {
                                record.put("sha256", knownDigest)
                                reused++
                            } else skipped++
                        } else {
                            val digest = MessageDigest.getInstance("SHA-256")
                            val name = "apks/${info.packageName}/$index.apk"
                            zip.putNextEntry(ZipEntry(name))
                            FileInputStream(source).use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    zip.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                }
                            }
                            zip.closeEntry()
                            record.put("status", "已收集").put("entry", name)
                                .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                            totalBytes += size
                            copied++
                        }
                        files.put(record)
                    }
                    entries.put(item.put("files", files))
                }
                val manifest = JSONObject().put("fingerprint", Build.FINGERPRINT)
                    .put("display", Build.DISPLAY).put("model", Build.MODEL)
                    .put("androidSdk", Build.VERSION.SDK_INT).put("generatedAt", System.currentTimeMillis())
                    .put("visibleInstalledPackages", installed.size)
                    .put("allVisiblePackageNames", JSONArray(installed.map(PackageInfo::packageName).sorted()))
                    .put("allVisiblePackageFiles", JSONArray(inventory.map { JSONObject(it) }))
                    .put("systemFileCount", systemFiles.paths.size)
                    .put("systemFileUnreadableDirectories", systemFiles.unreadableDirectories)
                    .put("systemFileScanTruncated", systemFiles.truncated)
                    .put("systemFiles", systemEntries)
                    .put("packages", entries)
                zip.putNextEntry(ZipEntry("system-files.txt"))
                systemFiles.paths.forEach { path ->
                    zip.write(path.toByteArray(Charsets.UTF_8))
                    zip.write('\n'.code)
                }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            return Result(file, copied, reused, skipped)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun shareUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
}
