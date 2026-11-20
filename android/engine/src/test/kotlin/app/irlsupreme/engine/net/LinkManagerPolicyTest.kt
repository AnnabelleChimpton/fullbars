package app.irlsupreme.engine.net

import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import srtlakt.LinkHint
import srtlakt.LinkPriority
import java.net.InetAddress

class LinkManagerPolicyTest {
    private fun ip(s: String): InetAddress = InetAddress.getByAddress(
        if (':' in s) java.net.Inet6Address.getByName(s).address else s.split('.').map { it.toInt().toByte() }.toByteArray(),
    )

    @Test fun `registration hints rank ethernet, cellular and validated Wi-Fi above unvalidated or captive Wi-Fi`() {
        assertEquals(LinkHint.ETHERNET, LinkManager.regHint(NetworkCapabilities.TRANSPORT_ETHERNET, validated = false, captive = false))
        assertEquals(LinkHint.CELLULAR, LinkManager.regHint(NetworkCapabilities.TRANSPORT_CELLULAR, validated = false, captive = false))
        assertEquals(LinkHint.WIFI_VALIDATED, LinkManager.regHint(NetworkCapabilities.TRANSPORT_WIFI, validated = true, captive = false))
        assertEquals(LinkHint.WIFI_UNVALIDATED, LinkManager.regHint(NetworkCapabilities.TRANSPORT_WIFI, validated = false, captive = false))
        assertEquals(LinkHint.CAPTIVE_PORTAL, LinkManager.regHint(NetworkCapabilities.TRANSPORT_WIFI, validated = false, captive = true))
    }

    @Test fun `mobile data is secondary only when opted in and an unmetered validated network is up`() {
        val cell = NetworkCapabilities.TRANSPORT_CELLULAR
        val wifi = NetworkCapabilities.TRANSPORT_WIFI
        assertEquals(LinkPriority.PRIMARY, LinkManager.defaultPriority(cell, strongUnmeteredUp = true, enabled = false))
        assertEquals(LinkPriority.SECONDARY, LinkManager.defaultPriority(cell, strongUnmeteredUp = true, enabled = true))
        assertEquals(LinkPriority.PRIMARY, LinkManager.defaultPriority(cell, strongUnmeteredUp = false, enabled = true))
        assertEquals(LinkPriority.PRIMARY, LinkManager.defaultPriority(wifi, strongUnmeteredUp = true, enabled = true))
    }

    @Test fun `only losing an address the link may send from triggers a new socket`() {
        val before = listOf(ip("192.168.1.20"), ip("fe80::1"), ip("2001:db8::20"))
        assertFalse("nothing changed", LinkManager.removedAddress(before, before, ipv6 = false))
        assertFalse("an IPv6 address was added", LinkManager.removedAddress(before, before + ip("2001:db8::21"), ipv6 = false))
        assertFalse("an IPv6 address went, the server is IPv4", LinkManager.removedAddress(before, before - ip("2001:db8::20"), ipv6 = false))
        assertTrue("new DHCP lease", LinkManager.removedAddress(before, listOf(ip("192.168.1.57"), ip("fe80::1")), ipv6 = false))
        assertTrue("IPv6 server, its family went", LinkManager.removedAddress(before, before - ip("2001:db8::20"), ipv6 = true))
        assertFalse("link-local doesn't count", LinkManager.removedAddress(before, before - ip("fe80::1"), ipv6 = true))
    }

    @Test fun `socket replacements are at most one a second per network`() {
        assertEquals(0L, LinkManager.relinkDelayMs(5_000, Long.MIN_VALUE))
        assertEquals(700L, LinkManager.relinkDelayMs(5_300, 5_000))
        assertEquals(0L, LinkManager.relinkDelayMs(6_000, 5_000))
    }
}
