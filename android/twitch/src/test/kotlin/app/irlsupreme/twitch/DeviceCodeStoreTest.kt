package app.irlsupreme.twitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCodeStoreTest {
    @Test fun `a pending sign-in survives being written down and read back`() {
        val code = DeviceCode("dev-123", "ABCD-EFGH", "https://www.twitch.tv/activate?device-code=ABCDEFGH", 1_800_000L, 5, listOf("user:read:chat", "moderator:read:followers"))
        val back = DeviceCode.decode(code.encode())!!
        assertEquals(code.deviceCode, back.deviceCode)
        assertEquals(code.userCode, back.userCode)
        assertEquals(code.verificationUri, back.verificationUri)
        assertEquals(code.expiresAtEpochMs, back.expiresAtEpochMs)
        assertEquals(code.intervalSeconds, back.intervalSeconds)
        assertEquals(code.scopes, back.scopes)
    }

    @Test fun `junk reads as nothing`() {
        assertNull(DeviceCode.decode("not json"))
        assertNull(DeviceCode.decode("{}"))
    }
}
