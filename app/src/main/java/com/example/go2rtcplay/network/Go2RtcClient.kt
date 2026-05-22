package com.example.go2rtcplay.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class CameraInfo(
    val name: String,
    val online: Boolean = false,
    val sourceTypes: List<String> = emptyList()
)

class Go2RtcClient(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun checkConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api")
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getStreams(): List<CameraInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/streams")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            val type = object : TypeToken<Map<String, JsonObject>>() {}.type
            val map: Map<String, JsonObject> = gson.fromJson(body, type) ?: return@withContext emptyList()

            map.map { (name, info) ->
                val producers = info.getAsJsonArray("producers")
                val online = producers != null && producers.size() > 0
                val types = if (producers != null) {
                    producers.mapNotNull { p ->
                        val obj = p.asJsonObject
                        obj?.get("type")?.asString
                    }
                } else emptyList()
                CameraInfo(name = name, online = online, sourceTypes = types)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getStreamUrl(cameraName: String): String {
        return "$baseUrl/api/stream.m3u8?src=$cameraName"
    }
}
