package app.irlsupreme.twitch

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TwitchIngestTest {
    private val server = MockWebServer()
    private val key = StreamKey("live_44322889_a34ub37c8ajv98a0")

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    @Test fun buildsRtmpsUrlOnPort443() {
        val url = buildPublishUrl(key)
        assertEquals("rtmps://ingest.global-contribute.live-video.net:443/app/live_44322889_a34ub37c8ajv98a0", url.url)
        assertEquals("rtmps://ingest.global-contribute.live-video.net:443/app", url.serverUrl)
        assertEquals("live_44322889_a34ub37c8ajv98a0", url.streamName)
        assertFalse("publish URL must not leak the key via toString", url.toString().contains("live_4432"))
    }

    @Test fun bandwidthTestAppendsQueryToKey() {
        val url = buildPublishUrl(key, bandwidthTest = true)
        assertEquals("rtmps://ingest.global-contribute.live-video.net:443/app/live_44322889_a34ub37c8ajv98a0?bandwidthtest=true", url.url)
        assertEquals("live_44322889_a34ub37c8ajv98a0?bandwidthtest=true", url.streamName)
        assertTrue(url.bandwidthTest)
    }

    @Test fun upgradesPlainTemplateWhenNoSecureTemplate() {
        val ingest = IngestServer(1, "US West", "rtmp://usw20.contribute.live-video.net/app/{stream_key}", null, 1)
        assertEquals("rtmps://usw20.contribute.live-video.net:443/app/live_1_a", buildPublishUrl(StreamKey("live_1_a"), ingest).url)
    }

    @Test fun keepsExplicitPort() {
        val ingest = IngestServer(9, "X", "", "rtmps://host.example:4443/app/{stream_key}", 9)
        assertEquals("rtmps://host.example:4443/app/k", buildPublishUrl(StreamKey("k"), ingest).url)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsKeyWithQueryCharacters() {
        buildPublishUrl(StreamKey("live_1?evil=1"))
    }

    @Test fun listsAndPicksDefaultIngest() = runTest {
        server.enqueue(json(200, """
            {"ingests":[
              {"_id":1,"availability":1.0,"default":false,"name":"US West (Oregon)","url_template":"rtmp://usw20.contribute.live-video.net/app/{stream_key}","url_template_secure":"rtmps://usw20.contribute.live-video.net/app/{stream_key}","priority":1},
              {"_id":0,"availability":1.0,"default":false,"name":"Default","url_template":"rtmp://ingest.global-contribute.live-video.net/app/{stream_key}","url_template_secure":"rtmps://ingest.global-contribute.live-video.net/app/{stream_key}","priority":0}
            ]}
        """.trimIndent()))
        val ingest = TwitchIngest(server.config(), testHttp())
        val picked = ingest.pickIngest()
        assertEquals(0, picked.id)
        assertEquals("rtmps://ingest.global-contribute.live-video.net/app/{stream_key}", picked.urlTemplateSecure)
        assertEquals(null, server.take().headers["Authorization"]) // ingest list is unauthenticated
    }

    @Test fun pickFallsBackToGlobalOnError() = runTest {
        server.enqueue(json(500, "{}"))
        assertEquals(IngestServer.GLOBAL, TwitchIngest(server.config(), testHttp()).pickIngest())
    }
}
