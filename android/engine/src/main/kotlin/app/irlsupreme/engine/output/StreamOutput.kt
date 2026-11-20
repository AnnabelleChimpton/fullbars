package app.irlsupreme.engine.output

import android.media.MediaCodec
import app.irlsupreme.engine.ConnectionPhase
import app.irlsupreme.engine.LinkInfo
import java.nio.ByteBuffer

/** Where encoded media goes: direct RTMP(S) to Twitch, or SRT bonded over several links to the BRB server. */
interface StreamOutput {
    /** [ConnectionPhase.RECONNECTING] only after the stream was live once; failed first tries stay CONNECTING. */
    val phase: ConnectionPhase
    /** Failed tries since the last success (or since the start, before the first one). */
    val reconnectAttempt: Int
    /** Bits/s actually leaving. */
    val sentBitrate: Long
    val lastFailure: String?
    /** Drops recovered by reconnecting (the recap's "kept you live" count). */
    val recoveredDrops: Int

    fun setVideoConfig(sps: ByteBuffer, pps: ByteBuffer)
    fun setAudioConfig(sampleRate: Int, stereo: Boolean)
    fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun congested(): Boolean
    /** Per-link state for the live screen (empty for single-link outputs). */
    fun links(): List<LinkInfo> = emptyList()
    fun stop()
}
