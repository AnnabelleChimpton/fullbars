package app.irlsupreme.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotShareTest {
    private val keys = HotspotShare.Keys("k7m2xq9pa4tz")
    private val creds = HotspotShare.Credentials("AndroidShare_4821", "p4ssw0rd-from-android", HotspotShare.SECURITY_WPA3_TRANSITION)

    @Test fun `a helper with the same code opens what the streaming phone sealed`() {
        val sealed = HotspotShare.seal(keys, creds)
        assertEquals(creds, HotspotShare.open(HotspotShare.Keys("k7m2xq9pa4tz"), sealed))
        // Fits one long GATT read comfortably even at the longest name and password.
        val longest = HotspotShare.seal(keys, HotspotShare.Credentials("N".repeat(32), "p".repeat(63), 1))
        assertEquals(1 + 12 + 3 + 32 + 63 + 16, longest.size)
    }

    @Test fun `another code, a tampered byte or junk opens nothing`() {
        val sealed = HotspotShare.seal(keys, creds)
        assertNull(HotspotShare.open(HotspotShare.Keys("k7m2xq9pa4ty"), sealed))
        for (i in sealed.indices) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", HotspotShare.open(keys, bad))
        }
        assertNull(HotspotShare.open(keys, ByteArray(0)))
        assertNull(HotspotShare.open(keys, ByteArray(40)))
    }

    @Test fun `each seal is fresh, the tag is stable per code`() {
        assertFalse(HotspotShare.seal(keys, creds).contentEquals(HotspotShare.seal(keys, creds)))
        assertArrayEquals(keys.tag, HotspotShare.Keys("k7m2xq9pa4tz").tag)
        assertEquals(HotspotShare.TAG_BYTES, keys.tag.size)
        assertFalse(keys.tag.contentEquals(HotspotShare.Keys("another-code").tag))
    }

    @Test fun `the password never shows up in logs`() {
        assertFalse(creds.toString().contains(creds.passphrase))
    }
}
