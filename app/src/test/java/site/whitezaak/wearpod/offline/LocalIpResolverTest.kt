package site.whitezaak.wearpod.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalIpResolverTest {

    private fun candidate(name: String, ip: String, up: Boolean = true) =
        LocalIpResolver.Candidate(name, ip, up)

    private fun network(isLan: Boolean, vararg ips: String) =
        LocalIpResolver.NetworkCandidate(isLan, ips.toList())

    // 主路径：按系统报告的「已连接网络」判定
    @Test
    fun `lan network address wins over cellular`() {
        assertEquals(
            "192.168.1.30",
            LocalIpResolver.pickFromNetworks(
                listOf(
                    network(false, "10.20.30.40"),
                    network(true, "192.168.1.30"),
                )
            ),
        )
    }

    @Test
    fun `cellular only watch has no lan address`() {
        assertNull(
            LocalIpResolver.pickFromNetworks(
                listOf(network(false, "10.20.30.40", "192.0.0.4"))
            )
        )
    }

    @Test
    fun `lan network without usable ipv4 yields nothing`() {
        assertNull(LocalIpResolver.pickFromNetworks(listOf(network(true, "169.254.1.1"))))
        assertNull(LocalIpResolver.pickFromNetworks(listOf(network(true))))
        assertNull(LocalIpResolver.pickFromNetworks(emptyList()))
    }

    // 回退路径：接口枚举（仅当拿不到 ConnectivityManager 时）
    @Test
    fun `returns the only usable address`() {
        assertEquals(
            "192.168.1.20",
            LocalIpResolver.pick(listOf(candidate("wlan0", "192.168.1.20"))),
        )
    }

    @Test
    fun `cellular and p2p interfaces are excluded`() {
        assertNull(LocalIpResolver.pick(listOf(candidate("rmnet_data0", "10.1.2.3"))))
        assertNull(LocalIpResolver.pick(listOf(candidate("wwan0", "10.1.2.3"))))
        assertNull(LocalIpResolver.pick(listOf(candidate("clat4", "192.0.0.4"))))
        assertNull(LocalIpResolver.pick(listOf(candidate("p2p0", "192.168.49.1"))))
        assertNull(LocalIpResolver.pick(listOf(candidate("bt-pan", "192.168.44.1"))))
    }

    @Test
    fun `loopback and link local addresses are excluded`() {
        assertNull(LocalIpResolver.pick(listOf(candidate("wlan0", "127.0.0.1"))))
        assertNull(LocalIpResolver.pick(listOf(candidate("wlan0", "169.254.10.20"))))
        assertNull(LocalIpResolver.pick(listOf(candidate("wlan0", "0.0.0.0"))))
        assertNull(LocalIpResolver.pick(listOf(candidate("wlan0", "not.an.ip"))))
    }

    @Test
    fun `down interfaces are excluded`() {
        assertNull(LocalIpResolver.pick(listOf(candidate("wlan0", "192.168.1.20", up = false))))
    }

    @Test
    fun `no candidates means no address`() {
        assertNull(LocalIpResolver.pick(emptyList()))
    }
}
