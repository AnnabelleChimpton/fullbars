package app.irlsupreme.dji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraHotspotAddressTest {
    @Test fun `mobile data coming up at the same moment is never taken for the hotspot`() {
        val before = mapOf("wlan0" to setOf("192.168.1.20"))
        val now = before + mapOf("rmnet_data0" to setOf("10.45.3.2"), "wlan1" to setOf("192.168.42.1"))
        assertEquals("wlan1" to "192.168.42.1", CameraHotspot.pickAddress(before, now, wifiClients = setOf("wlan0")))
        assertNull(CameraHotspot.pickAddress(before, before + mapOf("rmnet_data0" to setOf("10.45.3.2")), setOf("wlan0")))
    }

    @Test fun `the phone's own Wi-Fi reconnecting is not the hotspot`() {
        val now = mapOf("wlan0" to setOf("192.168.1.20"))
        assertNull(CameraHotspot.pickAddress(emptyMap(), now, wifiClients = setOf("wlan0")))
    }

    @Test fun `192 168 is preferred, and soft-AP names are accepted`() {
        val now = mapOf("ap0" to setOf("10.0.0.1"), "swlan0" to setOf("192.168.43.1"))
        assertEquals("swlan0" to "192.168.43.1", CameraHotspot.pickAddress(emptyMap(), now, emptySet()))
        assertEquals("softap0" to "172.20.10.1", CameraHotspot.pickAddress(emptyMap(), mapOf("softap0" to setOf("172.20.10.1")), emptySet()))
    }
}
