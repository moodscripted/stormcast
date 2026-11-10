package com.example.stormcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Api {

    // NWS asks for an identifying User-Agent on api.weather.gov requests.
    private const val USER_AGENT = "StormCast/1.0 (Android weather app)"

    suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/geo+json, application/json")
            connectTimeout = 15000
            readTimeout = 20000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from $url")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
