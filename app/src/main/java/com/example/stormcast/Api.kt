package com.example.stormcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Api {

    // NWS asks for an identifying User-Agent on api.weather.gov requests.
    private const val USER_AGENT = "StormCast/1.1 (Android weather app)"

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

    suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            JSONObject(conn.body())
        } finally {
            conn.disconnect()
        }
    }

    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            conn.body()
        } finally {
            conn.disconnect()
        }
    }

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
