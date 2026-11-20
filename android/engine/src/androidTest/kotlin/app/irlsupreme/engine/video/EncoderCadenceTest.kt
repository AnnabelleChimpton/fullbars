package app.irlsupreme.engine.video

import android.media.MediaCodec
import android.opengl.GLES30
import android.os.SystemClock
import app.irlsupreme.engine.OutputTarget
import app.irlsupreme.engine.StreamConfig
import app.irlsupreme.engine.gl.EglCore
import app.irlsupreme.engine.gl.GlUtil
import app.irlsupreme.engine.gl.TextureProgram
import junit.framework.TestCase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.Random

/**
 * The live encoder on a real phone, fed the way the compositor feeds it (an input Surface, timestamps from an even
 * clock, real-time pace) with picture content that doesn't compress away: blocky noise that changes every frame.
 *
 * What it proves, per run: the stream starts on a sync frame, keyframes come at least every keyframeIntervalSec
 * (+0.5 s) even when frames arrive slower than the configured rate (a thermal step-down), timestamps only go
 * forward, a bitrate change is honoured within a few seconds, and the phone's slate encoder produces byte-identical
 * SPS/PPS (which the server's splice depends on).
 *
 * Run by hand on the release phone (see docs/BENCHMARKS.md); takes about a minute.
 */
class EncoderCadenceTest : TestCase() {
    private val config = StreamConfig(OutputTarget.Rtmp("rtmp://localhost/test"))

    private class Out(val ptsUs: Long, val size: Int, val key: Boolean)

    private class Collect : VideoSink {
        @Volatile var sps: ByteArray? = null
        @Volatile var pps: ByteArray? = null
        val frames: MutableList<Out> = Collections.synchronizedList(ArrayList())
        override fun onVideoConfig(sps: ByteBuffer, pps: ByteBuffer) {
            this.sps = sps.duplicate().let { b -> ByteArray(b.remaining()).also { b.get(it) } }
            this.pps = pps.duplicate().let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        }
        override fun onVideoFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            frames += Out(info.presentationTimeUs, info.size, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
        }
    }

    /** Renders noise into [encoder] at [fps] for [seconds]; [atSecond] runs once at each whole second of PTS. */
    private fun feed(encoder: VideoEncoder, fps: Int, seconds: Int, atSecond: (Int) -> Unit = {}) {
        val egl = EglCore(terminateDisplay = false)
        val surface = egl.createWindowSurface(encoder.inputSurface)
        try {
            egl.makeCurrent(surface)
            val program = TextureProgram(oes = false)
            val nw = config.width / 12
            val nh = config.height / 12
            val tex = GlUtil.genTexture(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            val noise = ByteBuffer.allocateDirect(nw * nh * 4).order(ByteOrder.nativeOrder())
            val bytes = ByteArray(nw * nh * 4)
            val rnd = Random(42)
            val frameNs = 1_000_000_000L / fps
            val start = SystemClock.elapsedRealtimeNanos()
            for (i in 0 until fps * seconds) {
                if (i % fps == 0) atSecond(i / fps)
                rnd.nextBytes(bytes)
                noise.position(0); noise.put(bytes); noise.position(0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, nw, nh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, noise)
                GLES30.glViewport(0, 0, config.width, config.height)
                program.draw(tex)
                egl.setPresentationTime(surface, i * frameNs)
                assertTrue("eglSwapBuffers failed", egl.swap(surface))
                val wait = start + (i + 1) * frameNs - SystemClock.elapsedRealtimeNanos()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
        } finally {
            egl.releaseSurface(surface)
            egl.release()
        }
        Thread.sleep(500) // let the last frames drain
    }

    private fun cadence(fps: Int) {
        val sink = Collect()
        val enc = VideoEncoder(config.width, config.height, config.fps, config.videoBitrate, config.keyframeIntervalSec, sink)
        try {
            feed(enc, fps, seconds = 9)
        } finally {
            enc.release()
        }
        assertNull("encoder failed: ${enc.failure}", enc.failure)
        val f = ArrayList(sink.frames)
        assertTrue("only ${f.size} frames out of ${fps * 9}", f.size >= fps * 9 * 9 / 10)
        assertTrue("first frame is not a sync frame", f[0].key)
        for (i in 1 until f.size) {
            assertTrue("PTS not increasing at frame $i: ${f[i - 1].ptsUs} -> ${f[i].ptsUs}", f[i].ptsUs > f[i - 1].ptsUs)
        }
        val limitUs = (config.keyframeIntervalSec * 1_000_000L) + 500_000
        var lastKey = f[0].ptsUs
        var maxGap = 0L
        for (o in f) {
            maxGap = maxOf(maxGap, o.ptsUs - lastKey)
            if (o.key) lastKey = o.ptsUs
        }
        assertTrue("IDR gap ${maxGap / 1000} ms at $fps fps input (limit ${limitUs / 1000} ms)", maxGap <= limitUs)
        // What the engine will log for this phone, for the benchmark notes.
        android.util.Log.i("EncoderCadenceTest", "$fps fps in: ${enc.info.describe()} maxIdrMs=${enc.meter.maxIdrIntervalMs} fpsOut=${"%.1f".format(enc.meter.framesPerSecond)}")
    }

    fun testCadenceAtTheConfiguredRate() = cadence(config.fps)

    /** A thermal step-down feeds 20 fps into an encoder configured for 30; encoders that count frames stretch the GOP. */
    fun testCadenceAt20fps() = cadence(20)

    fun testCadenceAt15fps() = cadence(15)

    fun testBitrateFollowsSetBitrate() {
        val sink = Collect()
        val enc = VideoEncoder(config.width, config.height, config.fps, config.videoBitrate, config.keyframeIntervalSec, sink)
        val target = 2_500_000
        val changeAt = 3
        try {
            feed(enc, config.fps, seconds = changeAt + 7) { s -> if (s == changeAt) enc.setBitrate(target) }
        } finally {
            enc.release()
        }
        assertNull("encoder failed: ${enc.failure}", enc.failure)
        val f = ArrayList(sink.frames)
        fun bps(fromS: Int, toS: Int): Long {
            val bytes = f.filter { it.ptsUs >= fromS * 1_000_000L && it.ptsUs < toS * 1_000_000L }.sumOf { it.size.toLong() }
            return bytes * 8 / (toS - fromS)
        }
        val before = bps(1, changeAt)
        val after = bps(changeAt + 3, changeAt + 6)
        android.util.Log.i("EncoderCadenceTest", "bitrate before=${before / 1000} kbps after=${after / 1000} kbps target=${target / 1000}")
        assertTrue("before the change: ${before / 1000} kbps vs ${config.videoBitrate / 1000}", before > config.videoBitrate * 3 / 4)
        assertTrue("3-6 s after setBitrate: ${after / 1000} kbps vs ${target / 1000}", after in target * 3L / 4..target * 5L / 4)
    }

    fun testSlateParameterSetsMatchTheLiveEncoder() {
        val sink = Collect()
        val enc = VideoEncoder(config.width, config.height, config.fps, config.videoBitrate, config.keyframeIntervalSec, sink)
        try {
            feed(enc, config.fps, seconds = 1)
        } finally {
            enc.release()
        }
        val sps = sink.sps
        val pps = sink.pps
        assertNotNull("live encoder gave no SPS", sps)
        val slate = SlateEncoder.encode(config)
        assertTrue("slate SPS differs from live", sps!!.contentEquals(slate.sps))
        assertTrue("slate PPS differs from live", pps!!.contentEquals(slate.pps))
        android.util.Log.i("EncoderCadenceTest", "slate GOP ${slate.video.size} frames, ${slate.bytes / 1024} KB; live ${enc.info.describe()}")
    }
}
