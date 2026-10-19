package site.whitezaak.wearpod.offline

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 解析手表当前可用于局域网访问的 IPv4 地址。
 *
 * 纯 JVM 逻辑（无 android.* 依赖），判定规则集中在 [pick]，便于单测。
 * 不能写死 192.168.x.x —— 不同路由器/网段都要能用，必须从接口枚举里取。
 */
object LocalIpResolver {

    data class Candidate(val interfaceName: String, val ipv4: String, val isUp: Boolean)

    /** 蜂窝、蓝牙 PAN、p2p、dummy、loopback 一律不参与。 */
    private val EXCLUDED_INTERFACE = Regex("^(rmnet\\w*|ccmni\\w*|pdp\\w*|bt-pan\\w*|p2p\\w*|dummy\\d*|lo)")

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

    /** 枚举本机所有接口的 IPv4 候选（含地址族判定，不做优先级取舍）。 */
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

    fun resolve(): String? = pick(enumerate())
}
