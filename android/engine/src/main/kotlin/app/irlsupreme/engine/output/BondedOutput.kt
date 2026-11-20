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
import srtlakt.SrtlaConfig
import srtlakt.SrtlaEvent
import srtlakt.SenderStats
import srtlakt.SrtlaSender
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * "Combine connections": SRT caller -> srtla-kt (localhost) -> one link per network -> BRB server (srtla_rec).
 * Everything above the SRT caller is unchanged; this just changes where the encoded stream goes.
 */
class BondedOutput(
    context: Context,
    host: String,
    private val port: Int,
    streamId: String,
    latencyMs: Int,
    private val config: StreamConfig,
    onConnected: () -> Unit,
    passphrase: String? = null,
    helpers: HelperHosting? = null,
) : StreamOutput {
    @Volatile private var helperHost: HelperHost? = null
    /** Whether the BRB server has ever answered a link's registration (REG2) this stream. */
    @Volatile private var heardFromServer = false
    /** When this phone first had a network that could look the server up (0 = not yet). */
    @Volatile private var ownLinksSinceMs = 0L
    private val sender = SrtlaSender(
        InetSocketAddress("127.0.0.1", 0),
        SrtlaConfig(),
        onEvent = { e ->
            Log.i(TAG, "srtla: ${describe(e)}")
            if (e is SrtlaEvent.GroupRegistered || e is SrtlaEvent.LinkUp) heardFromServer = true
            if (e is SrtlaEvent.LinkDown) helperHost?.linkFailed(e.linkId)
        },
    ).start()
    private val linkManager = LinkManager(context, sender, host, port).also { it.start() }
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
        slateThread = Thread({
            try {
                Thread.sleep(SLATE_DELAY_MS) // let the live stream settle first
                if (stopping) return@Thread
                for ((card, uploader) in listOf(Slates.Card.SIGNAL_LOST to slateUploader, Slates.Card.WEAK_SIGNAL to weakSlateUploader)) {
                    if (stopping) return@Thread
                    val slate = encodeLogged(card) ?: if (card == Slates.Card.SIGNAL_LOST) {
                        Log.w(TAG, "slate SIGNAL_LOST didn't encode; sending BRB for dropouts instead")
                        if (stopping) return@Thread
                        encodeLogged(Slates.Card.BRB)
                    } else null
                    if (slate == null) { Log.w(TAG, "slate ${card.name} not encoded; the server keeps its current one"); continue }
                    if (!uploader.upload(slate) { stopping }) Log.w(TAG, "slate ${card.name} not sent; the server keeps its current one")
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
     * True once no link has reached the server for [NO_LINKS_MS]: a dead zone. SRT itself still says LIVE (it only
     * talks to the localhost bonder, which drops packets silently), so this is for what the streamer is shown and the
     * alert gate only; the send path keeps using [phase] so short blips stay recoverable by SRT's retransmission.
     */
    val noLinksForAWhile: Boolean get() = deadZone.update(android.os.SystemClock.elapsedRealtime(), sender.stats.connectedLinks)
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
            return noLinkReason(own, heardFromServer, if (ownLinksSinceMs == 0L) 0 else now - ownLinksSinceMs, port)
        }
    override val recoveredDrops: Int get() = srt.recoveredDrops

    override fun setVideoConfig(sps: ByteBuffer, pps: ByteBuffer) = srt.setVideoConfig(sps, pps)
    override fun setAudioConfig(sampleRate: Int, stereo: Boolean) = srt.setAudioConfig(sampleRate, stereo)
    override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = srt.sendVideo(buffer, info)
    override fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = srt.sendAudio(buffer, info)
    override fun congested(): Boolean = srt.congested()

    /** The bonding sender's latest stats (per-link delay, delivery, caps) for the bitrate controller. */
    fun senderStats(): SenderStats = sender.stats

    override fun links(): List<LinkInfo> {
        val stats = sender.stats.links.associateBy { it.id }
        val helpers = helperHost?.helpers.orEmpty().associateBy { it.id }
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
            )
        }.sortedBy { it.kind }
    }

    override fun stop() {
        stopping = true
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

        fun noLinkReason(ownLinks: Int, heardFromServer: Boolean, ownLinksForMs: Long, port: Int): String =
            if (ownLinks > 0 && !heardFromServer && ownLinksForMs >= SERVER_SILENT_MS) "Server not answering on UDP $port (check its firewall)"
            else "No connection can reach the BRB server"

        const val SHUTDOWN_GRACE_MS = 500L
        /** No connected link for this long counts as a dead zone (long enough to ignore one link's hand-over). */
        const val NO_LINKS_MS = 1_500L
        const val SLATE_DELAY_MS = 2_000L
        const val SLATE_STOP_WAIT_MS = 1_500L
    }
}

/**
 * Whether there have been no connected links for at least [afterMs] (pure, unit-tested). Remembers when a link was last
 * seen connected; callers poll it every few hundred ms. Thread-safe: the state publisher and the alert loop both read it.
 */
internal class DeadZoneTimer(private val afterMs: Long) {
    private var linkSeenMs = 0L

    @Synchronized
    fun update(nowMs: Long, connectedLinks: Int): Boolean {
        if (connectedLinks > 0 || linkSeenMs == 0L) { linkSeenMs = nowMs; return false }
        return nowMs - linkSeenMs >= afterMs
    }
}
