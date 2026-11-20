package app.irlsupreme.engine.output

import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import app.irlsupreme.engine.ConnectionPhase
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer

/**
 * Publishes to an RTMP(S) URL using RootEncoder's protocol client (Apache-2.0); everything else is ours.
 *
 * Connects only once both codec configs are known, reconnects forever with capped backoff (the stream must not end
 * because the network blinked), and asks for a keyframe whenever it (re)connects so viewers resync immediately.
 * The URL contains the stream key and is never logged.
 */
class RtmpOutput(
    private val url: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onConnected: () -> Unit,
) : ConnectChecker, StreamOutput {
    private val client = RtmpClient(this)
    private val thread = HandlerThread("engine-rtmp").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile override var phase = ConnectionPhase.IDLE
        private set
    @Volatile override var reconnectAttempt = 0
        private set
    @Volatile override var sentBitrate = 0L
        private set
    @Volatile override var lastFailure: String? = null
        private set
    /** Times the connection dropped after being live and came back by itself (the recap's "kept you live" count). */
    @Volatile override var recoveredDrops = 0
        private set
    @Volatile private var everLive = false
    @Volatile private var stopping = false
    @Volatile private var videoReady = false
    @Volatile private var audioReady = false

    init {
        client.setReTries(Int.MAX_VALUE)
        // Off: Twitch's ingest doesn't answer the client's liveness check, which caused a reconnect every ~10 s
        // in the first Twitch test. Dead connections still surface as socket write failures.
        client.setCheckServerAlive(false)
    }

    override fun setVideoConfig(sps: ByteBuffer, pps: ByteBuffer) { handler.post {
        client.setVideoInfo(sps, pps, null)
        client.setVideoResolution(width, height)
        client.setFps(fps)
        videoReady = true
        maybeConnect()
    } }

    override fun setAudioConfig(sampleRate: Int, stereo: Boolean) { handler.post {
        client.setAudioInfo(sampleRate, stereo)
        audioReady = true
        maybeConnect()
    } }

    private fun maybeConnect() {
        if (stopping || phase != ConnectionPhase.IDLE || !videoReady || !audioReady) return
        phase = ConnectionPhase.CONNECTING
        client.connect(url)
    }

    /** Frames are dropped (not queued) while not connected; the encoder sends a keyframe on (re)connect. */
    override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (phase == ConnectionPhase.LIVE) client.sendVideo(buffer, info)
    }

    override fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (phase == ConnectionPhase.LIVE) client.sendAudio(buffer, info)
    }

    override fun congested(): Boolean = phase == ConnectionPhase.LIVE && runCatching { client.hasCongestion(30f) }.getOrDefault(false)

    override fun stop() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        client.disconnect()
        thread.quitSafely()
        phase = ConnectionPhase.IDLE
    }

    // --- ConnectChecker (called from RootEncoder's threads) ---

    override fun onConnectionStarted(url: String) = Unit

    override fun onConnectionSuccess() {
        if (everLive) recoveredDrops++
        everLive = true
        phase = ConnectionPhase.LIVE
        reconnectAttempt = 0
        lastFailure = null
        Log.i(TAG, "rtmp live")
        onConnected()
    }

    override fun onConnectionFailed(reason: String) {
        if (stopping) return
        lastFailure = reason
        reconnectAttempt++
        // Before the first success this is still the first connect, not a drop: viewers never saw the stream.
        phase = if (everLive) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING
        val delay = (500L shl (reconnectAttempt - 1).coerceAtMost(4)).coerceAtMost(8_000)
        Log.w(TAG, "rtmp failed (${reason.take(120)}); retry #$reconnectAttempt in ${delay}ms")
        handler.post { if (!stopping) client.reConnect(delay) }
    }

    override fun onDisconnect() {
        if (!stopping && phase == ConnectionPhase.LIVE) onConnectionFailed("disconnected")
    }

    override fun onAuthError() {
        lastFailure = "stream key rejected"
        phase = ConnectionPhase.FAILED
    }

    override fun onAuthSuccess() = Unit

    override fun onNewBitrate(bitrate: Long) {
        sentBitrate = bitrate
    }

    private companion object { const val TAG = "Engine" }
}
