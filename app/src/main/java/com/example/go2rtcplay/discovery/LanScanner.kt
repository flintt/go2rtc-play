package com.example.go2rtcplay.discovery

import com.example.go2rtcplay.data.ServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LanScanner {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .build()

    companion object {
        private const val CONCURRENT_LIMIT = 64
        private const val TCP_TIMEOUT_MS = 300
    }

    data class ScanResult(val host: String, val port: Int)

    suspend fun scan(
        port: Int = 1984,
        onProgress: ((scanned: Int, total: Int, currentIp: String) -> Unit)? = null
    ): List<ServerAddress> = withContext(Dispatchers.IO) {
        val subnets = getLocalSubnets()
        if (subnets.isEmpty()) return@withContext emptyList()

        val semaphore = Semaphore(CONCURRENT_LIMIT)
        var scanned = AtomicInteger(0)
        val total = subnets.size * 254
        val results = mutableListOf<ScanResult>()

        val jobs = subnets.flatMap { subnet ->
            (1..254).map { lastOctet ->
                async {
                    semaphore.withPermit {
                        val ip = "$subnet.$lastOctet"
                        if (tcpPortOpen(ip, port) && isGo2Rtc(ip, port)) {
                            synchronized(results) { results.add(ScanResult(ip, port)) }
                        }
                        val s = scanned.incrementAndGet()
                        onProgress?.invoke(s, total, ip)
                    }
                }
            }
        }
        jobs.awaitAll()

        results.map { ServerAddress(host = it.host, port = it.port, discovered = true) }
    }

    private fun tcpPortOpen(ip: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), TCP_TIMEOUT_MS)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isGo2Rtc(ip: String, port: Int): Boolean {
        return try {
            val request = Request.Builder()
                .url("http://$ip:$port/api/streams")
                .build()
            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    fun getLocalSubnets(): List<String> {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val addrs = mutableListOf<Triple<String, String, String>>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val subnet = ip.substringBeforeLast(".")
                        addrs.add(Triple(iface.name, ip, subnet))
                    }
                }
            }

            val wifi = addrs.filter { it.first.startsWith("wlan") }
            val eth = addrs.filter { it.first.startsWith("eth") }
            val other = addrs - wifi - eth

            (wifi + eth + other)
                .map { it.third }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getLocalIps(): List<String> {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val addrs = mutableListOf<Pair<String, String>>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        addrs.add(iface.name to ip)
                    }
                }
            }

            val wifi = addrs.filter { it.first.startsWith("wlan") }
            val eth = addrs.filter { it.first.startsWith("eth") }
            val other = addrs - wifi - eth

            (wifi + eth + other).map { it.second }
        } catch (e: Exception) {
            emptyList()
        }
    }
}