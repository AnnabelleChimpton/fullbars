package app.irlsupreme.engine.output

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import app.irlsupreme.engine.ConnectionPhase
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes to an RTMP(S) URL using RootEncoder's protocol client (Apache-2.0); everything else is ours.
 *
 * Connects only once both codec configs are known, reconnects forever with capped backoff (the stream must not end
 * because the network blinked), and asks for a keyframe whenever it (re)connects so viewers resync immediately.
 * The URL contains the stream key and is never logged.
 *
 * Around the client (the decisions themselves live in RtmpPolicies, which are tested without Android):
 *  - measures the send backlog as time ([queueDelayMs]) and what leaves the socket ([throughputBps]);
 *  - forwards no video after a (re)connect until a keyframe, drops non-key video when the backlog gets deep (rather
 *    than letting the client's full queue drop audio and reference frames at random), and flushes a hopeless backlog;
 *  - treats a live connection that sends nothing for 7 s as failed (a dead link doesn't always close the socket);
 *  - when given a [context], reconnects as soon as a new default network comes up instead of waiting out the backoff.
 *
 * Connection state changes all happen on the output's own thread; the client's callbacks are posted there.
 */
class RtmpOutput(
    private val url: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onConnected: () -> Unit,
    context: Context? = null,
    /** Asks the encoder for a keyframe. Without one, [onConnected] is called again (it requests a keyframe too). */
    private val onRequestKeyframe: (() -> Unit)? = null,
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
    @Volatile override var queueDelayMs = 0
        private set
    @Volatile override var throughputBps = 0L
        private set
    /** Frames dropped while live: ours (gated for a keyframe, dropped for backlog) and the client's own. */
    override val droppedFrames: Long get() = synchronized(lock) { policy.selfDropped } + clientDropped
    /** Bytes of media actually handed to the client (frames this output dropped aren't counted). */
    val bytesQueued: Long get() = queuedBytes.get()

    @Volatile private var everLive = false
    @Volatile private var stopping = false
    @Volatile private var videoReady = false
    @Volatile private var audioReady = false
    @Volatile private var lastFrameMs = 0L
    @Volatile private var clientDropped = 0L
    private val queuedBytes = AtomicLong()
    private val largestSinceTick = AtomicLong()

    // Frame decisions happen on the encoders' threads; the rest below only on the output's own thread.
    private val lock = Any()
    private val policy = RtmpFramePolicy()
    private val meter = RtmpMeter(fps)
    private val watchdog = StallWatchdog()
    private val backoff = ReconnectPolicy()
    private val networkTrigger = NetworkTrigger()
    /** A connect was started and hasn't reported back yet. */
    private var attemptInFlight = false
    /**
     * That connect hasn't reported starting yet. The client reports every try as started before it can fail, so a
     * failure arriving before that belongs to the connection before it (its socket erroring as it's torn down).
     */
    private var awaitingStart = false
    /** The network changed while an attempt was under way: if it fails, retry at once. */
    private var pendingNetworkRetry = false
    private var droppedAtMs = 0L
    private var lastClientVideoDrops = 0L
    private var lastClientAudioDrops = 0L

    private val retry = Runnable {
        if (stopping || phase == ConnectionPhase.LIVE || phase == ConnectionPhase.FAILED || attemptInFlight) return@Runnable
        beginAttempt()
        client.reConnect(0) // closes what's left of the old connection, then connects
    }
    /** Safety net: a try that never reports back (a lost callback) counts as failed. */
    private val attemptTimeout = Runnable {
        if (stopping || !attemptInFlight || phase == ConnectionPhase.LIVE) return@Runnable
        awaitingStart = false
        handleFailure("connect timed out")
    }
    private val requestKeyframe = Runnable { if (!stopping) (onRequestKeyframe ?: onConnected)() }
    private val tick = object : Runnable {
        override fun run() {
            if (stopping) return
            try {
                tickNow()
            } catch (t: Throwable) {
                Log.w(TAG, "rtmp tick failed; carrying on", t)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val connectivity: ConnectivityManager? = context?.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onNetworkChange(networkTrigger.onAvailable(network.networkHandle, now(), reconnecting()))
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            onNetworkChange(networkTrigger.onCapabilities(network.networkHandle, validated, now(), reconnecting()))
        }

        override fun onLost(network: Network) { networkTrigger.onLost(network.networkHandle) }
    }

    init {
        client.setReTries(Int.MAX_VALUE)
        // Off: Twitch's ingest doesn't answer the client's liveness check, which caused a reconnect every ~10 s
        // in the first Twitch test. Dead connections surface as socket write failures, or through the stall watchdog.
        client.setCheckServerAlive(false)
        handler.postDelayed(tick, TICK_MS)
        watchNetwork()
    }

    // The app declares ACCESS_NETWORK_STATE; without it this fails, is logged, and reconnects keep their backoff.
    @SuppressLint("MissingPermission")
    private fun watchNetwork() {
        val cm = connectivity ?: return
        runCatching { cm.registerDefaultNetworkCallback(networkCallback, handler) }
            .onFailure { Log.w(TAG, "rtmp: no network callback (${it.message})") }
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
        beginAttempt()
        client.connect(url)
    }

    private fun beginAttempt() {
        attemptInFlight = true
        awaitingStart = true
        handler.removeCallbacks(attemptTimeout)
        handler.postDelayed(attemptTimeout, ATTEMPT_TIMEOUT_MS)
    }

    /** Frames are dropped (not queued) while not connected; after a (re)connect video waits for a keyframe. */
    override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (phase != ConnectionPhase.LIVE) return
        val now = now()
        lastFrameMs = now
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val forward: Boolean
        val wantKey: Boolean
        synchronized(lock) {
            forward = policy.onVideo(now, key)
            wantKey = policy.takeKeyframeRequest()
        }
        if (wantKey) handler.post(requestKeyframe)
        if (!forward) return
        client.sendVideo(buffer, info)
        val size = info.size.toLong()
        queuedBytes.addAndGet(size)
        // The largest frame since the last tick, for the stall watchdog (a lock-free max: no allocation per frame).
        while (true) {
            val cur = largestSinceTick.get()
            if (size <= cur || largestSinceTick.compareAndSet(cur, size)) break
        }
    }

    override fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (phase != ConnectionPhase.LIVE) return
        lastFrameMs = now()
        val forward = synchronized(lock) { policy.onAudio() }
        if (!forward) return
        client.sendAudio(buffer, info)
        queuedBytes.addAndGet(info.size.toLong())
    }

    override fun congested(): Boolean = phase == ConnectionPhase.LIVE && runCatching { client.hasCongestion(30f) }.getOrDefault(false)

    override fun stop() {
        stopping = true
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        handler.removeCallbacksAndMessages(null)
        client.disconnect()
        thread.quitSafely()
        phase = ConnectionPhase.IDLE
    }

    /** Every [TICK_MS] on the output's thread: measure, police the backlog, watch for a stall. */
    private fun tickNow() {
        if (phase != ConnectionPhase.LIVE) {
            queueDelayMs = 0
            throughputBps = 0
            return
        }
        val now = now()
        val sent = client.bytesSend
        meter.update(now, sent, queuedBytes.get(), client.getQueueBytesOut(), client.getItemsInCache())
        queueDelayMs = meter.queueDelayMs
        throughputBps = meter.throughputBps
        countClientDrops()
        val action: RtmpFramePolicy.Action
        val wantKey: Boolean
        synchronized(lock) {
            action = policy.updateBacklog(now, queueDelayMs)
            wantKey = policy.takeKeyframeRequest()
        }
        if (action == RtmpFramePolicy.Action.FLUSH) {
            Log.w(TAG, "rtmp backlog ${queueDelayMs} ms: cleared, restarting from a keyframe")
            runCatching { client.clearCache() }
            queueDelayMs = 0
        }
        if (wantKey) requestKeyframe.run()
        val idleMs = watchdog.check(
            now,
            encoderProducing = now - lastFrameMs < PRODUCING_MS,
            bytesSent = sent,
            largestFrameBytes = largestSinceTick.getAndSet(0),
            throughputBps = meter.throughputBps,
        )
        if (idleMs >= 0) handleFailure("stalled: no bytes for ${idleMs / 1000} s")
    }

    /** The client's own drop counters restart with each connection; keep a running total. */
    private fun countClientDrops() {
        val v = runCatching { client.droppedVideoFrames }.getOrDefault(lastClientVideoDrops)
        val a = runCatching { client.droppedAudioFrames }.getOrDefault(lastClientAudioDrops)
        val dv = if (v < lastClientVideoDrops) v else v - lastClientVideoDrops
        val da = if (a < lastClientAudioDrops) a else a - lastClientAudioDrops
        lastClientVideoDrops = v
        lastClientAudioDrops = a
        if (dv + da > 0) clientDropped += dv + da
    }

    private fun reconnecting() = !stopping && (phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.RECONNECTING)

    /** A new or newly validated default network while (re)connecting: don't wait out the backoff. */
    private fun onNetworkChange(delayMs: Long) {
        if (delayMs < 0 || !reconnecting()) return
        backoff.resetBackoff()
        reconnectAttempt = 0
        if (attemptInFlight) { // let it finish (the socket timeout bounds it); if it fails, the retry comes at once
            pendingNetworkRetry = true
            return
        }
        Log.i(TAG, "rtmp: network changed; reconnecting in ${delayMs}ms")
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delayMs)
    }

    /** On the output's thread: a connection (or a try) failed. */
    private fun handleFailure(reason: String) {
        if (stopping || phase == ConnectionPhase.FAILED) return
        // The end of a connection already given up on (the watchdog fired, then its socket errored): nothing new.
        if (phase != ConnectionPhase.LIVE && (!attemptInFlight || awaitingStart)) {
            Log.d(TAG, "rtmp: ignoring a failure from an earlier connection (${reason.take(60)})")
            return
        }
        attemptInFlight = false
        handler.removeCallbacks(attemptTimeout)
        lastFailure = reason
        val now = now()
        if (phase == ConnectionPhase.LIVE) droppedAtMs = now
        var delay = backoff.onFailure(now)
        if (pendingNetworkRetry) {
            delay = 0
            pendingNetworkRetry = false
        }
        reconnectAttempt = backoff.attempt
        // Before the first success this is still the first connect, not a drop: viewers never saw the stream.
        phase = if (everLive) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING
        queueDelayMs = 0
        throughputBps = 0
        Log.w(TAG, "rtmp failed (${reason.take(120)}); retry #$reconnectAttempt in ${delay}ms")
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delay)
    }

    private fun now() = SystemClock.elapsedRealtime()

    // --- ConnectChecker (called from RootEncoder's threads; handled on ours) ---

    override fun onConnectionStarted(url: String) { handler.post { awaitingStart = false } }

    override fun onConnectionSuccess() { handler.post {
        if (stopping) return@post
        if (everLive) recoveredDrops++
        val now = now()
        val outage = if (everLive && droppedAtMs > 0) " after ${now - droppedAtMs} ms down" else ""
        everLive = true
        attemptInFlight = false
        awaitingStart = false
        pendingNetworkRetry = false
        handler.removeCallbacks(retry)
        handler.removeCallbacks(attemptTimeout)
        backoff.onSuccess()
        reconnectAttempt = 0
        lastFailure = null
        droppedAtMs = 0
        meter.reset()
        watchdog.onConnected(now)
        synchronized(lock) { policy.onConnected(now) }
        queueDelayMs = 0
        phase = ConnectionPhase.LIVE
        Log.i(TAG, "rtmp live$outage")
        onConnected()
    } }

    override fun onConnectionFailed(reason: String) { handler.post { handleFailure(reason) } }

    override fun onDisconnect() { handler.post {
        if (!stopping && phase == ConnectionPhase.LIVE) handleFailure("disconnected")
    } }

    override fun onAuthError() { handler.post {
        lastFailure = "stream key rejected"
        phase = ConnectionPhase.FAILED
        attemptInFlight = false
        handler.removeCallbacks(retry)
        handler.removeCallbacks(attemptTimeout)
    } }

    override fun onAuthSuccess() = Unit

    override fun onNewBitrate(bitrate: Long) {
        sentBitrate = bitrate
    }

    private companion object {
        const val TAG = "Engine"
        const val TICK_MS = 250L
        /** The encoders count as producing while a frame arrived within this long. */
        const val PRODUCING_MS = 2_000L
        /** Far beyond a slow TLS + RTMP handshake (the client's socket timeout is 5 s per step). */
        const val ATTEMPT_TIMEOUT_MS = 30_000L
    }
}
