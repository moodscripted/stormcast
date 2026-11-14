package com.example.stormcast

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

object Api {

    // NWS asks for an identifying User-Agent on api.weather.gov requests.
    private const val USER_AGENT = "StormCast/1.4 (Android weather app)"

    private const val MAX_STALE_MS = 24L * 60 * 60 * 1000
    private const val MAX_CACHE_BYTES = 2_000_000

    private var cacheDir: File? = null

    /** Age (ms) of the oldest cached response served since the last [takeStaleNote]; 0 = none. */
    private val staleAge = AtomicLong(0)

    /** Call once with any Context (Activity, Worker, widget) before making requests. */
    fun init(ctx: Context) {
        if (cacheDir == null) {
            cacheDir = File(ctx.applicationContext.cacheDir, "http").apply { mkdirs() }
        }
    }

    /** "Offline — showing saved data from 12 min ago" if any request fell back to cache, else null. */
    fun takeStaleNote(): String? {
        val age = staleAge.getAndSet(0)
        if (age <= 0) return null
        val mins = (age / 60000).toInt()
        val ago = when {
            mins < 1 -> "moments ago"
            mins < 90 -> "$mins min ago"
            else -> "${mins / 60} h ago"
        }
        return "Offline — showing saved data from $ago"
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty(
                "Accept",
                "application/geo+json, application/json, text/html, */*"
            )
            connectTimeout = 15000
            readTimeout = 20000
        }

    private fun HttpURLConnection.body(): String {
        val code = responseCode
        if (code !in 200..299) throw IOException("HTTP $code from $url")
        return inputStream.bufferedReader().use { it.readText() }
    }

    private fun cacheFile(url: String): File? {
        val dir = cacheDir ?: return null
        return File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length + ".txt")
    }

    private fun fetchWithCache(url: String, allowStale: Boolean): String {
        val conn = open(url)
        try {
            val text = conn.body()
            if (text.length < MAX_CACHE_BYTES) {
                try { cacheFile(url)?.writeText(text) } catch (_: Exception) {}
            }
            return text
        } catch (e: IOException) {
            if (allowStale) {
                val f = cacheFile(url)
                if (f != null && f.exists()) {
                    val age = System.currentTimeMillis() - f.lastModified()
                    if (age in 0..MAX_STALE_MS) {
                        staleAge.accumulateAndGet(age.coerceAtLeast(1)) { a, b -> maxOf(a, b) }
                        return f.readText()
                    }
                }
            }
            throw e
        } finally {
            conn.disconnect()
        }
    }

    suspend fun getJson(url: String, allowStale: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) { JSONObject(fetchWithCache(url, allowStale)) }

    suspend fun getText(url: String, allowStale: Boolean = true): String =
        withContext(Dispatchers.IO) { fetchWithCache(url, allowStale) }

    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from $url")
            conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }
}
