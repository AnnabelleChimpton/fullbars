package app.irlsupreme.engine.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.GLES30
import android.util.Log
import app.irlsupreme.engine.StreamConfig
import app.irlsupreme.engine.audio.AudioPipeline
import app.irlsupreme.engine.gl.EglCore
import app.irlsupreme.engine.gl.GlUtil
import app.irlsupreme.engine.gl.SlateProgram
import app.irlsupreme.engine.gl.TextureProgram
import java.nio.ByteBuffer

/** One encoded access unit, copied out of MediaCodec. */
class EncodedFrame(val data: ByteArray, val ptsUs: Long, val flags: Int) {
    fun buffer(): ByteBuffer = ByteBuffer.wrap(data)
    fun info(): MediaCodec.BufferInfo = MediaCodec.BufferInfo().apply { set(0, data.size, ptsUs, flags) }
    val key: Boolean get() = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
}

/** A slate as the server loops it: one GOP of a card (Signal lost, Weak signal) plus matching silence. Timestamps start at 0. */
class EncodedSlate(
    val sps: ByteArray,
    val pps: ByteArray,
    val video: List<EncodedFrame>,
    val audio: List<EncodedFrame>,
    val sampleRate: Int,
    val stereo: Boolean,
) {
    val bytes: Int get() = video.sumOf { it.data.size } + audio.sumOf { it.data.size }
}

/**
 * Encodes the server's slates on the phone (Signal lost for dropouts, Weak signal), through the same encoder setup as the live stream ([VideoEncoder.configureAvc],
 * [AudioPipeline.configureAac]). The server splices the slate into the live stream without transcoding, which only
 * works cleanly when its SPS/PPS and audio config are identical to the live ones; an x264 slate made on the server
 * can't be. Runs on the calling thread with its own EGL context and encoder instances; ~0.5 s.
 */
object SlateEncoder {
    fun encode(config: StreamConfig, card: Slates.Card = Slates.Card.BRB): EncodedSlate {
        val v = encodeVideo(config, card)
        val durUs = v.frames.size * 1_000_000L / config.fps
        val a = encodeSilence(config, durUs)
        return EncodedSlate(v.sps, v.pps, v.frames, a, config.audioSampleRate, config.audioStereo)
    }

    private class Video(val sps: ByteArray, val pps: ByteArray, val frames: List<EncodedFrame>)

    private fun encodeVideo(config: StreamConfig, card: Slates.Card): Video {
        // Exactly one GOP, like the live stream's: the keeper loops it, so every loop starts on this IDR.
        val count = config.fps * config.keyframeIntervalSec
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var egl: EglCore? = null
        try {
            VideoEncoder.configureAvc(codec, config.width, config.height, config.fps, config.videoBitrate, config.keyframeIntervalSec)
            val input = codec.createInputSurface()
            codec.start()
            val frames = ArrayList<EncodedFrame>(count)
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            var drainError: Throwable? = null
            val drain = Thread({
                val info = MediaCodec.BufferInfo()
                val deadline = System.nanoTime() + 10_000_000_000L
                try {
                    while (System.nanoTime() < deadline) {
                        val idx = codec.dequeueOutputBuffer(info, 10_000)
                        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            sps = codec.outputFormat.getByteBuffer("csd-0")?.toArray()
                            pps = codec.outputFormat.getByteBuffer("csd-1")?.toArray()
                        } else if (idx >= 0) {
                            val buf = codec.getOutputBuffer(idx)
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (buf != null && !isConfig && info.size > 0) {
                                buf.position(info.offset).limit(info.offset + info.size)
                                frames += EncodedFrame(buf.toArray(), info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            }
                            codec.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                } catch (t: Throwable) {
                    drainError = t
                }
            }, "engine-slate-drain").apply { start() }

            val e = EglCore(terminateDisplay = false).also { egl = it }
            val surface = e.createWindowSurface(input)
            e.makeCurrent(surface)
            val drawer = SlateDrawer(config.width, config.height)
            // The server's loop is silent, so its cards say so whatever the mic is doing.
            val tex = Slates.texture(config.width, config.height, card, config.channelLogin, SlateAudio.NONE)
            val frameNs = 1_000_000_000L / config.fps
            for (i in 0 until count) {
                GLES30.glViewport(0, 0, config.width, config.height)
                // One full pulse per loop and the cursor's 4 steps, so the loop point is invisible; fresh grain every frame.
                drawer.draw(tex, card, i.toLong(), count, Slates.cursorStep(i.toLong(), count), moves = true)
                e.setPresentationTime(surface, i * frameNs)
                check(e.swap(surface)) { "eglSwapBuffers failed ${e.lastError()}" }
                Thread.sleep(4) // a little faster than real time; never so fast the encoder sheds frames
            }
            codec.signalEndOfInputStream()
            drain.join(10_000)
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            drawer.release()
            e.releaseSurface(surface)
            input.release()
            drainError?.let { throw it }
            val s = sps; val p = pps
            check(s != null && p != null) { "encoder gave no SPS/PPS" }
            check(frames.isNotEmpty() && frames[0].key) { "slate must start with an IDR (got ${frames.size} frames)" }
            if (frames.size < count) Log.w(TAG, "slate: encoder returned ${frames.size}/$count frames")
            // Live keyframes carry SPS/PPS in-band (AUD, SPS, PPS, IDR on the wire); a fresh encoder hands them over
            // only as csd-0/csd-1. Put them in front of the slate's IDR too, so every loop is decodable on its own
            // and the server can check them byte for byte against the live stream.
            val idr = frames[0]
            frames[0] = EncodedFrame(s + p + idr.data, idr.ptsUs, idr.flags)
            return Video(s, p, frames)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            egl?.release()
        }
    }

    /** Silent AAC through the live audio setup, covering at least [durUs]. */
    private fun encodeSilence(config: StreamConfig, durUs: Long): List<EncodedFrame> {
        val channels = if (config.audioStereo) 2 else 1
        val chunk = AudioPipeline.CHUNK_FRAMES
        val chunkUs = chunk * 1_000_000L / config.audioSampleRate
        val chunks = ((durUs + chunkUs - 1) / chunkUs).toInt()
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            AudioPipeline.configureAac(codec, config.audioSampleRate, config.audioStereo, config.audioBitrate)
            codec.start()
            val silence = ByteArray(chunk * 2 * channels)
            val out = ArrayList<EncodedFrame>(chunks + 4)
            val info = MediaCodec.BufferInfo()
            var queued = 0
            var eosQueued = false
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                if (!eosQueued) {
                    val idx = codec.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        if (queued < chunks) {
                            codec.getInputBuffer(idx)!!.apply { clear(); put(silence) }
                            codec.queueInputBuffer(idx, 0, silence.size, queued * chunkUs, 0)
                            queued++
                        } else {
                            codec.queueInputBuffer(idx, 0, 0, queued * chunkUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)
                    val cfg = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && !cfg && info.size > 0) {
                        buf.position(info.offset).limit(info.offset + info.size)
                        out += EncodedFrame(buf.toArray(), info.presentationTimeUs, 0)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            check(out.isNotEmpty()) { "AAC encoder gave no output" }
            return out
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun ByteBuffer.toArray(): ByteArray = ByteArray(remaining()).also { get(it) }

    private const val TAG = "Engine"
}

/**
 * Draws one frame of a scene card with its motion, the same way for the server's encoded loop and the
 * phone's compositor, so the two look identical: the static card bitmap through [SlateProgram] (grain seeded by the
 * frame's place in the GOP, one pulse per GOP), then the ruler cursor as a small solid texture moved by a pos matrix.
 * The card is never redrawn per frame. Render thread (or the slate encoder's own GL thread) only.
 */
internal class SlateDrawer(width: Int, height: Int) {
    private val slate = SlateProgram()
    private val flat = TextureProgram(oes = false)
    private val cursorColors = CursorTextures()
    private val cursors = Array(Slates.CURSOR_STEPS) { step ->
        val r = Slates.cursorRect(width, height, step)
        GlUtil.rectMatrix(r[0], r[1], r[2], r[3], width, height)
    }

    /**
     * [tex] is [card]'s texture (from [Slates.texture]); [frameIndex] counts frames in a [gopFrames] loop; [cursorStep]
     * is 0..3, or -1 for none. [moves] adds grain and pulse (the phone leaves them off the Weak signal card).
     * [alpha] < 1 blends the frame over what's there, for the scene crossfade.
     */
    fun draw(tex: Int, card: Slates.Card, frameIndex: Long, gopFrames: Int, cursorStep: Int, moves: Boolean, alpha: Float = 1f) {
        if (moves) {
            slate.draw(
                tex, GlUtil.FLIP_V, Slates.grainSeed(frameIndex, gopFrames), Slates.GRAIN,
                Slates.pulseGain(frameIndex, gopFrames), alpha,
            )
        } else {
            flat.draw(tex, GlUtil.FLIP_V, alpha = alpha)
        }
        if (cursorStep in 0 until Slates.CURSOR_STEPS && card.hasCursor) {
            val color = cursorColors.forColor(Slates.cursorColor(card, Slates.style(card), CardLayer.FULL))
            flat.draw(color, posMatrix = cursors[cursorStep], alpha = alpha)
        }
    }

    fun release() = cursorColors.release()
}

/**
 * One 1x1 texture per colour the ruler cursor can take (Bone, Ink and every accent), made once on the GL thread, so the
 * per-frame pick ([Slates.cursorColor] -> texture) allocates nothing. Shared by [SlateDrawer] and the compositor's
 * layered card drawer, so a card's cursor is the same colour whichever draws it.
 */
internal class CursorTextures {
    private val colors = intArrayOf(StreamPalette.BONE, StreamPalette.INK) + Slates.ACCENTS.toIntArray()
    private val textures = IntArray(colors.size) { solid(colors[it]) }

    /** The texture for [color]; Bone for anything not in the set (it can't happen: accents are curated). */
    fun forColor(color: Int): Int {
        for (i in colors.indices) if (colors[i] == color) return textures[i]
        return textures[0]
    }

    fun release() = GLES30.glDeleteTextures(textures.size, textures, 0)

    private fun solid(color: Int): Int {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        b.eraseColor(color)
        return GlUtil.textureFromBitmap(b).also { b.recycle() }
    }
}
