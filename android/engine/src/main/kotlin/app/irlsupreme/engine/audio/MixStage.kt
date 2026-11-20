package app.irlsupreme.engine.audio

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Everything the capture loop does to one chunk between reading it and encoding it: which source viewers hear (the
 * phone mic or the external camera's audio, with hysteresis and crossfades), the mic's fade-in after a reopen, the
 * level stage, alert sounds, and the mute ramp. Pure and single-threaded (the capture thread), apart from
 * [playEffect], which any thread may call; no Android, no I/O, so the whole mix can be tested with synthetic signals.
 */
internal class MixStage(private val sampleRate: Int, private val channels: Int, private val dsp: MicDsp) {
    private val bytesPerFrame = 2 * channels

    /** Whether the last chunk came from the camera. Read from any thread. */
    @Volatile var usingCamera = false
        private set
    private var camHits = 0
    private var camMisses = 0

    /** Where the mute ramp is: 1 = heard, 0 = muted. */
    private var muteGain = 1f

    /** The next mic chunk follows a (re)open: fade it in. */
    private var micFadeIn = false

    /** Sounds mixed into the stream (alert chimes), one after another; never played on the phone. */
    private class Effect(val samples: ShortArray, val startAtNs: Long)
    private val effects = ConcurrentLinkedQueue<Effect>()
    private var effect: ShortArray? = null
    private var effectPos = 0

    /** The level stage's lookahead: each chunk comes out of it this much later than it went in. */
    private val dspLatencyNs = dsp.latencyFrames * 1_000_000_000L / sampleRate

    /** See [AudioPipeline.playEffect]. Any thread. */
    fun playEffect(samples: ShortArray, startAtNs: Long) {
        if (effects.size < 8) effects.add(Effect(samples, startAtNs))
    }

    /** A stream that starts muted mustn't leak its first few ms. */
    fun start(muted: Boolean) { muteGain = if (muted) 0f else 1f }

    /** The mic just (re)opened: its first chunk starts mid-sound after silence or another source. */
    fun micOpened() { micFadeIn = true }

    /**
     * Mixes one chunk in place. [pcm] holds the phone mic's chunk (silence while there's no mic), [ptsNs] is its time
     * on the System.nanoTime clock. [cam] is the camera's chunk for the same moment and [camCovered] whether the
     * camera really had it; null while no camera is attached. [micGainDb] is the level for the source open now
     * (null: the user's [MicDsp.gainDb]). Returns the level as viewers get it, before the mute.
     *
     * After this, [pcm] is exactly what goes to the encoder, stamped [ptsNs] minus the lookahead ([outPtsNs]).
     */
    fun process(
        pcm: ByteArray,
        frames: Int,
        ptsNs: Long,
        cam: ByteArray?,
        camCovered: Boolean,
        micGainDb: Float?,
        muted: Boolean,
        micPresent: Boolean,
    ): MicLevel {
        // A mic that just (re)opened starts mid-sound after silence or another source: fade it in.
        if (micPresent && micFadeIn) { crossfade(null, pcm, pcm, frames, channels, SWITCH_FADE_FRAMES); micFadeIn = false }

        // Hysteresis: switching microphones is audible (different room sound), so only switch on a clear change. On
        // the camera, a momentary miss is a short faded silence (the camera's playout fades it) instead of the phone
        // mic. The camera is pulled every chunk, used or not, so its playout keeps time.
        val wasCamera = usingCamera
        if (cam != null) {
            if (camCovered) { camHits++; camMisses = 0 } else { camMisses++; camHits = 0 }
            if (!usingCamera && camHits >= CAM_SWITCH_ON) usingCamera = true
            else if (usingCamera && camMisses >= CAM_SWITCH_OFF) usingCamera = false
        } else usingCamera = false
        // A switch crossfades over a few ms: cutting from one source to the other mid-wave is a click.
        when {
            usingCamera && wasCamera -> cam!!.copyInto(pcm)
            usingCamera -> crossfade(pcm, cam!!, pcm, frames, channels, SWITCH_FADE_FRAMES)
            wasCamera -> crossfade(cam, pcm, pcm, frames, channels, SWITCH_FADE_FRAMES)
        }
        // Level and limiter on whatever the voice is (phone mic or camera), metered before the mute so the mic sheet
        // still shows the mic working while viewers hear nothing. The user's gain is set for the phone mic (up to
        // +30 dB for the quiet raw source); a camera's audio already arrives at a normal level, so it gets 0 dB
        // (limiter and meter still run). The DSP's glide smooths the switch.
        val level = dsp.process(pcm, frames, if (usingCamera) 0f else micGainDb)
        // What comes out of the level stage started its lookahead earlier than what went in.
        val outPtsNs = ptsNs - dspLatencyNs
        // Alert sounds go into the stream, but not while the mic is off. Chimes that come up while muted are dropped,
        // not held: their alert cards have already played (plain Mute doesn't hold alerts; Privacy holds them before
        // any chime is queued), so holding them would play a string of chimes on unmute. One still playing as the
        // mute starts fades out with the voice rather than stopping dead.
        if (muted && muteGain == 0f) { effects.clear(); effect = null }
        // A scheduled one starts at its own sample inside the chunk that contains its start time.
        var pcmFrom = 0
        if (effect == null && !muted) effects.peek()?.let { next ->
            val frame = AlertSounds.startFrame(outPtsNs, frames, sampleRate, next.startAtNs)
            if (frame >= 0) { effects.poll(); effect = next.samples; effectPos = 0; pcmFrom = frame * bytesPerFrame }
        }
        effect?.let { e ->
            effectPos = AlertSounds.mixInto(pcm, e, effectPos, pcmFrom)
            if (effectPos >= e.size) effect = null
        }
        // Mute and unmute ramp over ~10 ms: a hard cut mid-word clicks.
        muteGain = applyRamp(pcm, frames, channels, muteGain, if (muted) 0f else 1f, MUTE_RAMP_FRAMES)
        return level
    }

    /** The PTS the encoder gets for a chunk mixed at [ptsNs]. */
    fun outPtsNs(ptsNs: Long): Long = ptsNs - dspLatencyNs

    companion object {
        /** Consecutive chunks (~21 ms each) before switching to / away from the camera's audio. */
        const val CAM_SWITCH_ON = 94 // ~2 s: the Pocket's stream settles for 2-3 s after it starts
        const val CAM_SWITCH_OFF = 10
        /** ~5 ms crossfade between the camera's audio and the phone mic. */
        const val SWITCH_FADE_FRAMES = 240
        /** ~10 ms mute/unmute ramp. */
        const val MUTE_RAMP_FRAMES = 480
    }
}
