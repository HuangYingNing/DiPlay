package com.shilapi.xcertplay

import com.shilapi.xcertplay.vehicleprobe.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Uploads the complete redacted diagnostic report to the project's fixed PasteDB paste. */
object PasteDbDiagnosticUpload {
    private const val PASTE_ID = "diplay-geely-xingyuan"
    private const val API_URL = "https://pastedb-rw62.onrender.com/api/paste/$PASTE_ID"
    const val MAX_DESCRIPTION_LENGTH = 2_000
    private const val MAX_REPORT_BYTES = 1024 * 1024
    private const val MAX_API_KEY_BYTES = 256
    private const val MAX_RESPONSE_BYTES = 32 * 1024
    private val API_KEY_FORMAT = Regex("pdb_[0-9a-fA-F]{64}")

    fun upload(
        appVersion: String,
        sourceRevision: String,
        fileName: String,
        issueDescription: String,
        report: String,
        apiKeyEndpoint: String = BuildConfig.PASTEDB_KEY_ENDPOINT,
        endpoint: String = API_URL,
        submittedAt: Long = System.currentTimeMillis(),
    ): String {
        require(appVersion.isNotBlank())
        require(sourceRevision.matches(Regex("(?:[0-9a-f]{7,40}|unknown)(?:-dirty)?")))
        val description = issueDescription.trim()
        require(description.length <= MAX_DESCRIPTION_LENGTH)
        require(fileName.matches(Regex("DiPlay-[0-9]{8}-[0-9]{6}-[0-9]{3}\\.txt")))
        val reportBytes = report.toByteArray(Charsets.UTF_8)
        if (reportBytes.size > MAX_REPORT_BYTES) throw DiagnosticReportTooLargeException()
        require(reportBytes.isNotEmpty())
        val apiKey = fetchApiKey(apiKeyEndpoint)

        val content = buildString {
            appendLine("DiPlay version: $appVersion")
            appendLine("Source commit: $sourceRevision")
            appendLine("DiPlay diagnostic log")
            appendLine("File: $fileName")
            appendLine("Submitted: ${utcTimestamp(submittedAt)}")
            if (description.isNotEmpty()) appendLine("Problem: $description")
            appendLine()
            append(report)
        }
        val request = buildString {
            append("{\"title\":")
            append(jsonString("DiPlay Geely Xingyuan diagnostic log"))
            append(",\"content\":")
            append(jsonString(content))
            append(",\"syntax\":\"text\"}")
        }.toByteArray(Charsets.UTF_8)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(request.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-api-key", apiKey)
            connection.outputStream.use { it.write(request) }
            val status = connection.responseCode
            check(status in 200..299) { "PasteDB upload HTTP $status" }
            val response = connection.inputStream.use { readBounded(it, MAX_RESPONSE_BYTES) }.toString(Charsets.UTF_8)
            check(responseField(response, "status") == "success" && responseField(response, "custom_id") == PASTE_ID) {
                "PasteDB rejected the log upload"
            }
            return responseField(response, "paste_id") ?: PASTE_ID
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchApiKey(endpoint: String): String {
        require(endpoint.isNotBlank()) { "PasteDB API key endpoint is not configured" }
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true) || InetAddress.getByName(url.host).isLoopbackAddress) {
            "PasteDB API key endpoint must use HTTPS"
        }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "text/plain")
            val status = connection.responseCode
            check(status in 200..299) { "PasteDB API key HTTP $status" }
            val apiKey = connection.inputStream.use { readBounded(it, MAX_API_KEY_BYTES) }
                .toString(Charsets.UTF_8)
                .trim()
            check(API_KEY_FORMAT.matches(apiKey)) { "PasteDB API key response is invalid" }
            return apiKey
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream, maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximumBytes)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private fun responseField(response: String, field: String): String? =
        Regex("\\\"${Regex.escape(field)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
            .find(response)?.groupValues?.get(1)

    private fun utcTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))
}
