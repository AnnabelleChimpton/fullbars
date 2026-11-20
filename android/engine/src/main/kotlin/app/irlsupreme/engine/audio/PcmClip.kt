package app.irlsupreme.engine.audio

/**
 * A few seconds of 16-bit PCM, interleaved, for playing back to the streamer: what viewers would have heard (the
 * stream's final mix) or what the mic check heard. [pcm] is owned by the receiver.
 */
class PcmClip(val pcm: ShortArray, val sampleRate: Int, val channels: Int) {
    val seconds: Float get() = if (sampleRate <= 0 || channels <= 0) 0f else pcm.size.toFloat() / channels / sampleRate
}
