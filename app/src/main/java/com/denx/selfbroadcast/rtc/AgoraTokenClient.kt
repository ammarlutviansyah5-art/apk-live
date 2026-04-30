package com.denx.selfbroadcast.rtc

import com.denx.selfbroadcast.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object AgoraTokenClient {
    suspend fun fetchRtcToken(channel: String, role: String, uid: Int): String? = withContext(Dispatchers.IO) {
        val base = AppConfig.tokenServerUrl.trim()
        if (base.isBlank()) return@withContext null
        if (base.contains("REPLACE_WITH", ignoreCase = true)) return@withContext null

        val safeChannel = URLEncoder.encode(channel, "UTF-8")
        val safeRole = URLEncoder.encode(role, "UTF-8")
        val url = URL("$base/rtc/$safeChannel/$safeRole/$uid")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            json.optString("token", null)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
