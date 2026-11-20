package app.irlsupreme.engine.audio

import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import app.irlsupreme.engine.AudioCounters
import app.irlsupreme.engine.AudioInput
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.locks.LockSupport

interface AudioSink {
    fun onAudioConfig(sampleRate: Int, stereo: Boolean)
    fun onAudioFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
}

/**
 * Mic -> AAC-LC. PTS come from AudioRecord's monotonic timestamps (same clock as the video's System.nanoTime), so
 * audio stays locked to video for hours instead of drifting by the mic's clock error.
 * Muting replaces samples with silence rather than stopping capture, so the stream's audio track never gaps.
 *
 * The audio track never stops either, whatever the mic does: the output only connects once it has the audio config,
 * so a mic that won't open must not keep the stream off the air. Without a working AudioRecord (it couldn't open, or
 * reads started failing) the pipeline feeds silence paced by the clock, still mixing in the camera's audio and alert
 * sounds, and keeps trying to (re)open the mic with backoff. Codec failures restart the encoder with backoff;
 * capture continues meanwhile. [audioInput] says what's really happening.
 */
class AudioPipeline(
    private val sampleRate: Int,
    private val stereo: Boolean,
    private val bitrate: Int,
    private val sink: AudioSink,
    /** Whether the phone offers a truly unprocessed mic source (AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED). */
    private val unprocessedOk: Boolean = false,
    /** Every chunk's level as viewers get it (before the user's mute), on the capture thread, ~47 times a second. */
    private val onLevel: (MicLevel) -> Unit = {},
    /** Gets every chunk of the final mix (after mute and alert sounds), exactly as it's handed to the encoder. */
    private val mix: PcmRing? = null,
) {
    @Volatile var muted = false
    /** Where the mute ramp is: 1 = heard, 0 = muted (capture thread). */
    private var muteGain = 1f

    /**
     * The phone's own auto level off: the raw mic, levelled only by [micGainDb] and the limiter ([micSources]). Changing
     * it reopens the mic between two chunks; the stream gets one short gap of silence at most.
     */
    @Volatile var rawMic = false

    /** Sounds mixed into the stream (alert chimes), one after another; never played on the phone. */
    private class Effect(val samples: ShortArray, val startAtNs: Long)
    private val effects = java.util.concurrent.ConcurrentLinkedQueue<Effect>()
    private var effect: ShortArray? = null
    private var effectPos = 0

    /**
     * Queues [samples] (16-bit PCM in the stream's channel layout) to be mixed into the stream, starting no earlier
     * than [startAtNs] on the System.nanoTime clock (0: as soon as it's its turn). Audio and video timestamps share
     * that clock, so a chime started at the moment an alert finishes its wipe lands on that frame for viewers.
     */
    fun playEffect(samples: ShortArray, startAtNs: Long = 0L) {
        if (effects.size < 8) effects.add(Effect(samples, startAtNs))
    }
    /**
     * An RTMP camera's audio (Pocket 3 / DJI Mic). When set, each mic chunk is replaced by the camera's samples for
     * the same moment whenever the camera has them; the mic stays the clock and the fallback.
     */
    @Volatile var camera: CameraAudio? = null
    /** Whether the last chunk came from the camera. */
    @Volatile var usingCamera = false
        private set
    private var camHits = 0
    private var camMisses = 0
    private val camScratch by lazy { ByteArray(chunkFrames * bytesPerFrame) }
    @Volatile private var running = false
    private var capture: Thread? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var codecFailed = false
    private var drain: Thread? = null
    @Volatile var codecRestarts = 0
        private set

    /** Times the mic lost audio because this thread fell behind its AudioRecord (an overrun: a click or a gap). */
    @Volatile var micOverruns = 0
        private set
    /** Chunks of the final mix that never reached the encoder (it was restarting, or stayed full for too long). */
    @Volatile var encoderDrops = 0
        private set
    /** Chunks processed, and those where the limiter took off more than [HEAVY_LIMIT_DB]: the level is set too hot. */
    @Volatile var chunks = 0
        private set
    @Volatile var heavyLimitChunks = 0
        private set

    /** Everything worth counting for the session log, the camera's playout included. */
    fun counters(): AudioCounters {
        val cam = camera
        return AudioCounters(
            micOverruns = micOverruns,
            encoderDrops = encoderDrops,
            chunks = chunks,
            heavyLimitChunks = heavyLimitChunks,
            cameraUnderruns = cam?.underruns ?: 0,
            cameraGapFills = cam?.gapFills ?: 0,
            cameraResyncs = cam?.resyncs ?: 0,
            cameraBufferMs = cam?.bufferMs ?: 0,
            cameraRatePpm = cam?.ratePpm ?: 0,
            cameraSyncMs = cam?.syncErrorMs ?: 0,
        )
    }

    @Volatile private var micState = AudioInput.OK
    @Volatile private var silenced = false
    /**
     * What viewers get from the phone: [AudioInput.OK] while the mic delivers (or the external camera's audio stands in
     * for it), else why not. The user's mute is separate ([muted]).
     */
    val audioInput: AudioInput get() = if (usingCamera) AudioInput.OK else micState

    @Volatile private var micError: String? = null
    @Volatile private var codecError: String? = null
    /** The current audio problem in plain words, or null; clears once it's fixed. */
    val lastError: String? get() = listOfNotNull(micError, codecError).joinToString(" · ").ifEmpty { null }

    private val channels = if (stereo) 2 else 1
    private val dsp = MicDsp(sampleRate, channels)

    /**
     * The level for the raw mic. Picked per chunk by the source actually open, so switching modes changes source and
     * level together, never a chunk of one at the other's level.
     */
    @Volatile var rawMicGainDb = 20f

    /** The user's mic level, -20..+30 dB; applies from the next chunk, gliding so it doesn't click. */
    var micGainDb: Float
        get() = dsp.gainDb
        set(v) { dsp.gainDb = v }
    private val bytesPerFrame = 2 * channels
    private val chunkFrames = CHUNK_FRAMES
    private val chunkNs = chunkFrames * 1_000_000_000L / sampleRate
    private var codecRetryAtNs = 0L
    private var codecBackoffMs = CODEC_RETRY_MIN_MS

    fun start() {
        running = true
        capture = Thread(::captureLoop, "engine-audio-capture").apply { start() }
    }

    fun stop() {
        running = false
        capture?.join(2_000)
        stopCodec()
    }

    /** Capture thread. False (with [codecError] set) if the encoder couldn't start; the caller retries later. */
    private fun startCodec(): Boolean {
        var c: MediaCodec? = null
        return try {
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            configureAac(c, sampleRate, stereo, bitrate)
            c.start()
            codecFailed = false
            codec = c
            drain = Thread({ drainLoop(c) }, "engine-aenc-drain").apply { start() }
            codecError = null
            codecBackoffMs = CODEC_RETRY_MIN_MS
            true
        } catch (t: Throwable) {
            Log.w(TAG, "audio encoder couldn't start; retrying in $codecBackoffMs ms", t)
            c?.let { runCatching { it.release() } }
            codecError = "Audio encoder down"
            codecRetryAtNs = System.nanoTime() + codecBackoffMs * 1_000_000
            codecBackoffMs = (codecBackoffMs * 2).coerceAtMost(CODEC_RETRY_MAX_MS)
            false
        }
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        drain?.join(1_000)
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    /** The open mic and whether it was opened raw, so a change of [rawMic] can reopen it. */
    private var mic: OpenMic? = null
    private var openedRaw = false

    /** Opens the mic ([micSources]) and watches whether the system silences it. Null if no source will record. */
    private fun openRecord(): AudioRecord? {
        val raw = rawMic
        val m = openMic(sampleRate, stereo, chunkFrames * bytesPerFrame, raw, unprocessedOk) ?: return null
        mic = m
        openedRaw = raw
        val r = m.record
        // Another app (a call, the system) can silence this client while it keeps "recording" zeros.
        val cb = recordingCallback(r)
        if (runCatching { r.registerAudioRecordingCallback(directExecutor, cb) }.isSuccess) recordCallback = cb
        silenced = runCatching { r.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
        return r
    }

    private fun recordingCallback(r: AudioRecord) = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val mine = configs.firstOrNull { it.clientAudioSessionId == r.audioSessionId }
                ?: runCatching { r.activeRecordingConfiguration }.getOrNull()
            val now = mine?.isClientSilenced == true
            if (now != silenced) Log.w(TAG, if (now) "mic silenced by the system" else "mic no longer silenced")
            silenced = now
        }
    }

    private var recordCallback: AudioManager.AudioRecordingCallback? = null

    private fun closeRecord(r: AudioRecord) {
        recordCallback?.let { cb -> runCatching { r.unregisterAudioRecordingCallback(cb) } }
        recordCallback = null
        val m = mic
        if (m != null && m.record === r) m.close() else { runCatching { r.stop() }; runCatching { r.release() } }
        mic = null
        silenced = false
    }

    /** Reads one full chunk into [pcm]. Returns false when the record has failed (and should be rebuilt). */
    private fun readChunk(r: AudioRecord, pcm: ByteArray): Boolean {
        var off = 0
        var empty = 0
        while (off < pcm.size && running) {
            val n = r.read(pcm, off, pcm.size - off)
            if (n < 0) {
                micError = "Mic stopped working (error $n)"
                return false
            }
            if (n == 0) {
                // A blocking read that keeps returning nothing is as dead as an error; don't spin a core on it.
                if (++empty > 50) { micError = "Mic stopped delivering"; return false }
                LockSupport.parkNanos(5_000_000)
            }
            off += n
        }
        return true
    }

    private fun captureLoop() {
        // Audio priority: this thread is the AudioRecord's only reader, and a stall longer than its buffer (a warm,
        // throttled phone with the compositor, codecs and links all busy) overruns it into an audible click or gap.
        // AUDIO, not URGENT_AUDIO, which is the system mixer's level.
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        // Before anything that can fail: the output connects only once it knows the audio format.
        sink.onAudioConfig(sampleRate, stereo)
        startCodec()
        muteGain = if (muted) 0f else 1f // a stream that starts muted mustn't leak its first few ms
        val pcm = ByteArray(chunkFrames * bytesPerFrame)
        val ts = AudioTimestamp()
        val pts = AudioPts(chunkNs)
        var record: AudioRecord? = null
        var everOpened = false
        var recordFrames = 0L
        val clock = ChunkClock(sampleRate, chunkNs)
        val overruns = OverrunDetector(chunkNs)
        // The limiter's lookahead: each chunk comes out of the level stage this much later than it went in.
        val dspLatencyNs = dsp.latencyFrames * 1_000_000_000L / sampleRate
        var openAtNs = 0L
        var openBackoffMs = OPEN_RETRY_MIN_MS
        var goodChunks = 0
        var micFadeIn = false
        var silenceNextNs = 0L
        try {
            while (running) {
                // The user switched the phone's auto level: reopen with the other source, at once.
                record?.let { cur ->
                    if (openedRaw != rawMic) {
                        Log.i(TAG, "mic: phone auto level ${if (rawMic) "off" else "on"}, reopening")
                        closeRecord(cur)
                        record = null
                        openAtNs = 0L
                        openBackoffMs = OPEN_RETRY_MIN_MS
                    }
                }
                val r = record ?: if (System.nanoTime() >= openAtNs) openRecord().also { opened ->
                    if (opened != null) {
                        if (everOpened || micState != AudioInput.OK) Log.i(TAG, "mic open again")
                        everOpened = true
                        record = opened
                        recordFrames = 0
                        clock.reset()
                        overruns.reset()
                        micFadeIn = true
                        silenceNextNs = 0
                        goodChunks = 0
                        pts.sourceChanged()
                    } else {
                        if (micState == AudioInput.OK) Log.e(TAG, "microphone unavailable; streaming silence and retrying")
                        micState = if (everOpened) AudioInput.FAILING else AudioInput.UNAVAILABLE
                        if (micError == null) micError = "Microphone unavailable"
                        openAtNs = System.nanoTime() + openBackoffMs * 1_000_000
                        openBackoffMs = (openBackoffMs * 2).coerceAtMost(OPEN_RETRY_MAX_MS)
                    }
                } else null

                val ptsNs: Long
                if (r != null) {
                    if (!readChunk(r, pcm)) {
                        if (!running) break
                        Log.w(TAG, "mic failed ($micError); rebuilding in $openBackoffMs ms, silence meanwhile")
                        closeRecord(r)
                        record = null
                        micState = AudioInput.FAILING
                        openAtNs = System.nanoTime() + openBackoffMs * 1_000_000
                        openBackoffMs = (openBackoffMs * 2).coerceAtMost(OPEN_RETRY_MAX_MS)
                        continue
                    }
                    if (!running) break
                    // A mic that just (re)opened starts mid-sound after silence or another source: fade it in.
                    if (micFadeIn) { crossfade(null, pcm, pcm, chunkFrames, channels, SWITCH_FADE_FRAMES); micFadeIn = false }
                    val chunkStartFrame = recordFrames
                    recordFrames += chunkFrames
                    // ~1 s of good reads: this record works, so a later failure starts the backoff from the bottom.
                    if (++goodChunks == GOOD_CHUNKS_TO_RESET) { openBackoffMs = OPEN_RETRY_MIN_MS; micError = null }
                    micState = if (silenced) AudioInput.SILENCED else AudioInput.OK
                    // Time of the chunk's first sample on the monotonic clock.
                    val raw = if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        clock.fromTimestamp(ts.nanoTime, ts.framePosition, chunkStartFrame).also {
                            if (overruns.onChunk(it)) {
                                micOverruns++
                                Log.w(TAG, "mic overrun: audio lost before this chunk (${overruns.lastLostMs} ms)")
                            }
                        }
                    } else {
                        overruns.reset() // an extrapolated time can't show a loss, and mustn't fake one later
                        clock.fallback(System.nanoTime(), chunkStartFrame)
                    }
                    ptsNs = pts.next(raw)
                } else {
                    // No mic: one chunk of silence per chunk of real time, on the same clock as the video.
                    if (silenceNextNs == 0L) { silenceNextNs = System.nanoTime(); pts.sourceChanged() }
                    val due = silenceNextNs + chunkNs
                    while (running) {
                        val wait = due - System.nanoTime()
                        if (wait <= 0) break
                        LockSupport.parkNanos(wait)
                    }
                    if (!running) break
                    pcm.fill(0)
                    ptsNs = pts.next(silenceNextNs)
                    silenceNextNs = due
                }

                // Hysteresis: switching microphones is audible (different room sound), so only switch on a clear
                // change. On the camera, a momentary miss is a short faded silence (the camera's playout fades it)
                // instead of the phone mic. The camera is pulled every chunk, used or not, so its playout keeps time.
                val cam = camera
                val wasCamera = usingCamera
                if (cam != null) {
                    val covered = cam.fill(ptsNs, chunkFrames, camScratch)
                    if (covered) { camHits++; camMisses = 0 } else { camMisses++; camHits = 0 }
                    if (!usingCamera && camHits >= CAM_SWITCH_ON) { usingCamera = true; Log.i(TAG, "audio: camera") }
                    else if (usingCamera && camMisses >= CAM_SWITCH_OFF) { usingCamera = false; Log.i(TAG, "audio: phone mic") }
                } else usingCamera = false
                // A switch crossfades over a few ms: cutting from one source to the other mid-wave is a click.
                when {
                    usingCamera && wasCamera -> camScratch.copyInto(pcm)
                    usingCamera -> crossfade(pcm, camScratch, pcm, chunkFrames, channels, SWITCH_FADE_FRAMES)
                    wasCamera -> crossfade(if (cam != null) camScratch else null, pcm, pcm, chunkFrames, channels, SWITCH_FADE_FRAMES)
                }
                // Level and limiter on whatever the voice is (phone mic or camera), metered before the mute so the
                // mic sheet still shows the mic working while viewers hear nothing. The user's gain is set for the
                // phone mic (up to +30 dB for the quiet raw source); a camera's audio already arrives at a normal
                // level, so it gets 0 dB (limiter and meter still run). The DSP's glide smooths the switch.
                val level = dsp.process(pcm, chunkFrames, when { usingCamera -> 0f; record != null && openedRaw -> rawMicGainDb; else -> null })
                chunks++
                if (level.reductionDb > HEAVY_LIMIT_DB) heavyLimitChunks++
                onLevel(level)
                // What comes out of the level stage started its lookahead earlier than what went in.
                val outPtsNs = ptsNs - dspLatencyNs
                // Alert sounds go into the stream, but not while the mic is off. Chimes that come up while muted are
                // dropped, not held: their alert cards have already played (plain Mute doesn't hold alerts; Privacy
                // holds them before any chime is queued), so holding them would play a string of chimes on unmute.
                // One still playing as the mute starts fades out with the voice rather than stopping dead.
                if (muted && muteGain == 0f) { effects.clear(); effect = null }
                // A scheduled one starts at its own sample inside the chunk that contains its start time.
                var pcmFrom = 0
                if (effect == null && !muted) effects.peek()?.let { next ->
                    val frame = AlertSounds.startFrame(outPtsNs, chunkFrames, sampleRate, next.startAtNs)
                    if (frame >= 0) { effects.poll(); effect = next.samples; effectPos = 0; pcmFrom = frame * bytesPerFrame }
                }
                effect?.let { e ->
                    effectPos = AlertSounds.mixInto(pcm, e, effectPos, pcmFrom)
                    if (effectPos >= e.size) effect = null
                }
                // Mute and unmute ramp over ~10 ms: a hard cut mid-word clicks.
                muteGain = applyRamp(pcm, chunkFrames, channels, muteGain, if (muted) 0f else 1f, MUTE_RAMP_FRAMES)

                mix?.write(pcm, chunkFrames)
                encode(pcm, outPtsNs)
            }
        } catch (t: Throwable) {
            // Nothing above should throw; if something does, end this thread quietly rather than the whole app.
            Log.e(TAG, "audio capture stopped", t)
            micState = AudioInput.FAILING
            micError = "Audio stopped"
        } finally {
            record?.let { closeRecord(it) }
        }
    }

    /** Chunks waiting for an encoder input buffer, oldest first, and spare arrays for them (capture thread only). */
    private val encQueue = ArrayDeque<Pair<ByteArray, Long>>()
    private val encSpare = ArrayDeque<ByteArray>()

    /**
     * Hands one chunk to the encoder; while it's down the chunk is dropped and a restart is tried with backoff.
     *
     * Never waits for the encoder. It used to wait up to 20 ms for an input buffer and then drop the chunk: a chunk is
     * 21 ms, so a busy encoder both cut a hole in the audio and held this thread off the mic long enough to overrun
     * it. Now a chunk with no buffer free waits in a short queue and goes in with the next one; only a queue that
     * stays full for [MAX_QUEUED_CHUNKS] chunks drops its oldest.
     */
    private fun encode(pcm: ByteArray, ptsNs: Long) {
        if (codecFailed) {
            Log.w(TAG, "audio encoder output failed, restarting")
            stopCodec()
            // Cleared here so a replacement that won't start waits out startCodec()'s backoff, not one per chunk.
            codecFailed = false
            codecRestarts++
            codecRetryAtNs = 0
        }
        val c = codec
        if (c == null) {
            encoderDrops += 1 + encQueue.size
            while (encQueue.isNotEmpty()) encSpare.addLast(encQueue.removeFirst().first)
            if (System.nanoTime() >= codecRetryAtNs && startCodec()) Log.i(TAG, "audio encoder back")
            return
        }
        val copy = encSpare.removeLastOrNull()?.takeIf { it.size == pcm.size } ?: ByteArray(pcm.size)
        pcm.copyInto(copy)
        encQueue.addLast(copy to ptsNs)
        while (encQueue.size > MAX_QUEUED_CHUNKS) {
            encSpare.addLast(encQueue.removeFirst().first)
            encoderDrops++
        }
        try {
            while (encQueue.isNotEmpty()) {
                val idx = c.dequeueInputBuffer(0)
                if (idx < 0) break
                val (chunk, pts) = encQueue.removeFirst()
                val inBuf = c.getInputBuffer(idx)
                if (inBuf == null) { encSpare.addLast(chunk); encoderDrops++; continue }
                inBuf.clear(); inBuf.put(chunk)
                c.queueInputBuffer(idx, 0, chunk.size, pts / 1000, 0)
                encSpare.addLast(chunk)
            }
        } catch (e: Exception) {
            // Encoder died: drop it, keep capturing; the next chunks retry it.
            Log.w(TAG, "audio encoder failed, restarting", e)
            codecError = "Audio encoder down"
            stopCodec()
            codecRestarts++
            codecRetryAtNs = System.nanoTime() + CODEC_RETRY_MIN_MS * 1_000_000
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (codec === c) {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && !config && info.size > 0) {
                        buf.position(info.offset).limit(info.offset + info.size)
                        sink.onAudioFrame(buf, info)
                    }
                    c.releaseOutputBuffer(idx, false)
                }
            }
        } catch (e: Exception) {
            if (codec === c) {
                Log.w(TAG, "audio drain error", e)
                codecFailed = true // the capture thread restarts it (an undrained codec would just stop taking input)
            }
        }
    }

    companion object {
        private const val TAG = "Engine"
        /** Consecutive chunks (~21 ms each) before switching to / away from the camera's audio. */
        private const val CAM_SWITCH_ON = 94 // ~2 s: the Pocket's stream settles for 2-3 s after it starts
        private const val CAM_SWITCH_OFF = 10
        private const val OPEN_RETRY_MIN_MS = 200L
        private const val OPEN_RETRY_MAX_MS = 2_000L
        private const val CODEC_RETRY_MIN_MS = 500L
        private const val CODEC_RETRY_MAX_MS = 5_000L
        private const val GOOD_CHUNKS_TO_RESET = 47
        /** ~5 ms crossfade between the camera's audio and the phone mic. */
        private const val SWITCH_FADE_FRAMES = 240
        /** ~10 ms mute/unmute ramp. */
        private const val MUTE_RAMP_FRAMES = 480
        /** ~250 ms of chunks may wait for the encoder before the oldest is dropped. */
        private const val MAX_QUEUED_CHUNKS = 12
        /** More gain reduction than this in a chunk counts as the limiter working hard (level set too hot). */
        private const val HEAVY_LIMIT_DB = 6f
        private val directExecutor = Executor { it.run() }
        /** PCM frames per AAC input chunk (one AAC frame). */
        const val CHUNK_FRAMES = 1024

        /** The live AAC setup; the BRB slate's silent audio is encoded through this too, so its config matches. */
        fun configureAac(codec: MediaCodec, sampleRate: Int, stereo: Boolean, bitrate: Int) {
            val channels = if (stereo) 2 else 1
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * 2 * channels * 2)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }
}

/**
 * When each chunk of one AudioRecord started, on the monotonic clock (pure, unit-tested). AudioRecord's timestamp is
 * the source of truth; when it fails for a chunk the time is extrapolated from the last good one at the nominal rate,
 * or, if none has worked on this record yet, anchored at the failing chunk's own read time. Anchoring the fallback at
 * the record's opening (chunk 0) instead would put a first failure N minutes into a record N minutes in the future,
 * and [AudioPts] would then pin every later chunk to one timestamp until real time caught up.
 */
internal class ChunkClock(private val sampleRate: Int, private val chunkNs: Long) {
    /** Monotonic time of the record's frame 0; 0 = not known yet (monotonic times are never 0). */
    private var baseNs = 0L

    /** A new record: frame counts start again from 0. */
    fun reset() { baseNs = 0L }

    /** From a good AudioRecord timestamp ([tsNanoTime] at frame [tsFramePosition]). */
    fun fromTimestamp(tsNanoTime: Long, tsFramePosition: Long, chunkStartFrame: Long): Long =
        (tsNanoTime + (chunkStartFrame - tsFramePosition) * 1_000_000_000L / sampleRate)
            .also { baseNs = it - framesNs(chunkStartFrame) }

    /** No timestamp for this chunk; [nowNs] is just after it was read. */
    fun fallback(nowNs: Long, chunkStartFrame: Long): Long {
        if (baseNs == 0L) baseNs = nowNs - chunkNs - framesNs(chunkStartFrame)
        return baseNs + framesNs(chunkStartFrame)
    }

    private fun framesNs(frames: Long) = frames * 1_000_000_000L / sampleRate
}

/**
 * Keeps audio timestamps strictly increasing across mic rebuilds and silence fill (pure, unit-tested). Each source
 * (an AudioRecord, or the silence clock) has its own timeline; a new one can start slightly before the last chunk of
 * the old one (the mic's buffer latency vs. the silence clock). On the first chunk after [sourceChanged], the new
 * timeline is shifted just enough to follow on, and that offset is kept for the rest of that source so its chunks stay
 * evenly spaced. Within a source, jitter can never take a timestamp backwards.
 */
internal class AudioPts(private val chunkNs: Long) {
    private var last = Long.MIN_VALUE
    private var offset = 0L
    private var rebase = true

    /** The next chunk comes from a new source (a reopened mic, or silence). */
    fun sourceChanged() { rebase = true }

    /** The PTS (ns) to use for a chunk whose own clock says [rawNs]. */
    fun next(rawNs: Long): Long {
        if (rebase) {
            rebase = false
            offset = if (last != Long.MIN_VALUE && rawNs < last + chunkNs) last + chunkNs - rawNs else 0L
        }
        var p = rawNs + offset
        if (last != Long.MIN_VALUE && p <= last) p = last + 1_000 // 1 µs: the codec's unit
        last = p
        return p
    }
}

/**
 * Spots audio the mic lost (pure, unit-tested). AudioRecord's timestamps count frames in the reader's terms: when the
 * capture thread falls behind and the record overruns, the frames it drops never get a number, so the next chunk's
 * time (from [ChunkClock.fromTimestamp]) lands later than one chunk after the last. Timestamps wobble by a fraction of
 * a millisecond; a real overrun loses at least one of the HAL's periods (several ms).
 */
internal class OverrunDetector(private val chunkNs: Long) {
    private var lastNs = 0L
    /** How much the last overrun lost (ms). */
    var lastLostMs = 0L
        private set

    /** A new record, or a chunk timed without a timestamp: the next one has nothing to compare with. */
    fun reset() { lastNs = 0L }

    /** [rawNs]: this chunk's start from the record's timestamp. True when audio went missing just before it. */
    fun onChunk(rawNs: Long): Boolean {
        val prev = lastNs
        lastNs = rawNs
        if (prev == 0L) return false
        val lost = rawNs - prev - chunkNs
        if (lost <= SLACK_NS) return false
        lastLostMs = lost / 1_000_000
        return true
    }

    private companion object {
        const val SLACK_NS = 5_000_000L
    }
}

/**
 * Crossfades over the first [fadeFrames] of a chunk from [from] (null: silence) to [to], into [out] (any of them may be
 * the same array); after the fade, [out] is [to]. 16-bit little-endian interleaved.
 */
internal fun crossfade(from: ByteArray?, to: ByteArray, out: ByteArray, frames: Int, channels: Int, fadeFrames: Int) {
    val n = frames * channels
    val fade = minOf(fadeFrames, frames)
    for (s in 0 until n) {
        val f = s / channels
        val b = pcm16(to, s)
        val v = if (f < fade) {
            val w = (f + 1).toFloat() / (fade + 1)
            val a = if (from == null) 0 else pcm16(from, s)
            (a + (b - a) * w).toInt()
        } else b
        out[2 * s] = v.toByte()
        out[2 * s + 1] = (v shr 8).toByte()
    }
}

/**
 * Scales a chunk (16-bit little-endian interleaved) by a gain moving from [from] towards [to] by 1/[rampFrames] per
 * frame, and returns where it got to. Untouched at 1, silence at 0.
 */
internal fun applyRamp(pcm: ByteArray, frames: Int, channels: Int, from: Float, to: Float, rampFrames: Int): Float {
    if (from == to) {
        if (to == 0f) pcm.fill(0, 0, frames * channels * 2)
        return to
    }
    val step = 1f / rampFrames
    var g = from
    for (f in 0 until frames) {
        g = if (g < to) minOf(to, g + step) else maxOf(to, g - step)
        for (ch in 0 until channels) {
            val s = f * channels + ch
            val v = (pcm16(pcm, s) * g).toInt()
            pcm[2 * s] = v.toByte()
            pcm[2 * s + 1] = (v shr 8).toByte()
        }
    }
    return g
}

private fun pcm16(b: ByteArray, sample: Int): Int =
    ((b[2 * sample].toInt() and 0xff) or (b[2 * sample + 1].toInt() shl 8)).toShort().toInt()
