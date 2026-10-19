package site.whitezaak.wearpod.offline

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 解析手表本机在局域网/热点上的 IPv4 地址。
 *
 * 纯 JVM 逻辑（无 android.* 依赖），判定规则集中在 [pick]，便于单测。
 * 不能写死 192.168.43.1 —— 国产 ROM 的热点网段各不相同，必须从接口枚举里取。
 */
object LocalIpResolver {

    data class Candidate(val interfaceName: String, val ipv4: String, val isUp: Boolean)

    /** 热点接口名优先级最高：ap0 / swlan0 / softap0 / wlan1 都见过。 */
    private val AP_INTERFACE = Regex("^(ap\\d+|swlan\\d+|softap\\d+|wlan1)")

    /** 蜂窝、蓝牙 PAN、p2p、dummy、loopback 一律不参与。 */
    private val EXCLUDED_INTERFACE = Regex("^(rmnet\\w*|ccmni\\w*|pdp\\w*|bt-pan\\w*|p2p\\w*|dummy\\d*|lo)")

    fun pick(candidates: List<Candidate>): String? {
        val usable = candidates.filter { candidate ->
            candidate.isUp &&
                isUsableIpv4(candidate.ipv4) &&
                !EXCLUDED_INTERFACE.containsMatchIn(candidate.interfaceName)
        }
        val preferred = usable.firstOrNull { AP_INTERFACE.containsMatchIn(it.interfaceName) }
        return (preferred ?: usable.firstOrNull())?.ipv4
    }

    /**
     * 只接受热点口的候选：热点已启动时手机的下一跳必须是 AP 接口地址，
     * 拿 STA 地址给手机会连不上。热点口名称异常时返回 null，由调用方决定回退。
     */
    fun pickAccessPoint(candidates: List<Candidate>): String? = candidates
        .firstOrNull {
            it.isUp && isUsableIpv4(it.ipv4) && AP_INTERFACE.containsMatchIn(it.interfaceName)
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
