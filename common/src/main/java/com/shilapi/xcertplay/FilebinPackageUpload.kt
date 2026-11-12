package com.shilapi.xcertplay

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/** 将用户主动采集的系统包上传到 Filebin，并校验服务端返回的文件摘要。 */
internal object FilebinPackageUpload {
    private const val SERVICE = "https://filebin.net"
    private const val MAX_RESPONSE_BYTES = 32 * 1024
    private val random = SecureRandom()

    data class Result(val localFile: File, val downloadUrl: String, val sha256: String, val expiresAt: String)

    fun upload(file: File, service: String = SERVICE): Result {
        require(file.isFile && file.length() > 0L) { "系统包文件不存在或为空" }
        val base = URL(service)
        require(base.protocol == "https" || base.protocol == "http" && base.host == "127.0.0.1") { "上传地址必须使用 HTTPS" }
        val bin = randomBin()
        val endpoint = URL(base, "/$bin/${file.name}")
        val digest = sha256(file)
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setFixedLengthStreamingMode(file.length())
            connection.setRequestProperty("Content-Type", "application/zip")
            connection.setRequestProperty("Content-SHA256", digest)
            file.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output, 64 * 1024) } }
            val code = connection.responseCode
            val stream = if (code == HttpURLConnection.HTTP_CREATED) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= MAX_RESPONSE_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            require(body.size <= MAX_RESPONSE_BYTES) { "上传服务返回内容过长" }
            if (code != HttpURLConnection.HTTP_CREATED) error("Filebin HTTP $code: ${String(body, Charsets.UTF_8).take(180)}")
            val response = JSONObject(String(body, Charsets.UTF_8))
            val serverBin = response.getJSONObject("bin")
            val serverFile = response.getJSONObject("file")
            require(serverBin.getString("id") == bin && serverFile.getString("filename") == file.name) { "上传服务返回的文件标识不符" }
            require(serverFile.getString("sha256").equals(digest, ignoreCase = true)) { "上传后 SHA-256 校验失败" }
            val download = endpoint.toString()
            return Result(file, download, digest, serverBin.optString("expired_at", ""))
        } finally {
            connection.disconnect()
        }
    }

    private fun randomBin(): String {
        val bytes = ByteArray(9).also(random::nextBytes)
        return "diplayz" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
