package app.irlsupreme.engine.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.irlsupreme.engine.gl.GlUtil
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * An animated GIF looping behind a phone-drawn card (Settings › Scene cards › Background). The compositor draws this
 * texture full-frame (cropped to fill, see [GifPlan.coverMatrix]), dims it toward Ink and lays the card's
 * [CardLayer.FOREGROUND] on top, with the cards' usual grain, pulse and cursor.
 *
 * Why a worker: decoding a GIF frame can take tens of milliseconds and the render thread has 33 ms per tick for
 * everything, so frames are decoded on [worker] (background priority, never competing with the encoders) into one of
 * two reused bitmaps, and the render thread only uploads the newest one ([update]) with a lock it never waits for.
 *
 * Memory (stated so it stays bounded): the GIF is decoded at most [GifPlan.MAX_PIXELS] (1280x720) and never larger
 * than the output or the GIF itself. At that cap each frame is 3.7 MB: two reused bitmaps (7.4 MB), the decoder's
 * own frame buffers (about three frames, ~11 MB) and one GL texture (3.7 MB), so **at most ~22 MB per GIF**, and the
 * compositor keeps at most two alive (only while crossfading between two GIF cards). Most GIFs are far smaller and
 * cost proportionally less. Files over [GifPlan.MAX_FILE_BYTES] are refused.
 *
 * Anything that goes wrong (unreadable file, not an image, a decoder error mid-loop) puts it in [State.FAILED], and
 * the compositor draws the normal card instead: never a black or frozen frame.
 *
 * Created, updated and released on the render thread (which owns the texture).
 */
internal class GifBackground(private val file: File, private val outWidth: Int, private val outHeight: Int) {
    enum class State { LOADING, PLAYING, FAILED }

    @Volatile var state = State.LOADING
        private set

    /** The GL texture (0 until the first frame is uploaded). Render thread only. */
    var texture = 0
        private set
    /** Crop-to-fill (and the bitmap's V flip) for the decoded size; set with the first upload. */
    var texMatrix: FloatArray = GlUtil.FLIP_V
        private set

    /**
     * The fastest the worker redraws, in ms (the GIF's own timing is followed when it's slower). Set by the compositor
     * from the thermal ladder: [GifPlan.MIN_INTERVAL_MS] (15 fps) normally, [GifPlan.HOT_INTERVAL_MS] when hot.
     */
    @Volatile var minIntervalMs = GifPlan.MIN_INTERVAL_MS

    private val lock = ReentrantLock()
    /** The newest whole frame, swapped in by the worker under [lock]. */
    private var front: Bitmap? = null
    @Volatile private var fresh = false
    @Volatile private var stopped = false
    private var uploadedW = 0
    private var uploadedH = 0
    private val worker = Thread(::run, "engine-card-gif").apply { isDaemon = true }

    init {
        worker.start()
    }

    /**
     * Render thread, each tick it's shown: uploads the newest frame if there is one and the worker isn't mid-swap
     * (tryLock: the render thread never waits). Returns true when there's a frame to draw.
     */
    fun update(): Boolean {
        if (state == State.FAILED) return false
        if (fresh && lock.tryLock()) {
            try {
                val b = front
                if (b != null && !b.isRecycled) {
                    if (texture == 0) {
                        texture = GlUtil.textureFromBitmap(b)
                    } else {
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                        if (b.width == uploadedW && b.height == uploadedH) GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, b)
                        else GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
                    }
                    if (b.width != uploadedW || b.height != uploadedH) {
                        uploadedW = b.width; uploadedH = b.height
                        texMatrix = GifPlan.coverMatrix(b.width, b.height, outWidth, outHeight)
                    }
                }
                fresh = false
            } finally {
                lock.unlock()
            }
        }
        return texture != 0 && state == State.PLAYING
    }

    /** Render thread: stops the worker (without waiting for it) and frees the texture. */
    fun release() {
        stopped = true
        worker.interrupt()
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
    }

    private fun run() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        var back: Bitmap? = null
        var drawable: Drawable? = null
        try {
            val len = file.length()
            if (len <= 0L || len > GifPlan.MAX_FILE_BYTES) throw IOException("GIF is ${len / 1024} KB (empty or over the limit)")
            var size: GifPlan.Size? = null
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val s = GifPlan.decodeSize(info.size.width, info.size.height, outWidth, outHeight)
                    ?: throw IOException("GIF has no usable size")
                decoder.setTargetSize(s.width, s.height)
                // A one-frame GIF comes back as a plain bitmap drawable; a hardware bitmap can't be drawn on our canvas.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                size = s
            }
            drawable = d
            val s = size ?: throw IOException("GIF header never decoded")
            if (stopped) return
            val a = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
            val b = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
            back = b
            lock.lock()
            try { front = a } finally { lock.unlock() }
            val canvases = HashMap<Bitmap, Canvas>(2).apply { put(a, Canvas(a)); put(b, Canvas(b)) }
            d.setBounds(0, 0, s.width, s.height)
            // AnimatedImageDrawable asks its callback to redraw at the next frame's due time (uptime ms): that's how we
            // follow the GIF's own timing. Single-frame GIFs decode to a plain drawable and are drawn once.
            var nextDueMs = -1L
            val anim = d as? AnimatedImageDrawable
            anim?.apply {
                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                callback = object : Drawable.Callback {
                    override fun invalidateDrawable(who: Drawable) {}
                    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { nextDueMs = `when` }
                    override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
                }
                start()
            }
            var lastDrawMs = 0L
            while (!stopped) {
                val bm = back ?: break
                nextDueMs = -1L
                bm.eraseColor(StreamPalette.INK) // transparent GIF pixels show Ink, like the card's own field
                d.draw(canvases.getValue(bm))
                lastDrawMs = SystemClock.uptimeMillis()
                lock.lock()
                try {
                    back = front
                    front = bm
                    fresh = true
                } finally {
                    lock.unlock()
                }
                if (state == State.LOADING) state = State.PLAYING
                val nowMs = SystemClock.uptimeMillis()
                // No callback but still running (the platform didn't schedule this software draw): keep sampling at
                // the frame cap, since the drawable picks the frame for the current time itself.
                val due = when {
                    anim == null -> -1L
                    nextDueMs >= 0 -> GifPlan.dueUptimeMs(nextDueMs, nowMs)
                    anim.isRunning -> lastDrawMs + minIntervalMs
                    else -> -1L
                }
                val next = GifPlan.nextDrawAtMs(nowMs, due, lastDrawMs, minIntervalMs)
                if (next < 0) {
                    // Nothing more to animate (one frame): the texture stays as it is until the card goes.
                    while (!stopped) Thread.sleep(60_000)
                    break
                }
                val wait = next - SystemClock.uptimeMillis()
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (_: InterruptedException) {
            // released
        } catch (t: Throwable) {
            // Not a GIF, a damaged file, out of memory: the normal card stands in. The file's name stays out of the log.
            if (!stopped) Log.w(TAG, "card background GIF couldn't play (${t.javaClass.simpleName}); showing the normal card", t)
            state = State.FAILED
        } finally {
            (drawable as? AnimatedImageDrawable)?.let { runCatching { it.stop(); it.callback = null } }
            lock.lock()
            try {
                // The render thread only touches [front] under the lock and never after FAILED or release, so these go now.
                if (stopped || state == State.FAILED) {
                    front?.recycle(); front = null
                    fresh = false
                }
                back?.recycle()
            } finally {
                lock.unlock()
            }
        }
    }

    private companion object {
        const val TAG = "Engine"
    }
}

/** The pure parts of [GifBackground]: how big to decode, when to draw the next frame, and how to crop. Tested. */
internal object GifPlan {
    /** The most pixels a GIF is decoded at: 1280x720 (3.7 MB a frame). Behind the dim and grain, more isn't seen. */
    const val MAX_PIXELS = 1280 * 720
    /** The shorter side's cap: a card background never needs more than 720p. */
    const val MAX_SHORT_SIDE = 720
    /** ~15 fps: GIFs rarely run faster, and every frame is an upload on the render thread. */
    const val MIN_INTERVAL_MS = 66L
    /** Thermal rung 2+: ~5 fps, so a hot phone isn't decoding for a background. */
    const val HOT_INTERVAL_MS = 200L
    /** A GIF's next frame is never waited on longer than this (a bogus delay can't freeze the loop for good). */
    const val MAX_WAIT_MS = 5_000L
    /** Files bigger than this are refused before decoding (a GIF this big is a video, and decoding it would cost seconds). */
    const val MAX_FILE_BYTES = 32L * 1024 * 1024

    data class Size(val width: Int, val height: Int)

    /**
     * The size to decode a [srcW]x[srcH] GIF at, for an [outW]x[outH] output: just enough to cover the output (capped to
     * 720p on the short side), never larger than the GIF itself (it's upscaled by the GPU for free), and never more
     * than [MAX_PIXELS] whatever its shape. Null when the GIF or the output has no size.
     */
    fun decodeSize(srcW: Int, srcH: Int, outW: Int, outH: Int): Size? {
        if (srcW <= 0 || srcH <= 0 || outW <= 0 || outH <= 0) return null
        val outShort = minOf(outW, outH)
        val capScale = minOf(1.0, MAX_SHORT_SIDE.toDouble() / outShort)
        val capW = outW * capScale
        val capH = outH * capScale
        // Cover: the GIF's smaller ratio to the (capped) output decides, so it still fills the frame after cropping.
        val cover = maxOf(capW / srcW, capH / srcH)
        val pixels = sqrt(MAX_PIXELS.toDouble() / (srcW.toDouble() * srcH))
        val scale = minOf(1.0, cover, pixels)
        return Size((srcW * scale).roundToInt().coerceAtLeast(1), (srcH * scale).roundToInt().coerceAtLeast(1))
    }

    /**
     * The next frame's due time as uptime ms. Drawable callbacks are documented to pass an uptime ([whenMs]), but a
     * value far below [nowMs] can only be a delay from now (uptime is hours on a phone that's streaming), so it's read
     * as one rather than as "long overdue". Negative stays negative (nothing scheduled).
     */
    fun dueUptimeMs(whenMs: Long, nowMs: Long): Long = when {
        whenMs < 0 -> -1
        whenMs + RELATIVE_GUARD_MS < nowMs -> nowMs + whenMs
        else -> whenMs
    }

    /** See [dueUptimeMs]: a "due" time this far in the past is a delay, not a time. */
    const val RELATIVE_GUARD_MS = 60_000L

    /**
     * When (uptime ms) to draw the next frame: when the GIF says the next one is due ([dueMs]), but no sooner than
     * [minIntervalMs] after the last draw ([lastDrawMs]) and no later than [MAX_WAIT_MS] from [nowMs]. -1 when nothing
     * is due ([dueMs] < 0: a single frame, or the animation stopped), so the last frame simply stays.
     */
    fun nextDrawAtMs(nowMs: Long, dueMs: Long, lastDrawMs: Long, minIntervalMs: Long): Long {
        if (dueMs < 0) return -1
        return maxOf(dueMs, lastDrawMs + minIntervalMs).coerceAtMost(nowMs + MAX_WAIT_MS)
    }

    /**
     * The texture matrix that crops a [srcW]x[srcH] bitmap texture to fill a [dstW]x[dstH] frame (centred, keeping its
     * shape) and flips V the way [GlUtil.FLIP_V] does for uploaded bitmaps. Column-major, for a uTexMatrix.
     */
    fun coverMatrix(srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val r = coverRect(srcW, srcH, dstW, dstH)
        val fx = r[2] - r[0]
        val fy = r[3] - r[1]
        return floatArrayOf(
            fx, 0f, 0f, 0f,
            0f, -fy, 0f, 0f,
            0f, 0f, 1f, 0f,
            r[0], r[3], 0f, 1f,
        )
    }

    /**
     * The part of the source shown when it fills the frame, as (u0, v0, u1, v1) in 0..1 with v down from the bitmap's
     * top: the full width or height, and the middle of the other.
     */
    fun coverRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
        val src = srcW.toFloat() / srcH
        val dst = dstW.toFloat() / dstH
        return if (src > dst) {
            val f = dst / src
            floatArrayOf((1f - f) / 2f, 0f, (1f + f) / 2f, 1f)
        } else {
            val f = src / dst
            floatArrayOf(0f, (1f - f) / 2f, 1f, (1f + f) / 2f)
        }
    }
}
