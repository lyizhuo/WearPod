package site.whitezaak.wearpod.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalIpResolverTest {

    private fun candidate(name: String, ip: String, up: Boolean = true) =
        LocalIpResolver.Candidate(name, ip, up)

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
