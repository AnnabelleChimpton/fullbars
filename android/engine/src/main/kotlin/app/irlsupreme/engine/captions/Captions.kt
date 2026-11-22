package app.irlsupreme.engine.captions

import android.content.Context
import android.os.SystemClock
import app.irlsupreme.engine.CaptionOptions
import app.irlsupreme.engine.CaptionStatus
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live captions for one stream: the recogniser ([LiveCaptioner]), what the strip says ([CaptionText]) and its drawing
 * ([CaptionDrawer]). Text and drawing happen on one thread of their own, so neither the main thread nor the render
 * thread waits on them; each new strip goes to [show] (the compositor takes the bitmap; null takes the strip down).
 */
@android.annotation.TargetApi(33)
internal class Captions(
    ctx: Context,
    sampleRate: Int,
    outW: Int,
    outH: Int,
    val options: CaptionOptions,
    private val show: (CaptionGraphic?) -> Unit,
    onStatus: (CaptionStatus, String?) -> Unit,
) {
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "captions") }
    private val text = CaptionText()
    private val drawer = CaptionDrawer(outW, outH, options.size, options.position)
    private var lastLines: List<String> = emptyList()
    @Volatile private var paused = false

    private val captioner = LiveCaptioner(
        ctx, sampleRate, options,
        onPartial = { p -> post { if (text.partial(p, now())) redraw() } },
        onSegment = { s -> post { if (text.segment(s, now())) redraw() } },
        onStatus = onStatus,
    )

    /** For the audio pipeline. */
    val tap: VoiceTap get() = captioner.tap
    val restarts: Int get() = captioner.restarts

    /** Debug builds: see [LiveCaptioner.feedClip]. */
    fun feedClip(pcm: ByteArray) = captioner.feedClip(pcm)

    fun start() {
        captioner.start()
        exec.scheduleWithFixedDelay({ runCatching { if (text.tick(now())) redraw() } }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    /** Takes the strip down now (the mic was muted); what was said doesn't come back. */
    fun clear() = post { if (text.clear()) redraw() }

    /** The heat ladder's top rung pauses captions; they resume when it steps down. */
    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        captioner.setPaused(p)
        if (p) clear()
    }

    fun stop() {
        captioner.stop()
        post { lastLines = emptyList(); show(null) }
        exec.shutdown()
    }

    private fun post(block: () -> Unit) {
        runCatching { exec.execute { runCatching(block) } } // after stop, late results are dropped
    }

    /** The captions thread: draws the strip again if its lines changed. */
    private fun redraw() {
        val lines = text.lines(CaptionGeometry.MAX_LINES, drawer.maxWidth, drawer::measure)
        if (lines == lastLines) return
        lastLines = lines
        show(drawer.draw(lines))
    }

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val TICK_MS = 500L
    }
}
