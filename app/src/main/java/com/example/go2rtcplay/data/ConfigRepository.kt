package com.example.go2rtcplay.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ServerAddress(
    val host: String,
    val port: Int = 1984,
    val discovered: Boolean = false,
    var enabled: Boolean = true
) {
    val url: String get() = "http://$host:$port"
}

class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("go2rtc_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getServers(): MutableList<ServerAddress> {
        val json = prefs.getString("servers", null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<ServerAddress>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun saveServers(servers: List<ServerAddress>) {
        prefs.edit().putString("servers", gson.toJson(servers)).apply()
    }

    fun addServer(server: ServerAddress) {
        val list = getServers()
        if (list.none { it.host == server.host && it.port == server.port }) {
            list.add(server)
            saveServers(list)
        }
    }

    fun removeServer(server: ServerAddress) {
        val list = getServers()
        list.removeAll { it.host == server.host && it.port == server.port }
        saveServers(list)
    }

    fun getActiveServer(): ServerAddress? {
        return getServers().firstOrNull { it.enabled }
    }

    fun setActiveServer(server: ServerAddress) {
        val list = getServers()
        list.forEach { it.enabled = it.host == server.host && it.port == server.port }
        saveServers(list)
    }

    fun getPreferredProtocol(): String {
        return prefs.getString("preferred_protocol", "") ?: ""
    }

    fun setPreferredProtocol(protocol: String) {
        prefs.edit().putString("preferred_protocol", protocol).apply()
    }

    fun saveCameraNames(names: List<String>) {
        prefs.edit().putString("camera_names", gson.toJson(names)).apply()
    }

    fun getCameraNames(): List<String> {
        val json = prefs.getString("camera_names", null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun getRefreshInterval(): Int {
        return prefs.getInt("refresh_interval", 5000)
    }

    fun setRefreshInterval(ms: Int) {
        prefs.edit().putInt("refresh_interval", ms).apply()
    }
}
