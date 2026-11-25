package com.stockwatch.app.core

import java.net.HttpURLConnection
import java.net.URL

object Http {
    const val USER_AGENT = "Mozilla/5.0"

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 12_000,
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (url.contains("gtimg.cn") || url.contains("proxy.finance.qq.com")) {
                connection.setRequestProperty("Referer", "https://gu.qq.com/")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                ?: throw RuntimeException("HTTP $code")
            stream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 12_000,
        charset: Charset = Charsets.UTF_8,
    ): String = get(url, headers, timeoutMs).toString(charset)

    fun postJson(
        url: String,
        payload: Any?,
        timeoutMs: Int = 12_000,
    ): JsonElement {
        val body = stringifyJson(payload).toByteArray(Charsets.UTF_8)
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                ?: throw RuntimeException("HTTP $code")
            parseJson(stream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            connection.disconnect()
        }
    }
}
