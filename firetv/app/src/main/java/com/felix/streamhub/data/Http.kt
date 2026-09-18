package com.felix.streamhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Deliberately dependency-free HTTP. HttpURLConnection is unglamorous but it is
 * in the platform, so there is nothing to resolve, nothing to shade, and nothing
 * to go stale.
 */
object Http {

    class HttpException(val status: Int, message: String) : Exception(message)

    // Written from several Dispatchers.IO coroutines at once (Recommender runs
    // its discover calls in parallel), so a plain HashMap can corrupt on resize.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private const val TTL_MS = 30 * 60 * 1000L

    suspend fun getJson(url: String, useCache: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        if (useCache) {
            cache[url]?.let { (at, body) ->
                if (System.currentTimeMillis() - at < TTL_MS) return@withContext JSONObject(body)
            }
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "StreamHubTV/1.0")
        }

        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use(BufferedReader::readText)
            } ?: ""

            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("status_message") }.getOrNull()
                throw HttpException(
                    status,
                    when {
                        status == 401 || status == 403 ->
                            "TMDB rejected the API key. Re-enter it under the cog on your phone."
                        status == 429 -> "TMDB is rate-limiting. Give it a moment."
                        !message.isNullOrEmpty() -> message
                        else -> "Request failed ($status)"
                    }
                )
            }

            if (useCache) {
                // Keep the cache from growing without bound on a long session.
                if (cache.size > 200) cache.clear()
                cache[url] = System.currentTimeMillis() to body
            }
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    fun clearCache() = cache.clear()
}
