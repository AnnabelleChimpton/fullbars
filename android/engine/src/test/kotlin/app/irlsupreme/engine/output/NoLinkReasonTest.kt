package app.irlsupreme.engine.output

import org.junit.Assert.assertEquals
import org.junit.Test

class NoLinkReasonTest {
    private val phone = "No connection can reach the BRB server"
    private val firewall = "Server not answering on UDP 5000 (check its firewall)"

    @Test fun `no working network is the phone's problem`() {
        assertEquals(phone, BondedOutput.noLinkReason(0, heardFromServer = false, ownLinksForMs = 0, port = 5000))
    }

    @Test fun `working networks and a silent server point at the server`() {
        assertEquals(phone, BondedOutput.noLinkReason(2, heardFromServer = false, ownLinksForMs = 1_000, port = 5000))
        assertEquals(firewall, BondedOutput.noLinkReason(2, heardFromServer = false, ownLinksForMs = 6_000, port = 5000))
    }

    @Test fun `a server that answered before isn't blamed for a dropout`() {
        assertEquals(phone, BondedOutput.noLinkReason(2, heardFromServer = true, ownLinksForMs = 60_000, port = 5000))
    }

    @Test fun `the first link coming up is connecting, not an error`() {
        assertEquals(null, BondedOutput.noLinkReason(2, heardFromServer = true, ownLinksForMs = 30, port = 5000, startingForMs = 30))
        assertEquals(phone, BondedOutput.noLinkReason(2, heardFromServer = true, ownLinksForMs = 3_000, port = 5000, startingForMs = 3_000))
        // Once a link has been up, a later dropout shows at once.
        assertEquals(phone, BondedOutput.noLinkReason(0, heardFromServer = true, ownLinksForMs = 0, port = 5000))
    }
}
