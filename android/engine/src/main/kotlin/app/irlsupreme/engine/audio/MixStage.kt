package app.irlsupreme.engine.audio

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Everything the capture loop does to one chunk between reading it and encoding it: which source viewers hear (the
 * phone mic or the external camera's audio, with hysteresis and crossfades), the mic's fade-in after a reopen, the
 * level stage, alert sounds, and the mute ramp. Pure and single-threaded (the capture thread), apart from
 * [playEffect], which any thread may call; no Android, no I/O, so the whole mix can be tested with synthetic signals.
 *
 * The order matters, and is what keeps every source switch and every chime clean:
 * 1. Each source is levelled on its own, in float: the phone mic through [MicDsp.micFront] (low cut, its own gliding
 *    gain, the leveler) on every chunk whether it's heard or not, so its filter and glide never jump; the camera's
 *    audio at 0 dB, because it already arrives at a normal level. Before, one shared gain followed whichever source
 *    was on, so for the ~20 ms glide after the Pocket's audio took over it got the raw mic's +20 dB, a burst straight
 *    into the limiter, and the camera's fading tail got boosted on the way back.
 * 2. The crossfade (on a switch) mixes the two already-levelled signals.
 * 3. Alert sounds and card music are added, at their own level, and the limiter runs once over the sum. Before,
 *    chimes were added after the limiter with a hard clip, so a chime over a loud voice crackled.
 * 4. The mute ramp. While card music plays, muting turns down the voice (and alert sounds) before the limiter
 *    instead, so the music carries on: Privacy keeps the mic off and its music on.
 *
 * No allocation per chunk.
 */
internal class MixStage(
    private val sampleRate: Int,
    private val channels: Int,
    private val dsp: MicDsp,
    /** How long a switch between the camera and the mic crossfades (0: a hard cut, which clicks; for tests). */
    private val switchFadeFrames: Int = SWITCH_FADE_FRAMES,
) {
    /** Whether the last chunk came from the camera. Read from any thread. */
    @Volatile var usingCamera = false
        private set
    private var camHits = 0
    private var camMisses = 0

    /** Where the mute ramp is: 1 = heard, 0 = muted. */
    private var muteGain = 1f

    /** The next mic chunk follows a (re)open: fade it in. */
    private var micFadeIn = false

    /**
     * Sounds mixed into the stream (alert chimes and the streamer's own alert sounds); never played on the phone.
     * Chimes play one after another. A [replaces] sound (the streamer's own, which can be long) doesn't wait: it
     * takes over from whatever is playing, which fades out over [REPLACE_FADE_FRAMES], so sounds never stack.
     */
    private class Effect(val samples: ShortArray, val startAtNs: Long, val gain: Float, val replaces: Boolean)
    private val effects = ConcurrentLinkedQueue<Effect>()
    private var effect: Effect? = null
    private var effectPos = 0
    /** A sound being replaced: plays on for [outHold] frames, then fades over [outFadeLeft]. */
    private var outgoing: Effect? = null
    private var outgoingPos = 0
    private var outHold = 0
    private var outFadeLeft = 0

    /** Live captions' feed: gets the voice each chunk, before alert sounds, music and the mute. Read once per chunk. */
    @Volatile var voiceTap: app.irlsupreme.engine.captions.VoiceTap? = null

    /** Card music, when the stream has a player; null in tests that don't use it. Read once per chunk. */
    @Volatile var music: MusicSource? = null
    /** The voice's (and alert sounds') own mute, before the limiter: used only while music plays. 1 = heard. */
    private var preMute = 1f
    /** The voice's level while music that asks for the mic off plays. 1 = heard. */
    private var micDuck = 1f

    /** The level stage's lookahead: each chunk comes out of it this much later than it went in. */
    private val dspLatencyNs = dsp.latencyFrames * 1_000_000_000L / sampleRate

    // Settings the capture thread sets before each chunk.
    /** The phone mic's low cut, Hz (0 = off); see [lowCutHz]. */
    var lowCutHz = 0
    /** Whether the voice leveler rides the phone mic's level. */
    var leveler = false
    /** Fold the phone mic to mono ((L+R)/2 on both channels); for a stereo mic whose channels don't match. */
    var monoVoice = false
    /**
     * How much later than its capture time the audio is stamped (a lip-sync delay, see [AvDelayLine]); chimes are
     * scheduled on that same shifted clock so they still land on their alert's frame.
     */
    var clockOffsetNs = 0L

    /**
     * Samples of the final mix at full scale (|x| >= 32767): should stay 0, the limiter covers chimes too. Counters
     * are written by the capture thread only and read from others (stats), hence volatile.
     */
    @Volatile var postMixClips = 0L
        private set
    /** Chunks whose voice arrived clipped (before any gain), and the clipped-looking samples in them. */
    @Volatile var inputClipChunks = 0L
        private set
    @Volatile var inputClips = 0L
        private set

    private var micF = FloatArray(0)
    private var camF = FloatArray(0)
    private var mixF = FloatArray(0)
    private var fxF = FloatArray(0)
    /** The voice before the mute and the duck while either acts, for the meter. */
    private var meterF = FloatArray(0)

    /** See [AudioPipeline.playEffect]. Any thread. */
    fun playEffect(samples: ShortArray, startAtNs: Long, gain: Float = 1f, replaces: Boolean = false) {
        if (effects.size < 8) effects.add(Effect(samples, startAtNs, gain, replaces))
    }

    /** A stream that starts muted mustn't leak its first few ms. */
    fun start(muted: Boolean) { muteGain = if (muted) 0f else 1f }

    /** The mic just (re)opened: its first chunk starts mid-sound after silence or another source. */
    fun micOpened() {
        micFadeIn = true
        dsp.micReopened()
    }

    /** The next mic chunk follows a gap in the timeline (filled with silence): fade it in, without forgetting the filter. */
    fun micFadeInNext() { micFadeIn = true }

    /**
     * Mixes one chunk in place. [pcm] holds the phone mic's chunk (silence while there's no mic: [micPresent] false),
     * [ptsNs] is its time on the System.nanoTime clock. [cam] is the camera's chunk for the same moment and
     * [camCovered] whether the camera really had it; null while no camera is attached. [micGainDb] is the level for
     * the mic open now (null: the user's [MicDsp.gainDb]). With [micFadeOut], the mic is about to close (a switch of
     * source): its chunk fades to silence so the cut doesn't click. Returns the level as viewers get it, before the
     * mute and before music that turns the mic off.
     *
     * After this, [pcm] is exactly what goes to the encoder, stamped [outPtsNs].
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
        micFadeOut: Boolean = false,
    ): MicLevel {
        val n = frames * channels
        if (micF.size < n) { micF = FloatArray(n); camF = FloatArray(n); mixF = FloatArray(n); fxF = FloatArray(n); meterF = FloatArray(n) }
        // A mic that just (re)opened starts mid-sound after silence or another source: fade it in.
        if (micPresent && micFadeIn) { crossfade(null, pcm, pcm, frames, channels, SWITCH_FADE_FRAMES); micFadeIn = false }
        if (micPresent && micFadeOut) applyRamp(pcm, frames, channels, 1f, 0f, frames)

        // Hysteresis: switching microphones is audible (different room sound), so only switch on a clear change. On
        // the camera, a momentary miss is a short faded silence (the camera's playout fades it) instead of the phone
        // mic. The camera is pulled every chunk, used or not, so its playout keeps time.
        val wasCamera = usingCamera
        if (cam != null) {
            if (camCovered) { camHits++; camMisses = 0 } else { camMisses++; camHits = 0 }
            if (!usingCamera && camHits >= CAM_SWITCH_ON) usingCamera = true
            else if (usingCamera && camMisses >= CAM_SWITCH_OFF) usingCamera = false
        } else usingCamera = false

        // The input meter reads the source viewers hear, before any gain.
        dsp.meterInput(if (usingCamera && cam != null) cam else pcm, frames)
        // The mic is levelled every chunk, heard or not; the camera's audio goes in at 0 dB.
        dsp.micFront(pcm, frames, micF, micGainDb, lowCutHz, leveler, monoVoice)
        if (cam != null) toFloat(cam, camF, n)
        // A switch crossfades over a few ms: cutting from one source to the other mid-wave is a click.
        val voice = when {
            usingCamera && wasCamera -> camF
            usingCamera -> { crossfadeF(micF, camF, mixF, frames, channels, switchFadeFrames); mixF }
            wasCamera -> { crossfadeF(if (cam != null) camF else null, micF, mixF, frames, channels, switchFadeFrames); mixF }
            else -> micF
        }

        // Alert sounds go into the stream, but not while the mic is off. Chimes that come up while muted are dropped,
        // not held: their alert cards have already played (plain Mute doesn't hold alerts; Privacy holds them before
        // any chime is queued), so holding them would play a string of chimes on unmute. One still playing as the
        // mute starts fades out with the voice rather than stopping dead.
        // With music on, the mute happens before the limiter ([preMute]); the same rule applies once it's done.
        if (muted && (muteGain == 0f || preMute == 0f)) { effects.clear(); effect = null; outgoing = null }
        // A scheduled one starts at its own sample inside the chunk that contains its start time. It goes in ahead of
        // the limiter's lookahead with the voice, so it's scheduled on the chunk's own (input) time: both come out
        // the lookahead later together.
        var fxFrom = 0
        if (!muted) effects.peek()?.let { next ->
            val playing = effect
            if (playing == null || next.replaces || playing.replaces) {
                val frame = AlertSounds.startFrame(ptsNs + clockOffsetNs, frames, sampleRate, next.startAtNs)
                if (frame >= 0) {
                    effects.poll()
                    if (playing != null) { outgoing = playing; outgoingPos = effectPos; outHold = frame; outFadeLeft = REPLACE_FADE_FRAMES }
                    effect = next; effectPos = 0; fxFrom = frame * channels
                }
            }
        }
        var fx: FloatArray? = null
        effect?.let { e ->
            fxF.fill(0f, 0, n)
            effectPos = if (e.gain == 1f) AlertSounds.addInto(fxF, n, e.samples, effectPos, fxFrom)
            else addScaled(fxF, n, e.samples, effectPos, fxFrom, e.gain)
            if (effectPos >= e.samples.size) effect = null
            fx = fxF
        }
        if (outgoing != null) {
            if (fx == null) fxF.fill(0f, 0, n)
            addOutgoing(fxF, frames)
            fx = fxF
        }

        // Card music: under the voice, inside the limiter. While it plays, a mute turns down the voice and the alert
        // sounds here, before the limiter, and the music carries on; "mic off" music turns the voice down too.
        val m = music
        val musicOn = m != null && m.engaged
        val preMuteWas = preMute
        val preTarget = if (muted && (musicOn || preMute < 1f)) 0f else 1f
        val duckTarget = if (musicOn && m?.micOff == true) 0f else 1f
        val muting = preMute != 1f || preTarget != 1f
        val ducking = micDuck != 1f || duckTarget != 1f
        // The meter reads the voice as it was before either, like the plain mute (after the limiter) always has.
        val meter = if (muting || ducking) { System.arraycopy(voice, 0, meterF, 0, n); meterF } else null
        // Captions hear the voice alone (never the alert sounds or the music), and only what viewers can hear: a mute,
        // or music that turns the mic off, sends them silence.
        voiceTap?.onVoice(voice, frames, channels, audible = !muted && duckTarget == 1f)
        if (muting) preMute = rampF(voice, fx, frames, preMute, preTarget, MUTE_RAMP_FRAMES)
        if (ducking) micDuck = rampF(voice, null, frames, micDuck, duckTarget, DUCK_FRAMES)
        if (musicOn) {
            if (fx == null) { fxF.fill(0f, 0, n); fx = fxF }
            m?.addInto(fxF, frames, channels)
        }

        // One limiter over the sum; it meters the voice alone.
        val level = dsp.limit(voice, fx, frames, pcm, meter)
        if (level.inClipped > 0) { inputClipChunks++; inputClips += level.inClipped }
        // Mute and unmute ramp over ~10 ms: a hard cut mid-word clicks. With music on, the output stays up once the
        // voice is silent before the limiter (held where it is until then, so the voice never leaks).
        val outTarget = when {
            !muted -> 1f
            musicOn && preMuteWas == 0f -> 1f
            musicOn -> muteGain
            else -> 0f
        }
        muteGain = applyRamp(pcm, frames, channels, muteGain, outTarget, MUTE_RAMP_FRAMES)
        postMixClips += countFullScale(pcm, n)
        return level
    }

    /** The PTS the encoder gets for a chunk mixed at [ptsNs]. */
    fun outPtsNs(ptsNs: Long): Long = ptsNs - dspLatencyNs

    /** [AlertSounds.addInto] at a gain. */
    private fun addScaled(dst: FloatArray, dstSamples: Int, e: ShortArray, from: Int, dstFrom: Int, gain: Float): Int {
        var i = from
        var d = dstFrom.coerceAtLeast(0)
        val k = gain / 32768f
        while (d < dstSamples && i < e.size) { dst[d] += e[i] * k; d++; i++ }
        return i
    }

    /** The replaced sound: on until the new one starts, then fading out. */
    private fun addOutgoing(dst: FloatArray, frames: Int) {
        val o = outgoing ?: return
        var pos = outgoingPos
        var f = 0
        val k = o.gain / 32768f
        while (f < frames && pos + channels <= o.samples.size) {
            val g = if (outHold > 0) { outHold--; 1f } else if (outFadeLeft > 0) { outFadeLeft--; outFadeLeft.toFloat() / REPLACE_FADE_FRAMES } else break
            for (ch in 0 until channels) dst[f * channels + ch] += o.samples[pos + ch] * k * g
            pos += channels
            f++
        }
        outgoingPos = pos
        if (pos + channels > o.samples.size || (outHold == 0 && outFadeLeft == 0)) outgoing = null
    }

    /**
     * Scales [a] (and [b], if any) by a gain moving from [from] towards [to] by 1/[rampFrames] per frame, in float;
     * returns where it got to. Silence at 0.
     */
    private fun rampF(a: FloatArray, b: FloatArray?, frames: Int, from: Float, to: Float, rampFrames: Int): Float {
        val n = frames * channels
        if (from == to) {
            if (to == 0f) { a.fill(0f, 0, n); b?.fill(0f, 0, n) }
            return to
        }
        val step = 1f / rampFrames
        var g = from
        for (f in 0 until frames) {
            g = if (g < to) minOf(to, g + step) else maxOf(to, g - step)
            for (ch in 0 until channels) {
                val s = f * channels + ch
                a[s] *= g
                if (b != null) b[s] *= g
            }
        }
        return g
    }

    companion object {
        /** Consecutive chunks (~21 ms each) before switching to / away from the camera's audio. */
        const val CAM_SWITCH_ON = 94 // ~2 s: the Pocket's stream settles for 2-3 s after it starts
        const val CAM_SWITCH_OFF = 10
        /** ~5 ms crossfade between the camera's audio and the phone mic. */
        const val SWITCH_FADE_FRAMES = 240
        /** ~10 ms mute/unmute ramp. */
        const val MUTE_RAMP_FRAMES = 480
        /** ~20 ms: how fast a replaced alert sound fades out. */
        const val REPLACE_FADE_FRAMES = 960
        /** ~50 ms: the mic going off and on for music that asks for it. */
        const val DUCK_FRAMES = 2400
    }
}

/** 16-bit little-endian PCM to float (1.0 = full scale), [samples] samples. */
internal fun toFloat(pcm: ByteArray, out: FloatArray, samples: Int) {
    for (s in 0 until samples) out[s] = pcm16At(pcm, 2 * s) / 32768f
}

/** Samples of [pcm] at full scale either way. */
internal fun countFullScale(pcm: ByteArray, samples: Int): Int {
    var c = 0
    for (s in 0 until samples) {
        val v = pcm16At(pcm, 2 * s)
        if (v >= 32767 || v <= -32767) c++
    }
    return c
}

/** [crossfade] in float: from [from] (null: silence) to [to] over the first [fadeFrames] frames, into [out]. */
internal fun crossfadeF(from: FloatArray?, to: FloatArray, out: FloatArray, frames: Int, channels: Int, fadeFrames: Int) {
    val fade = minOf(fadeFrames, frames)
    for (f in 0 until frames) {
        val w = if (f < fade) (f + 1).toFloat() / (fade + 1) else 1f
        for (ch in 0 until channels) {
            val s = f * channels + ch
            val a = if (from != null) from[s] else 0f
            out[s] = if (w >= 1f) to[s] else a + (to[s] - a) * w
        }
    }
}
