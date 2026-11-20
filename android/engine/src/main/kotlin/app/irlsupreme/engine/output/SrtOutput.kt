package app.irlsupreme.engine.output

import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import app.irlsupreme.engine.ConnectionPhase
import com.pedro.common.ConnectChecker
import com.pedro.srt.srt.SrtClient
import com.pedro.srt.srt.packets.control.handshake.EncryptionType
import java.nio.ByteBuffer

/**
 * SRT caller (RootEncoder's pure-Kotlin implementation, Apache-2.0: no NDK) muxing MPEG-TS. Used behind the SRTLA
 * bonder, which listens on localhost. Reconnects forever with capped backoff and asks for a keyframe on (re)connect.
 *
 * Tunnels: when every link dies the receiver forgets the bonding group; srtla-kt then sends this caller an
 * SRT SHUTDOWN, which surfaces here as "Shutdown received from server" and triggers an immediate re-handshake
 * through the new group instead of a 30 s idle timeout.
 */
class SrtOutput(
    private val url: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    latencyMs: Int,
    passphrase: String? = null,
    private val onConnected: () -> Unit,
) : ConnectChecker, StreamOutput {
    private val client = SrtClient(this)
    private val thread = HandlerThread("engine-srt").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile override var phase = ConnectionPhase.IDLE
        private set
    @Volatile override var reconnectAttempt = 0
        private set
    @Volatile override var sentBitrate = 0L
        private set
    @Volatile override var lastFailure: String? = null
        private set
    @Volatile override var recoveredDrops = 0
        private set
    @Volatile private var everLive = false
    @Volatile private var stopping = false
    @Volatile private var videoReady = false
    @Volatile private var audioReady = false

    init {
        client.setReTries(Int.MAX_VALUE)
        client.setLatency(latencyMs)
        client.setCheckServerAlive(false)
        if (passphrase != null) client.setPassphrase(passphrase, EncryptionType.AES128)
    }

    override fun setVideoConfig(sps: ByteBuffer, pps: ByteBuffer) {
        handler.post {
            client.setVideoInfo(sps, pps, null)
            videoReady = true
            maybeConnect()
        }
    }

    override fun setAudioConfig(sampleRate: Int, stereo: Boolean) {
        handler.post {
            client.setAudioInfo(sampleRate, stereo)
            audioReady = true
            maybeConnect()
        }
    }

    private fun maybeConnect() {
        if (stopping || phase != ConnectionPhase.IDLE || !videoReady || !audioReady) return
        phase = ConnectionPhase.CONNECTING
        client.connect(url)
    }

    override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (phase == ConnectionPhase.LIVE) client.sendVideo(buffer, info)
    }

    override fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (phase == ConnectionPhase.LIVE) client.sendAudio(buffer, info)
    }

    override fun congested(): Boolean = phase == ConnectionPhase.LIVE && runCatching { client.hasCongestion() }.getOrDefault(false)

    override fun stop() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        client.disconnect()
        thread.quitSafely()
        phase = ConnectionPhase.IDLE
    }

    override fun onConnectionStarted(url: String) = Unit

    override fun onConnectionSuccess() {
        if (everLive) recoveredDrops++
        everLive = true
        phase = ConnectionPhase.LIVE
        reconnectAttempt = 0
        lastFailure = null
        Log.i(TAG, "srt live")
        onConnected()
    }

    override fun onConnectionFailed(reason: String) {
        if (stopping) return
        lastFailure = reason
        reconnectAttempt++
        // Before the first success this is still the first connect, not a drop: viewers never saw the stream.
        phase = if (everLive) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING
        // Shutdown from the bonder means "group re-registered, re-handshake now": retry immediately.
        val delay = if (reason.contains("Shutdown")) 0L else (500L shl (reconnectAttempt - 1).coerceAtMost(4)).coerceAtMost(8_000)
        Log.w(TAG, "srt failed (${reason.take(120)}); retry #$reconnectAttempt in ${delay}ms")
        handler.post { if (!stopping) client.reConnect(delay) }
    }

    override fun onDisconnect() {
        if (!stopping && phase == ConnectionPhase.LIVE) onConnectionFailed("disconnected")
    }

    override fun onAuthError() {
        lastFailure = "BRB server rejected the stream"
        phase = ConnectionPhase.FAILED
    }

    override fun onAuthSuccess() = Unit

    override fun onNewBitrate(bitrate: Long) {
        sentBitrate = bitrate
    }

    private companion object { const val TAG = "Engine" }
}
