package app.irlsupreme.engine.output

import android.content.Context
import android.util.Log
import app.irlsupreme.engine.net.LinkManager
import app.irlsupreme.engine.video.EncodedSlate
import com.pedro.common.ConnectChecker
import com.pedro.srt.srt.SrtClient
import com.pedro.srt.srt.packets.control.handshake.EncryptionType
import srtlakt.SrtlaConfig
import srtlakt.SrtlaSender
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends the phone-encoded BRB slate to the BRB server as a short, separate SRT stream with stream id [streamId]
 * ("slate", or "slate/PUBLISH_ID" for a paired server), bonded
 * over the same networks as the live stream (its own SRTLA group, so it never touches the live one). The server
 * keeps it and loops it during dropouts. A few hundred KB, once per stream.
 *
 * SRT live mode releases data at the receiver only after the latency window, so the connection is held open for
 * latency + margin before the clean close that tells the server the upload is complete.
 */
class SlateUploader(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val latencyMs: Int,
    private val streamId: String = "slate",
    private val passphrase: String? = null,
) {
    /** Blocks until done. Returns true when the whole slate was handed to SRT and the connection closed cleanly. */
    fun upload(slate: EncodedSlate, cancelled: () -> Boolean): Boolean {
        val sender = SrtlaSender(InetSocketAddress("127.0.0.1", 0), SrtlaConfig(), onEvent = {}).start()
        val links = LinkManager(context, sender, host, port).also { it.start() }
        val connected = AtomicReference(CountDownLatch(1))
        val failure = AtomicReference<String?>(null)
        val client = SrtClient(object : ConnectChecker {
            override fun onConnectionStarted(url: String) = Unit
            override fun onConnectionSuccess() = connected.get().countDown()
            override fun onConnectionFailed(reason: String) { failure.compareAndSet(null, reason); connected.get().countDown() }
            override fun onNewBitrate(bitrate: Long) = Unit
            override fun onDisconnect() = Unit
            override fun onAuthError() { failure.compareAndSet(null, "rejected"); connected.get().countDown() }
            override fun onAuthSuccess() = Unit
        })
        try {
            client.setLatency(latencyMs)
            client.setCheckServerAlive(false)
            if (passphrase != null) client.setPassphrase(passphrase, EncryptionType.AES128)
            client.setReTries(0)
            client.resizeCache((slate.video.size + slate.audio.size) * 2)
            client.setVideoInfo(ByteBuffer.wrap(slate.sps), ByteBuffer.wrap(slate.pps), null)
            client.setAudioInfo(slate.sampleRate, slate.stereo)
            // The handshake must not leave before a link is registered: the bonder has nowhere to send it yet, and
            // the SRT client doesn't resend it (first on-device test: "Poll timed out" with both links up 1 s later).
            val linkDeadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (sender.stats.connectedLinks == 0) {
                if (cancelled() || System.currentTimeMillis() > linkDeadline) {
                    Log.w(TAG, "slate upload: no link reached the BRB server")
                    return false
                }
                Thread.sleep(50)
            }
            // A few attempts: over several links a server ACK can overtake the handshake reply, which this SRT client
            // treats as fatal (second on-device test: "unexpected response type: Ack"). The live stream just retries.
            val url = "srt://127.0.0.1:${sender.localPort}/$streamId"
            var ok = false
            for (attempt in 1..CONNECT_ATTEMPTS) {
                if (cancelled()) return false
                val latch = CountDownLatch(1).also { connected.set(it) }
                failure.set(null)
                client.connect(url)
                ok = latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) && failure.get() == null
                if (ok) break
                Log.w(TAG, "slate upload: attempt $attempt couldn't connect (${failure.get() ?: "timeout"})")
                client.disconnect()
                Thread.sleep(RETRY_DELAY_MS)
            }
            if (!ok) return false

            // RootEncoder reports success before its sender loop runs and resets the send queue when it starts, so
            // frames queued straight away are silently discarded (on-device: the first 3-22 frames, IDR included).
            // Give it a moment, then pace like a fast live mux instead of one burst.
            Thread.sleep(START_DELAY_MS)
            client.resetSentVideoFrames(); client.resetSentAudioFrames()
            val t0 = System.nanoTime()
            var vi = 0
            var ai = 0
            while (vi < slate.video.size || ai < slate.audio.size) {
                if (cancelled()) return false
                val v = slate.video.getOrNull(vi)
                val a = slate.audio.getOrNull(ai)
                val nextIsVideo = a == null || (v != null && v.ptsUs <= a.ptsUs)
                val ptsUs = if (nextIsVideo) v!!.ptsUs else a!!.ptsUs
                val dueNs = t0 + ptsUs * 1000 / PACE_SPEEDUP
                val waitNs = dueNs - System.nanoTime()
                if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
                // +100 ms keeps every timestamp positive.
                if (nextIsVideo) {
                    client.sendVideo(v!!.buffer(), v.info().apply { presentationTimeUs += TS_OFFSET_US })
                    vi++
                } else {
                    client.sendAudio(a!!.buffer(), a.info().apply { presentationTimeUs += TS_OFFSET_US })
                    ai++
                }
            }
            val drainDeadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
            while (client.getItemsInCache() > 0) {
                if (cancelled() || System.currentTimeMillis() > drainDeadline) return false
                Thread.sleep(50)
            }
            // Wait out the receiver's latency window (plus retransmissions) before closing.
            val holdUntil = System.currentTimeMillis() + latencyMs + HOLD_MARGIN_MS
            while (System.currentTimeMillis() < holdUntil) {
                if (cancelled()) return false
                Thread.sleep(50)
            }
            val sentV = client.sentVideoFrames
            val sentA = client.sentAudioFrames
            if (sentV < slate.video.size) {
                Log.w(TAG, "slate: SRT client sent only $sentV/${slate.video.size} video frames")
                return false
            }
            Log.i(TAG, "slate sent: $sentV video + $sentA audio frames, ${slate.bytes / 1024} KB")
            return true
        } finally {
            client.disconnect()
            Thread.sleep(SHUTDOWN_GRACE_MS) // let the SRT SHUTDOWN leave before the links go (see BondedOutput.stop)
            links.stop()
            sender.close()
        }
    }

    private companion object {
        const val TAG = "Engine"
        const val TS_OFFSET_US = 100_000L
        const val START_DELAY_MS = 500L
        const val PACE_SPEEDUP = 4
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val CONNECT_ATTEMPTS = 4
        const val RETRY_DELAY_MS = 300L
        const val DRAIN_TIMEOUT_MS = 10_000L
        const val HOLD_MARGIN_MS = 1_000L
        const val SHUTDOWN_GRACE_MS = 500L
    }
}
