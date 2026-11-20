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
 * keeps it and loops it during dropouts. Once per stream.
 *
 * Size: one GOP encoded at the stream's own bitrate (so its SPS/PPS match the live stream's), about bitrate x GOP
 * length: ~1.5 MB at 6 Mbps with 2 s keyframes. BondedOutput logs the real figure when it encodes ("slate ... GOP
 * N KB"). That is far too much to send in a burst over the live stream's own links, so the upload is paced by bytes
 * at a small fraction of the live rate (see [SlatePacer]; frame by frame, so the keyframe itself still leaves in one
 * burst); the server keeps its default slate until it completes.
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
    /**
     * Blocks until done. Returns true when the whole slate was handed to SRT and the connection closed cleanly.
     * [capBps]: the rate to send at, read before every frame (the live stream's rate changes); never below
     * [SlatePacer.MIN_BPS].
     */
    fun upload(slate: EncodedSlate, capBps: () -> Int = { DEFAULT_CAP_BPS }, cancelled: () -> Boolean): Boolean {
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
            // Give it a moment, then hand frames over in PTS order, paced by their size.
            Thread.sleep(START_DELAY_MS)
            client.resetSentVideoFrames(); client.resetSentAudioFrames()
            val pacer = SlatePacer()
            val t0 = System.nanoTime()
            var vi = 0
            var ai = 0
            while (vi < slate.video.size || ai < slate.audio.size) {
                if (cancelled()) return false
                val v = slate.video.getOrNull(vi)
                val a = slate.audio.getOrNull(ai)
                val nextIsVideo = a == null || (v != null && v.ptsUs <= a.ptsUs)
                // Sleep in short steps so a stream that ends mid-upload stops it promptly.
                while (true) {
                    val waitNs = pacer.waitNs(System.nanoTime())
                    if (waitNs <= 0) break
                    if (cancelled()) return false
                    val stepNs = minOf(waitNs, MAX_SLEEP_NS)
                    Thread.sleep(stepNs / 1_000_000, (stepNs % 1_000_000).toInt())
                }
                // +100 ms keeps every timestamp positive.
                val bytes = if (nextIsVideo) {
                    client.sendVideo(v!!.buffer(), v.info().apply { presentationTimeUs += TS_OFFSET_US })
                    vi++
                    v.data.size
                } else {
                    client.sendAudio(a!!.buffer(), a.info().apply { presentationTimeUs += TS_OFFSET_US })
                    ai++
                    a.data.size
                }
                pacer.sent(System.nanoTime(), bytes, capBps())
            }
            Log.i(TAG, "slate fed in ${(System.nanoTime() - t0) / 1_000_000} ms at ~${capBps() / 1000} kbps")
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
        const val MAX_SLEEP_NS = 100_000_000L
        /** Without a live rate to go by: gentle on any link the app would bond. */
        const val DEFAULT_CAP_BPS = 1_000_000
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val CONNECT_ATTEMPTS = 4
        const val RETRY_DELAY_MS = 300L
        const val DRAIN_TIMEOUT_MS = 10_000L
        const val HOLD_MARGIN_MS = 1_000L
        const val SHUTDOWN_GRACE_MS = 500L
    }
}

/**
 * Paces the slate upload by bytes (pure, unit-tested): each frame may only go once the previous ones have had their
 * time at the cap, `sentBits / capBps`. The slate is one GOP at the stream's full bitrate (~1.5 MB at 6 Mbps); sent at
 * a multiple of real time over the same links as the live stream during its first seconds, it filled their queues and
 * the live bitrate controller backed off for it. Never catches up after a late wake-up (that would be a burst): a frame
 * handed over late pushes the schedule back instead.
 *
 * The pacing is per frame, not per datagram: a frame is handed to the SRT client whole and leaves as one burst, and
 * only the gap after it is stretched. The card's first frame (the keyframe, often a large share of the GOP) still
 * goes out in one go; what this prevents is the whole GOP going out at once.
 */
internal class SlatePacer(private val minBps: Int = MIN_BPS) {
    private var nextDueNs = Long.MIN_VALUE

    /** How long to wait before handing over the next frame. */
    fun waitNs(nowNs: Long): Long = if (nextDueNs == Long.MIN_VALUE) 0 else maxOf(0, nextDueNs - nowNs)

    /** A frame of [bytes] was handed over at [nowNs], to be sent at [capBps]. */
    fun sent(nowNs: Long, bytes: Int, capBps: Int) {
        val start = if (nextDueNs == Long.MIN_VALUE) nowNs else maxOf(nextDueNs, nowNs)
        nextDueNs = start + bytes * 8L * 1_000_000_000L / maxOf(minBps, capBps)
    }

    companion object {
        const val MIN_BPS = 500_000

        /** The rate for the slate given what the live stream sends: about a sixth of it, never under [MIN_BPS]. */
        fun capFor(liveTxBps: Long): Int = maxOf(MIN_BPS.toLong(), liveTxBps * LIVE_SHARE_PERCENT / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        private const val LIVE_SHARE_PERCENT = 18L
    }
}

/**
 * When to start the slate upload (pure, unit-tested): once the live stream's delivered rate has held steady for
 * [stableMs], or after [timeoutMs] regardless. Started at a fixed 2 s, the upload landed in the bitrate controller's
 * start-up ramp, when the links' capacity is still being discovered. The rate is smoothed over ~2 s (keyframes make
 * single half-second readings jump) and counts as steady while it stays within [tolerance] of where the steady
 * stretch began. Sample it every ~500 ms.
 */
internal class SlateStartGate(
    private val stableMs: Long = 10_000,
    private val timeoutMs: Long = 25_000,
    private val tolerance: Double = 0.2,
) {
    private var startMs = Long.MIN_VALUE
    private var smoothed = -1.0
    private var anchor = 0.0
    private var steadySinceMs = Long.MIN_VALUE

    /** True once it is time to upload. */
    fun update(nowMs: Long, deliveredBps: Long): Boolean {
        if (startMs == Long.MIN_VALUE) startMs = nowMs
        if (nowMs - startMs >= timeoutMs) return true
        if (deliveredBps <= 0) {
            smoothed = -1.0
            steadySinceMs = Long.MIN_VALUE
            return false
        }
        smoothed = if (smoothed < 0) deliveredBps.toDouble() else smoothed * 0.75 + deliveredBps * 0.25
        if (steadySinceMs == Long.MIN_VALUE || kotlin.math.abs(smoothed - anchor) > anchor * tolerance) {
            anchor = smoothed
            steadySinceMs = nowMs
        }
        return nowMs - steadySinceMs >= stableMs
    }
}
