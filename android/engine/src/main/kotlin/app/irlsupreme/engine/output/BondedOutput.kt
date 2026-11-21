package app.irlsupreme.engine.output

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import app.irlsupreme.engine.ConnectionPhase
import app.irlsupreme.engine.LinkInfo
import app.irlsupreme.engine.StreamConfig
import app.irlsupreme.engine.HelperHosting
import app.irlsupreme.engine.net.HelperHost
import app.irlsupreme.engine.net.LinkManager
import srtlakt.moblink.Moblink
import app.irlsupreme.engine.video.SlateEncoder
import app.irlsupreme.engine.video.Slates
import srtlakt.LinkPhase
import srtlakt.LinkPriority
import srtlakt.SrtlaConfig
import srtlakt.SrtlaEvent
import srtlakt.SenderStats
import srtlakt.SrtlaSender
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Combine connections": SRT caller -> srtla-kt (localhost) -> one link per network -> BRB server (srtla_rec).
 * Everything above the SRT caller is unchanged; this just changes where the encoded stream goes.
 *
 * [onConnected] asks for a keyframe (and does the engine's live-since bookkeeping, which tolerates repeats): called
 * when SRT (re)connects, and also when the server hears this phone again after a gap SRT never noticed (see
 * [ResumeDetector]). [cellularAsSecondary]: see [LinkManager]; off by default.
 */
class BondedOutput(
    context: Context,
    host: String,
    private val port: Int,
    streamId: String,
    latencyMs: Int,
    private val config: StreamConfig,
    private val onConnected: () -> Unit,
    passphrase: String? = null,
    helpers: HelperHosting? = null,
    cellularAsSecondary: Boolean = false,
) : StreamOutput {
    @Volatile private var helperHost: HelperHost? = null
    /** Whether the BRB server has ever answered a link's registration (REG2) this stream. */
    @Volatile private var heardFromServer = false
    /** When this phone first had a network that could look the server up (0 = not yet). */
    @Volatile private var ownLinksSinceMs = 0L
    /** Whether any link has been up this stream; before the first one, a short wait is connecting, not a failure. */
    @Volatile private var everLinked = false
    private val createdAtMs = System.currentTimeMillis()
    /**
     * Link events (up, down with the reason, group lost, refused) as the plain-words lines also logged here, for the
     * session log. Called on the bonding sender's thread: keep it quick.
     */
    @Volatile var onLinkEvent: ((String) -> Unit)? = null
    private val resume = ResumeDetector()
    private val sender = SrtlaSender(
        InetSocketAddress("127.0.0.1", 0),
        SrtlaConfig(),
        onEvent = { e ->
            val line = "srtla: ${describe(e)}"
            Log.i(TAG, line)
            onLinkEvent?.let { runCatching { it(line) } }
            if (e is SrtlaEvent.GroupRegistered || e is SrtlaEvent.LinkUp) heardFromServer = true
            if (e is SrtlaEvent.LinkUp) everLinked = true
            if (e is SrtlaEvent.LinkUp && e.afterOutage) resume.onLinkUpAfterOutage()
            if (e is SrtlaEvent.LinkDown) helperHost?.linkFailed(e.linkId)
        },
        log = { Log.i(TAG, it) },
        // Above default: a late wake-up of this thread delays every packet of every link.
        onThreadStart = { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) },
    ).start()
    private val linkManager = LinkManager(context, sender, host, port, cellularAsSecondary).also { it.start() }
    init {
        helperHost = helpers?.let { HelperHost(context, it, linkManager, host, port).also { h -> h.start() } }
    }
    @Volatile private var stopping = false
    // "live/ID" -> "slate/ID": a paired server only accepts streams carrying the pairing's publish ID.
    private val slateUploader = SlateUploader(context, host, port, latencyMs, "slate" + streamId.removePrefix("live"), passphrase)
    // The server's own Weak signal screen: shown while packets keep getting lost on the way to it.
    private val weakSlateUploader = SlateUploader(context, host, port, latencyMs, "slate-weak" + streamId.removePrefix("live"), passphrase)
    private var slateThread: Thread? = null
    private val srt = SrtOutput(
        "srt://127.0.0.1:${sender.localPort}/$streamId?latency=$latencyMs",
        config.width, config.height, config.fps, latencyMs, passphrase,
    ) {
        onConnected()
        sendSlateOnce()
    }

    /**
     * Shortly after first going live, encode this phone's slates and send them to the server, which loops them: Signal
     * lost during dropouts (it used to be BRB, which told viewers the streamer chose to step away), and Weak signal.
     * Their SPS/PPS match the live stream's exactly, so the server can splice them in without transcoding. Best
     * effort: if Signal lost won't encode, BRB goes instead (logged); if nothing can be sent, the server falls back to
     * its default slate (and says so in its events).
     */
    @Synchronized
    private fun sendSlateOnce() {
        if (slateThread != null || stopping) return
        startSlateUpload()
    }

    /**
     * Encodes and sends the slates again, when the server refused the last ones or found they don't match the live
     * picture. False (nothing started) while an upload is still running, before the first one, or while stopping.
     */
    @Synchronized
    fun resendSlate(): Boolean {
        val t = slateThread ?: return false
        if (stopping || t.isAlive) return false
        startSlateUpload()
        return true
    }

    private fun startSlateUpload() {
        slateThread = Thread({
            try {
                // Let the live stream settle first: its delivered rate steady for a while (or a timeout), so the
                // upload doesn't land in the bitrate controller's start-up ramp.
                val gate = SlateStartGate()
                val t0 = System.nanoTime()
                while (!stopping && !gate.update((System.nanoTime() - t0) / 1_000_000, sender.stats.deliveredBps)) {
                    Thread.sleep(SLATE_GATE_POLL_MS)
                }
                if (stopping) return@Thread
                Log.i(TAG, "slate upload starting ${(System.nanoTime() - t0) / 1_000_000} ms after going live")
                for ((card, uploader) in listOf(Slates.Card.SIGNAL_LOST to slateUploader, Slates.Card.WEAK_SIGNAL to weakSlateUploader)) {
                    if (stopping) return@Thread
                    val slate = encodeLogged(card) ?: if (card == Slates.Card.SIGNAL_LOST) {
                        Log.w(TAG, "slate SIGNAL_LOST didn't encode; sending BRB for dropouts instead")
                        if (stopping) return@Thread
                        encodeLogged(Slates.Card.BRB)
                    } else null
                    if (slate == null) { Log.w(TAG, "slate ${card.name} not encoded; the server keeps its current one"); continue }
                    // Paced at a small share of what the live stream sends, so it doesn't crowd the live links.
                    val cap = { SlatePacer.capFor(sender.stats.totalTxBitsPerSec) }
                    if (!uploader.upload(slate, cap) { stopping }) Log.w(TAG, "slate ${card.name} not sent; the server keeps its current one")
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                Log.w(TAG, "slate failed; the server keeps its current one", t)
            }
        }, "engine-slate").apply { isDaemon = true; start() }
    }

    /**
     * Encodes [card]'s slate, logging what it cost (the encode time and the GOP's size, which matter most at the
     * 1080p rung); null when it failed.
     */
    private fun encodeLogged(card: Slates.Card) = try {
        val t0 = System.nanoTime()
        val slate = SlateEncoder.encode(config, card)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val videoBytes = slate.video.sumOf { it.data.size }
        val kbps = videoBytes * 8L / 1000 * config.fps / maxOf(1, slate.video.size)
        Log.i(
            TAG,
            "slate ${card.name} ${config.width}x${config.height} encoded in $ms ms: ${slate.video.size} frames, " +
                "GOP ${videoBytes / 1024} KB video (~$kbps kbps), ${slate.bytes / 1024} KB with audio",
        )
        slate
    } catch (e: InterruptedException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "slate ${card.name} failed to encode", t)
        null
    }

    override val phase: ConnectionPhase get() = srt.phase
    private val deadZone = DeadZoneTimer(NO_LINKS_MS)
    /**
     * True once the server hasn't heard this phone on any link for [NO_LINKS_MS]: a dead zone. SRT itself still says
     * LIVE (it only talks to the localhost bonder, which drops packets silently), so this is for what the streamer is
     * shown and the alert gate only; the send path keeps using [phase] so short blips stay recoverable by SRT's
     * retransmission. Judged from the last proof the server hears us, not from links being CONNECTED: links stay
     * CONNECTED for up to 4 s of silence, which used to hold this off for ~6 s.
     */
    val noLinksForAWhile: Boolean
        get() {
            val st = sender.stats
            return deadZone.update(android.os.SystemClock.elapsedRealtime(), st.connectedLinks, st.freshestProofAgoMs)
        }

    /**
     * Watches for the server hearing this phone again after a gap and asks for a keyframe then (see [ResumeDetector]),
     * only while SRT is LIVE: on a reconnect, SRT's own success callback already asks.
     */
    private val watcher = Executors.newSingleThreadScheduledExecutor { Thread(it, "engine-bond-watch").apply { isDaemon = true } }.also {
        it.scheduleWithFixedDelay({
            try {
                val silent = sender.stats.links.mapNotNull { l -> l.proofAgoMs }.minOrNull()
                if (resume.update(System.nanoTime() / 1_000_000, silent) && !stopping && srt.phase == ConnectionPhase.LIVE) {
                    Log.i(TAG, "server hears us again: asking for a keyframe")
                    onConnected()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "bond watch failed", t)
            }
        }, RESUME_POLL_MS, RESUME_POLL_MS, TimeUnit.MILLISECONDS)
    }
    override val reconnectAttempt: Int get() = srt.reconnectAttempt
    override val sentBitrate: Long get() = sender.stats.totalTxBitsPerSec
    /**
     * With no link through to the server, that's the cause (SRT's own timeout would only echo it). It's the server's
     * side, not the phone's, when the phone's networks are fine but the server never answered on any of them.
     */
    override val lastFailure: String?
        get() {
            if (sender.stats.connectedLinks > 0) return srt.lastFailure
            val own = linkManager.ownLinkCount
            val now = System.currentTimeMillis()
            if (own == 0) ownLinksSinceMs = 0 else if (ownLinksSinceMs == 0L) ownLinksSinceMs = now
            return noLinkReason(
                own, heardFromServer, if (ownLinksSinceMs == 0L) 0 else now - ownLinksSinceMs, port,
                startingForMs = if (everLinked) Long.MAX_VALUE else now - createdAtMs,
            )
        }
    override val recoveredDrops: Int get() = srt.recoveredDrops

    override fun setVideoConfig(sps: ByteBuffer, pps: ByteBuffer) = srt.setVideoConfig(sps, pps)
    override fun setAudioConfig(sampleRate: Int, stereo: Boolean) = srt.setAudioConfig(sampleRate, stereo)
    override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = srt.sendVideo(buffer, info)
    override fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = srt.sendAudio(buffer, info)
    override fun congested(): Boolean = srt.congested()

    /** The bonding sender's latest stats (per-link delay, delivery, caps) for the bitrate controller. */
    fun senderStats(): SenderStats = sender.stats

    /** Frames RootEncoder's SRT client dropped from its own queue (it couldn't send them in time), video + audio. */
    val libraryDroppedFrames: Long get() = srt.libraryDroppedFrames

    private class DiagBase(val naks: Long, val overCap: Long, val atMs: Long)
    private val diagBase = HashMap<Int, DiagBase>()

    /**
     * Per-link health for logs and diagnostics. [LinkDiag.nakRate] and [LinkDiag.overCapDelta] are measured since the
     * previous call, so call this at a steady cadence (e.g. the 5 s session snapshot) from one place.
     */
    @Synchronized
    fun linkDiagnostics(): List<LinkDiag> {
        val now = System.nanoTime() / 1_000_000
        val stats = sender.stats.links.associateBy { it.id }
        val out = linkManager.labels.mapNotNull { l ->
            val s = stats[l.linkId] ?: return@mapNotNull null
            val base = diagBase[l.linkId]
            val secs = if (base == null) 0.0 else (now - base.atMs) / 1000.0
            diagBase[l.linkId] = DiagBase(s.naks, s.overCapSent, now)
            LinkDiag(
                linkId = l.linkId,
                kind = l.kind,
                name = l.name,
                up = s.phase == LinkPhase.CONNECTED,
                queueMs = s.queueDelayMs,
                nakRate = if (base == null || secs <= 0) 0.0 else (s.naks - base.naks) / secs,
                capPackets = s.capPackets,
                overCapDelta = if (base == null) 0 else s.overCapSent - base.overCap,
                localDrops = s.localDrops,
                bytesSent = s.bytesSent,
                proofAgoMs = s.proofAgoMs,
                priority = s.priority,
            )
        }
        diagBase.keys.retainAll(stats.keys)
        lastNakRate = out.associate { it.linkId to it.nakRate }
        return out.sortedBy { it.kind }
    }

    /** Each link's [LinkDiag.nakRate] from the last [linkDiagnostics] call, for [links]. */
    @Volatile private var lastNakRate: Map<Int, Double> = emptyMap()

    /** Per-link state for the live screen; the resend rate is from the last [linkDiagnostics] window. */
    override fun links(): List<LinkInfo> {
        val stats = sender.stats.links.associateBy { it.id }
        val helpers = helperHost?.helpers.orEmpty().associateBy { it.id }
        val naks = lastNakRate
        return linkManager.labels.map { l ->
            val s = stats[l.linkId]
            val h = linkManager.helperFor(l.linkId)?.let { helpers[it] }
            LinkInfo(
                kind = l.kind,
                name = l.name,
                up = s?.phase == LinkPhase.CONNECTED,
                kbps = (s?.txBitsPerSec ?: 0) / 1000,
                rttMs = s?.rttMs,
                batteryPercent = h?.batteryPercentage,
                hot = h?.thermal == Moblink.Thermal.RED,
                queueMs = s?.queueDelayMs ?: 0,
                nakRate = naks[l.linkId] ?: 0.0,
                capPackets = s?.capPackets ?: 0,
                overCap = s?.overCapSent ?: 0,
                proofAgoMs = s?.proofAgoMs,
                localDrops = s?.localDrops ?: 0,
                priority = (s?.priority ?: LinkPriority.PRIMARY).name.lowercase(),
                bytesSent = s?.bytesSent ?: 0,
            )
        }.sortedBy { it.kind }
    }

    override fun stop() {
        stopping = true
        watcher.shutdownNow()
        srt.stop()
        // The SRT client sends its SHUTDOWN asynchronously. Keep the links up briefly so it actually reaches the BRB
        // server: that clean close is how the server tells "streamer ended" (end Twitch now) from a dropout (show BRB).
        Thread.sleep(SHUTDOWN_GRACE_MS)
        // Helpers first (late link failures then find no host), and they finish before the link manager shuts down.
        val h = helperHost
        helperHost = null
        h?.stop()
        linkManager.stop()
        sender.close()
        slateThread?.join(SLATE_STOP_WAIT_MS) // it notices `stopping` within ~50 ms and closes its own connection
    }

    private fun describe(e: SrtlaEvent) = when (e) {
        is SrtlaEvent.GroupRegistered -> "group registered"
        SrtlaEvent.GroupLost -> "group lost (receiver forgot us), resetting SRT"
        is SrtlaEvent.LinkUp -> "link up ${e.label}"
        is SrtlaEvent.LinkDown -> "link down ${e.label} (${e.reason})"
        is SrtlaEvent.LinkRefused -> "link refused ${e.label}"
    }

    internal companion object {
        const val TAG = "Engine"
        /** REG1 retries run every few seconds: this long with no REG2 on any working network means the server is silent. */
        const val SERVER_SILENT_MS = 6_000L

        /** How long a stream that has never had a link up may wait for its first one before that's a failure. */
        const val FIRST_LINK_GRACE_MS = 3_000L

        /**
         * Why no link reaches the server, or null while a stream is still bringing its first link up
         * ([startingForMs] since it started, [Long.MAX_VALUE] once a link has been up): the first SRT attempt can
         * fail before any link has registered, and that's connecting, not an error to show.
         */
        fun noLinkReason(ownLinks: Int, heardFromServer: Boolean, ownLinksForMs: Long, port: Int, startingForMs: Long = Long.MAX_VALUE): String? = when {
            startingForMs < FIRST_LINK_GRACE_MS -> null
            ownLinks > 0 && !heardFromServer && ownLinksForMs >= SERVER_SILENT_MS -> "Server not answering on UDP $port (check its firewall)"
            else -> "No connection can reach the BRB server"
        }

        const val SHUTDOWN_GRACE_MS = 500L
        /** No connected link for this long counts as a dead zone (long enough to ignore one link's hand-over). */
        const val NO_LINKS_MS = 1_500L
        const val SLATE_GATE_POLL_MS = 500L
        const val SLATE_STOP_WAIT_MS = 1_500L
        const val RESUME_POLL_MS = 150L
    }
}

/**
 * Whether the server has heard this phone on no link for at least [afterMs] (pure, unit-tested). Remembers when it
 * last did; callers poll it every few hundred ms. Thread-safe: the state publisher and the alert loop both read it.
 *
 * With [freshestProofAgoMs] (time since any connected link last proved the server hears it), the clock starts at that
 * last proof, so the warning comes [afterMs] after the server stopped hearing us even while links are still
 * CONNECTED. Without it, a connected link counts as heard.
 */
internal class DeadZoneTimer(private val afterMs: Long) {
    private var linkSeenMs = 0L

    @Synchronized
    fun update(nowMs: Long, connectedLinks: Int, freshestProofAgoMs: Long? = null): Boolean {
        val heardAt = if (connectedLinks > 0) nowMs - (freshestProofAgoMs ?: 0L).coerceAtLeast(0) else null
        if (linkSeenMs == 0L) { linkSeenMs = heardAt ?: nowMs; return false }
        if (heardAt != null && heardAt > linkSeenMs) linkSeenMs = heardAt
        return nowMs - linkSeenMs >= afterMs
    }
}

/**
 * One link's health for logs and diagnostics ([BondedOutput.linkDiagnostics]).
 * [nakRate]: losses reported per second, and [overCapDelta]: packets pushed past its in-flight cap (every link was
 * full), both since the previous call; [localDrops]: packets its socket refused, and [bytesSent], since the stream
 * started; [proofAgoMs]: time since the server last proved it hears this link.
 */
data class LinkDiag(
    val linkId: Int,
    val kind: String,
    val name: String,
    val up: Boolean,
    val queueMs: Int,
    val nakRate: Double,
    val capPackets: Int,
    val overCapDelta: Long,
    val localDrops: Long,
    val bytesSent: Long,
    val proofAgoMs: Long?,
    val priority: LinkPriority,
)
