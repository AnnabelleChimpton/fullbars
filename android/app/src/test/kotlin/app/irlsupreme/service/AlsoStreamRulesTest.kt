package app.irlsupreme.service

import app.irlsupreme.ui.alsoStreamValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlsoStreamRulesTest {
    private val kickHost = "rtmps://fa723fc1b171.global-contribute.live-video.net"

    @Test fun `Kick's address gets the app path its ingest needs, with or without a trailing slash`() {
        val want = "$kickHost/app/sk_us_KEY"
        assertEquals(want, AlsoStreamRules.url(ExtraKind.KICK, ExtraDestination(true, kickHost, "sk_us_KEY")))
        assertEquals(want, AlsoStreamRules.url(ExtraKind.KICK, ExtraDestination(true, "$kickHost/", " sk_us_KEY ")))
        assertEquals(want, AlsoStreamRules.url(ExtraKind.KICK, ExtraDestination(true, "$kickHost/app/", "sk_us_KEY")))
    }

    @Test fun `YouTube only needs the key`() {
        assertEquals(
            "rtmps://a.rtmps.youtube.com/live2/abcd-efgh-ijkl",
            AlsoStreamRules.url(ExtraKind.YOUTUBE, ExtraDestination(true, "ignored", "abcd-efgh-ijkl")),
        )
        assertEquals("Add the stream key.", AlsoStreamRules.problem(ExtraKind.YOUTUBE, ExtraDestination(true)))
    }

    @Test fun `problems are named in plain words`() {
        assertEquals("Add the stream URL.", AlsoStreamRules.problem(ExtraKind.OTHER, ExtraDestination(true, "", "k")))
        assertNotNull(AlsoStreamRules.problem(ExtraKind.OTHER, ExtraDestination(true, "https://restream.io/live", "k")))
        assertNotNull(AlsoStreamRules.problem(ExtraKind.KICK, ExtraDestination(true, kickHost, "has space")))
        assertNotNull(AlsoStreamRules.problem(ExtraKind.KICK, ExtraDestination(true, kickHost, "a/b")))
        assertNull(AlsoStreamRules.problem(ExtraKind.OTHER, ExtraDestination(true, "rtmp://live.restream.io/live", "re_123")))
    }

    @Test fun `only destinations that are on and complete are sent, named for the server's status`() {
        val m = mapOf(
            ExtraKind.KICK to ExtraDestination(true, kickHost, "sk"),
            ExtraKind.YOUTUBE to ExtraDestination(false, "", "yt"),
            ExtraKind.OTHER to ExtraDestination(true, "rtmp://x.example/live", ""),
        )
        assertEquals(listOf("Kick" to "$kickHost/app/sk"), AlsoStreamRules.toSend(m))
        assertEquals(listOf("Kick", "Other server"), AlsoStreamRules.onNames(m))
    }

    @Test fun `the Settings row says what's on, or why it won't go`() {
        assertEquals("Off", alsoStreamValue(emptyList(), ready = true))
        assertEquals("Needs Combine connections", alsoStreamValue(listOf("Kick"), ready = false))
        assertEquals("Kick, YouTube", alsoStreamValue(listOf("Kick", "YouTube"), ready = true))
    }
}
