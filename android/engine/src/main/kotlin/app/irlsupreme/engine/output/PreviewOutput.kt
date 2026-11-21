package app.irlsupreme.engine.output

import android.media.MediaCodec
import app.irlsupreme.engine.ConnectionPhase
import java.nio.ByteBuffer

/**
 * The output during a preview: it connects nowhere and sends nothing, so the camera, the mic and the cards can run
 * while the streamer checks them. [app.irlsupreme.engine.StreamEngine.goLive] replaces it with the real output.
 */
object PreviewOutput : StreamOutput {
    override val phase: ConnectionPhase get() = ConnectionPhase.IDLE
    override val reconnectAttempt: Int get() = 0
    override val sentBitrate: Long get() = 0
    override val lastFailure: String? get() = null
    override val recoveredDrops: Int get() = 0

    override fun setVideoConfig(sps: ByteBuffer, pps: ByteBuffer) = Unit
    override fun setAudioConfig(sampleRate: Int, stereo: Boolean) = Unit
    override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = Unit
    override fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = Unit
    override fun congested(): Boolean = false
    override fun stop() = Unit
}
