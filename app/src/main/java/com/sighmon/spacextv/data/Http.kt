package com.sighmon.spacextv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class HttpClient {
    suspend fun getString(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Int = 15_000,
    ): String = request(url, "GET", headers, timeoutMillis).decodeToString()

    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Int = 15_000,
    ): ByteArray = request(url, "GET", headers, timeoutMillis)

    suspend fun postBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Int = 15_000,
    ): ByteArray = request(url, "POST", headers, timeoutMillis)

    private suspend fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        timeoutMillis: Int,
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.readBytes()
            } else {
                connection.errorStream?.readBytes() ?: ByteArray(0)
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from $url: ${body.decodeToString().take(220)}")
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}

internal fun userAgentHeaders(): Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 AndroidTV SpaceXTV/1.0",
)
