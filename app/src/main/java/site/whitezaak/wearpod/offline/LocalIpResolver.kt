package site.whitezaak.wearpod.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 解析手表当前可用于局域网访问的 IPv4 地址。
 *
 * 判定优先问系统「当前有没有已连接的 Wi-Fi / 以太网网络」，只从这类网络的
 * 链路地址里取 IPv4 —— 接口枚举会误判：eSIM 蜂窝口（`wwan*`/`rmnet*`）、
 * 464xlat 的 `clat4`(192.0.0.4)、或 Wi-Fi 关闭后残留的 `wlan0` 都带过可用 IPv4。
 * 只有在拿不到 ConnectivityManager 时才回退到接口枚举。
 *
 * 纯逻辑（[pick] / [pickFromNetworks] / [isUsableIpv4]）无 android.* 依赖，可单测。
 */
object LocalIpResolver {

    private const val TAG = "WearPodOffline"

    data class Candidate(val interfaceName: String, val ipv4: String, val isUp: Boolean)

    /** 一个已连接网络的摘要：是否局域网传输（Wi-Fi/以太网）＋它的 IPv4 列表。 */
    data class NetworkCandidate(val isLanTransport: Boolean, val ipv4Addresses: List<String>)

    /** 蜂窝、蓝牙 PAN、p2p、dummy、loopback 一律不参与（仅接口枚举回退路径会用到）。 */
    private val EXCLUDED_INTERFACE = Regex("^(rmnet\\w*|wwan\\w*|ccmni\\w*|pdp\\w*|bt-pan\\w*|p2p\\w*|dummy\\d*|clat\\d*|lo)")

    /** 只接受局域网传输类型的网络，且其链路地址里必须有可用 IPv4。 */
    fun pickFromNetworks(networks: List<NetworkCandidate>): String? = networks
        .filter { it.isLanTransport }
        .flatMap { it.ipv4Addresses }
        .firstOrNull { isUsableIpv4(it) }

    fun pick(candidates: List<Candidate>): String? = candidates
        .firstOrNull { candidate ->
            candidate.isUp &&
                isUsableIpv4(candidate.ipv4) &&
                !EXCLUDED_INTERFACE.containsMatchIn(candidate.interfaceName)
        }
        ?.ipv4

    fun isUsableIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        if (octets[0] == 0) return false
        if (octets[0] == 127) return false                    // loopback
        if (octets[0] == 169 && octets[1] == 254) return false // link-local
        return true
    }

    /** 枚举本机所有接口的 IPv4 候选（仅在没有 ConnectivityManager 时使用）。 */
    fun enumerate(): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return result
        for (nif in interfaces) {
            val name = nif.name ?: continue
            val isUp = runCatching { nif.isUp }.getOrDefault(false)
            for (address in nif.inetAddresses) {
                val ipv4 = (address as? Inet4Address)?.hostAddress ?: continue
                result += Candidate(name, ipv4, isUp)
            }
        }
        return result
    }

    /**
     * 主入口：返回手表可用于局域网访问的 IPv4；没有已连接的 Wi-Fi/以太网网络时返回 null。
     */
    fun resolve(context: Context): String? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            val fallback = pick(enumerate())
            Log.i(TAG, "resolve(no ConnectivityManager) -> $fallback")
            return fallback
        }
        val networks = describeNetworks(connectivityManager)
        val picked = pickFromNetworks(networks)
        if (picked == null) {
            Log.i(TAG, "resolve: no LAN address; networks=$networks")
        } else {
            Log.d(TAG, "resolve: $picked (networks=$networks)")
        }
        return picked
    }

    @Suppress("DEPRECATION")
    private fun describeNetworks(connectivityManager: ConnectivityManager): List<NetworkCandidate> =
        runCatching {
            connectivityManager.allNetworks.mapNotNull { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                    ?: return@mapNotNull null
                val isLanTransport =
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                val linkProperties = connectivityManager.getLinkProperties(network)
                    ?: return@mapNotNull null
                val ipv4Addresses = linkProperties.linkAddresses.mapNotNull { linkAddress ->
                    (linkAddress.address as? Inet4Address)?.hostAddress
                }
                NetworkCandidate(isLanTransport, ipv4Addresses)
            }
        }.getOrElse { error ->
            Log.w(TAG, "describeNetworks failed", error)
            emptyList()
        }
}
