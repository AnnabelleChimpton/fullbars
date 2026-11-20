package app.irlsupreme.twitch

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TwitchAuthTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun TestScope.auth(clientId: String = CLIENT_ID) =
        TwitchAuth(server.config(clientId), testHttp(), clock = { testScheduler.currentTime })

    private val deviceResponse = """
        {"device_code":"dev-secret","expires_in":1800,"interval":5,"user_code":"ABCDEFGH",
         "verification_uri":"https://www.twitch.tv/activate?public=true&device-code=ABCDEFGH"}
    """.trimIndent()

    private val tokenResponse = """
        {"access_token":"at-1","expires_in":14400,"refresh_token":"rt-1",
         "scope":["channel:read:stream_key","user:read:chat"],"token_type":"bearer"}
    """.trimIndent()

    private fun pending() = json(400, """{"status":400,"message":"authorization_pending"}""")

    @Test fun startDeviceFlowPostsClientIdAndScopes() = runTest {
        server.enqueue(json(200, deviceResponse))
        val code = auth().startDeviceFlow()

        val req = server.take()
        assertEquals("POST", req.method)
        assertEquals("/oauth2/device", req.url.encodedPath)
        assertEquals(CLIENT_ID, req.form()["client_id"])
        assertEquals(TwitchConfig.DEFAULT_SCOPES.joinToString(" "), req.form()["scopes"])
        assertEquals("ABCDEFGH", code.userCode)
        assertEquals(5, code.intervalSeconds)
        assertEquals(1_800_000L, code.expiresAtEpochMs)
        assertFalse("device code must not leak via toString", code.toString().contains("dev-secret"))
    }

    @Test fun deviceFlowPendingThenSuccess() = runTest {
        server.enqueue(json(200, deviceResponse))
        server.enqueue(pending())
        server.enqueue(pending())
        server.enqueue(json(200, tokenResponse))
        val auth = auth()
        val code = auth.startDeviceFlow()
        val token = auth.pollForToken(code)

        assertEquals("at-1", token.accessToken)
        assertEquals("rt-1", token.refreshToken)
        assertEquals(listOf("channel:read:stream_key", "user:read:chat"), token.scopes)
        assertEquals(15_000L, testScheduler.currentTime) // three polls, 5 s apart
        assertEquals(15_000L + 14_400_000L, token.expiresAtEpochMs)
        assertFalse(token.toString().contains("at-1"))
        assertFalse(token.toString().contains("rt-1"))

        server.take() // device
        val poll = server.take().form()
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", poll["grant_type"])
        assertEquals("dev-secret", poll["device_code"])
        assertEquals(CLIENT_ID, poll["client_id"])
        assertTrue(poll.containsKey("scopes"))
        assertNull("public client never sends a secret", poll["client_secret"])
    }

    @Test fun slowDownIncreasesInterval() = runTest {
        server.enqueue(json(200, deviceResponse))
        server.enqueue(json(400, """{"status":400,"message":"slow_down"}"""))
        server.enqueue(pending())
        server.enqueue(json(200, tokenResponse))
        val auth = auth()
        auth.pollForToken(auth.startDeviceFlow())
        // 5 s → slow_down → interval 10 s → pending → 10 s → success
        assertEquals(25_000L, testScheduler.currentTime)
    }

    @Test fun expiredTokenErrorStopsPolling() = runTest {
        server.enqueue(json(200, deviceResponse))
        server.enqueue(pending())
        server.enqueue(json(400, """{"status":400,"message":"expired_token"}"""))
        val auth = auth()
        try {
            auth.pollForToken(auth.startDeviceFlow())
            fail("expected DeviceCodeExpired")
        } catch (_: TwitchException.DeviceCodeExpired) {
        }
    }

    @Test fun invalidDeviceCodeIsTreatedAsExpired() = runTest {
        server.enqueue(json(200, deviceResponse))
        server.enqueue(json(400, """{"status":400,"message":"invalid device code"}"""))
        val auth = auth()
        try {
            auth.pollForToken(auth.startDeviceFlow())
            fail("expected DeviceCodeExpired")
        } catch (_: TwitchException.DeviceCodeExpired) {
        }
    }

    @Test fun localDeadlineExpiresCodeWithoutAnotherRequest() = runTest {
        server.enqueue(json(200, deviceResponse.replace("1800", "12")))
        server.enqueue(pending())
        server.enqueue(pending())
        val auth = auth()
        try {
            auth.pollForToken(auth.startDeviceFlow())
            fail("expected DeviceCodeExpired")
        } catch (_: TwitchException.DeviceCodeExpired) {
        }
        assertEquals(3, server.requestCount) // device + 2 polls; the third tick is past expiry
    }

    @Test fun accessDenied() = runTest {
        server.enqueue(json(200, deviceResponse))
        server.enqueue(json(400, """{"status":400,"message":"access_denied"}"""))
        val auth = auth()
        try {
            auth.pollForToken(auth.startDeviceFlow())
            fail("expected AccessDenied")
        } catch (_: TwitchException.AccessDenied) {
        }
    }

    @Test fun refreshWithoutClientSecret() = runTest {
        server.enqueue(json(200, tokenResponse.replace("at-1", "at-2").replace("rt-1", "rt-2")))
        val old = token(access = "at-1", refresh = "rt-1")
        val fresh = auth().refresh(old)

        val form = server.take().form()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("rt-1", form["refresh_token"])
        assertEquals(CLIENT_ID, form["client_id"])
        assertNull(form["client_secret"])
        assertEquals("at-2", fresh.accessToken)
        assertEquals("rt-2", fresh.refreshToken)
        assertEquals("1001", fresh.userId) // carried over
    }

    @Test fun invalidRefreshTokenRequiresSignIn() = runTest {
        server.enqueue(json(400, """{"status":400,"message":"Invalid refresh token"}"""))
        try {
            auth().refresh(token())
            fail("expected SignInRequired")
        } catch (_: TwitchException.SignInRequired) {
        }
    }

    @Test fun validateSuccessAndUnauthorized() = runTest {
        server.enqueue(json(200, """{"client_id":"$CLIENT_ID","login":"streamer","scopes":["user:read:chat"],"user_id":"1001","expires_in":5520}"""))
        server.enqueue(json(401, """{"status":401,"message":"invalid access token"}"""))
        val auth = auth()
        val v = auth.validate(token())
        assertEquals("1001", v.userId)
        assertEquals("streamer", v.login)
        assertEquals("OAuth access-1", server.take().headers["Authorization"])
        try {
            auth.validate(token())
            fail("expected Unauthorized")
        } catch (e: TwitchException.Unauthorized) {
            assertEquals("invalid access token", e.serverMessage)
        }
    }

    @Test fun missingClientIdFailsFastWithoutNetwork() = runTest {
        try {
            auth(clientId = "").startDeviceFlow()
            fail("expected MissingClientId")
        } catch (e: TwitchException.MissingClientId) {
            // Shown to people, so it says what to do instead (the local.properties hint lives in KDoc).
            assertEquals("Twitch sign-in isn't available in this build. Use your stream key instead.", e.userMessage())
        }
        assertEquals(0, server.requestCount)
    }

    /** A test client whose first [drops] requests fail in transit, like a weak signal while the user approves. */
    private fun droppingHttp(drops: Int): okhttp3.OkHttpClient {
        var left = drops
        return testHttp().newBuilder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/token") && left-- > 0) throw java.io.IOException("blip")
            chain.proceed(chain.request())
        }.build()
    }

    @Test fun pollingSurvivesADroppedRequest() = runTest {
        server.enqueue(json(200, deviceResponse))
        server.enqueue(pending())
        server.enqueue(json(200, tokenResponse))
        val auth = TwitchAuth(server.config(), droppingHttp(2), clock = { testScheduler.currentTime })
        val token = auth.pollForToken(auth.startDeviceFlow())
        assertEquals("at-1", token.accessToken)
        assertEquals(20_000L, testScheduler.currentTime) // two dropped polls, then pending, then the token
    }

    @Test fun pollingGivesUpAfterFiveDropsInARow() = runTest {
        server.enqueue(json(200, deviceResponse))
        val auth = TwitchAuth(server.config(), droppingHttp(100), clock = { testScheduler.currentTime })
        val code = auth.startDeviceFlow()
        try {
            auth.pollForToken(code)
            fail("expected Network")
        } catch (e: TwitchException.Network) {
            assertEquals("Couldn't reach Twitch. Check your connection and try again.", e.userMessage())
        }
        assertEquals(25_000L, testScheduler.currentTime)
    }

    @Test fun userMessagesArePlainAndNameTheNextStep() {
        assertEquals("Twitch is busy. Try again in a minute.", TwitchException.RateLimited(null).userMessage())
        assertEquals("Twitch had a problem. Try again in a moment.", TwitchException.Api(503, "down").userMessage())
        assertEquals("Twitch had a problem. Try again in a moment.", TwitchException.Protocol("bad json").userMessage())
        assertEquals("Please sign in to Twitch again.", TwitchException.Unauthorized("invalid").userMessage())
        assertEquals("Please sign in to Twitch again.", TwitchException.SignInRequired("expired").userMessage())
        assertEquals("You must verify your email", TwitchException.Forbidden("You must verify your email").userMessage())
        listOf(
            TwitchException.Network(java.io.IOException("x")), TwitchException.Api(500, null), TwitchException.Forbidden(null),
            TwitchException.DeviceCodeExpired(), TwitchException.AccessDenied(),
        ).forEach { e ->
            val m = e.userMessage()
            assertFalse(m, m.contains("Exception") || m.contains("API error"))
        }
    }

    @Test fun onlyNoSignalOrTwitchTroubleCountsAsTransient() {
        assertTrue(TwitchException.Network(java.io.IOException("x")).isTransient)
        assertTrue(TwitchException.RateLimited(null).isTransient)
        assertTrue(TwitchException.Api(502, null).isTransient)
        assertTrue(TwitchException.Protocol("html from a captive portal").isTransient)
        assertFalse(TwitchException.Api(400, null).isTransient)
        assertFalse(TwitchException.SignInRequired("expired").isTransient)
        assertFalse(TwitchException.Unauthorized(null).isTransient)
        assertFalse(TwitchException.MissingClientId().isTransient)
    }

    @Test fun storedTokenNeedsNoNetwork() = runTest {
        val session = TwitchSession(auth(), InMemoryTokenStore(token(expiresAt = 0)), clock = { testScheduler.currentTime })
        assertEquals("streamer", session.storedLogin())
        assertEquals("access-1", session.stored()!!.accessToken)
        assertNull(TwitchSession(auth(), InMemoryTokenStore()).storedLogin())
        assertEquals(0, server.requestCount)
    }

    @Test fun sessionRefreshesExpiringTokenAndClearsOnFailure() = runTest {
        val store = InMemoryTokenStore(token(access = "old", expiresAt = 0))
        val session = TwitchSession(auth(), store, clock = { testScheduler.currentTime })
        server.enqueue(json(200, tokenResponse))
        assertEquals("at-1", session.current().accessToken)
        assertEquals("at-1", store.load()!!.accessToken)

        server.enqueue(json(400, """{"status":400,"message":"Invalid refresh token"}"""))
        try {
            session.refreshAfterUnauthorized(store.load()!!)
            fail("expected SignInRequired")
        } catch (_: TwitchException.SignInRequired) {
        }
        assertNull(store.load())
    }

    @Test fun tokenJsonRoundTrip() {
        val t = token()
        assertEquals(t, TwitchToken.fromJson(t.toJson()))
    }
}
