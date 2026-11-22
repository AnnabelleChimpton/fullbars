package app.irlsupreme.ui

import app.irlsupreme.engine.AudioInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlainFailureTest {
    @Test fun `no error, nothing to explain`() {
        assertNull(plainFailure(null, bonded = true))
        assertNull(plainFailure("  ", bonded = false))
    }

    @Test fun `the BRB server out of reach is named`() {
        assertEquals("Can't reach your server", plainFailure("No connection can reach the BRB server", bonded = false))
        assertEquals(
            "Your server refused the stream, try pairing again",
            plainFailure("BRB server rejected the stream", bonded = true),
        )
    }

    @Test fun `timeouts, refusals and DNS say whose server and what to check`() {
        val bonded = "Can't reach your server, check the address or signal"
        val direct = "Can't reach Twitch, check your signal"
        assertEquals(bonded, plainFailure("Connect timed out", bonded = true))
        assertEquals(bonded, plainFailure("failed to connect to /203.0.113.5 (port 5000): ECONNREFUSED (Connection refused)", bonded = true))
        assertEquals(direct, plainFailure("java.net.SocketTimeoutException: timeout", bonded = false))
        assertEquals(direct, plainFailure("Unable to resolve host \"live.twitch.tv\": No address associated with hostname", bonded = false))
        assertEquals(direct, plainFailure("connect failed: ENETUNREACH (Network is unreachable)", bonded = false))
    }

    @Test fun `a refused key is Twitch's`() {
        assertEquals("Twitch refused the stream key", plainFailure("stream key rejected", bonded = false))
    }

    @Test fun `a drop and the mic get their own words, anything else points at Details`() {
        assertEquals("The connection dropped", plainFailure("disconnected", bonded = true))
        assertEquals("Your mic isn't working", plainFailure("microphone unavailable", bonded = false))
        assertEquals("Tap for details", plainFailure("eglSwapBuffers failed 0x300d", bonded = false))
    }

    @Test fun `the first-connect hint waits for three tries`() {
        assertNull(firstConnectHint(0, bonded = true))
        assertNull(firstConnectHint(2, bonded = false))
        assertEquals("Is your server running?", firstConnectHint(3, bonded = true))
        assertEquals("Check your stream key and internet", firstConnectHint(7, bonded = false))
    }

    @Test fun `every audio problem has a reason`() {
        assertNull(audioProblem(AudioInput.OK))
        assertEquals("Mic in use by another app", audioProblem(AudioInput.SILENCED))
        assertEquals("Mic unavailable, sending silence", audioProblem(AudioInput.UNAVAILABLE))
        assertEquals("Mic reconnecting", audioProblem(AudioInput.FAILING))
    }
}
