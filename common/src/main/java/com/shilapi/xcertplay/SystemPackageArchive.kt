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

/** 收集车机可读取的互联组件 APK；不读取应用私有数据或受保护的系统分区。 */
internal object SystemPackageArchive {
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    private const val MAX_FILE_BYTES = 96L * 1024 * 1024
    private val keywords = listOf("flyme", "meizu", "geely", "ecarx", "autolink", "carplay", "bluetooth", "wifi", "connect")
    private val corePackages = setOf(
        "com.android.car", "com.android.settings", "com.android.networkstack",
        "com.android.networkstack.tethering", "com.reachauto.messagechannel",
    )
    private val vendorBridgePackages = setOf("com.geely.gc.settingcenter", "com.reachauto.messagechannel")

    data class Result(val file: File, val copied: Int, val skipped: Int)

    fun matches(packageName: String): Boolean = packageName in corePackages ||
        keywords.any { it in packageName.lowercase() }

    fun shouldCollect(packageName: String, systemApp: Boolean): Boolean = matches(packageName) &&
        (systemApp || packageName in vendorBridgePackages)

    @Suppress("DEPRECATION")
    fun create(context: Context): Result {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostic-reports")
        if (!directory.isDirectory && !directory.mkdirs()) error("无法创建分析包目录")
        val file = File.createTempFile("DiPlay-System-${System.currentTimeMillis()}-", ".zip", directory)
        var copied = 0
        var skipped = 0
        try {
            val installed = context.packageManager.getInstalledPackages(0)
            val packages = installed
                .filter { info -> info.applicationInfo?.let { app ->
                    shouldCollect(info.packageName,
                        app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                } == true }
                .sortedBy(PackageInfo::packageName)
            val entries = JSONArray()
            var totalBytes = 0L
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
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
                        val reason = when {
                            !source.isFile || !source.canRead() -> "不可读取"
                            size <= 0L -> "空文件"
                            size > MAX_FILE_BYTES -> "单文件超过限制"
                            totalBytes + size > MAX_TOTAL_BYTES -> "分析包超过限制"
                            else -> null
                        }
                        if (reason != null) {
                            record.put("status", reason)
                            skipped++
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
                    .put("packages", entries)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            return Result(file, copied, skipped)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun shareUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
}
