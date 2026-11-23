package app.irlsupreme.engine.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
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
    /**
     * How late the picture is against the sound, ns (the camera's capture-to-draw latency for the source on screen);
     * the audio is delayed by this much plus [userAvOffsetNs], 0..400 ms in all, changing by at most 0.5 ms per chunk
     * ([AvDelayLine]). Asked once per chunk on the capture thread, so it must be cheap (a volatile read). Default: no
     * delay, as before.
     */
    private val videoDelayNs: DelaySource = DelaySource { 0L },
    /** The user's own A/V offset, ns (+: audio later). See [videoDelayNs]. */
    private val userAvOffsetNs: DelaySource = DelaySource { 0L },
    /**
     * For watching the phone's audio inputs come and go (a USB mic plugged in or out) and for [inputChoice]. Without
     * it the pipeline still notices its own recording being rerouted, but can't pick a device.
     */
    private val context: Context? = null,
    /**
     * The mic in use changed ([activeInput]); with a message for the streamer when it changed kind ("External mic
     * disconnected", "Using <name>"), else null. On the capture thread: hand it off, don't block.
     */
    private val onInputChange: (ActiveInput, String?) -> Unit = { _, _ -> },
) {
    @Volatile var muted = false

    /**
     * The phone's own auto level off: the raw mic, levelled only by [micGainDb] and the limiter ([micSources]). Changing
     * it reopens the mic between two chunks; the stream gets one short gap of silence at most.
     */
    @Volatile var rawMic = false

    /**
     * Queues [samples] (16-bit PCM in the stream's channel layout) to be mixed into the stream, starting no earlier
     * than [startAtNs] on the System.nanoTime clock (0: as soon as it's its turn). Audio and video timestamps share
     * that clock, so a chime started at the moment an alert finishes its wipe lands on that frame for viewers.
     */
    fun playEffect(samples: ShortArray, startAtNs: Long = 0L) = stage.playEffect(samples, startAtNs)

    /**
     * [playEffect] at [gain] (linear); with [replaces], it takes over from any sound still playing (which fades out
     * quickly) instead of waiting for it: the streamer's own alert sounds, which can be long.
     */
    fun playEffect(samples: ShortArray, startAtNs: Long, gain: Float, replaces: Boolean) =
        stage.playEffect(samples, startAtNs, gain, replaces)

    /**
     * A viewer's TTS message ([samples] in the stream's layout) mixed into the stream at [gain] from the next chunk,
     * in a slot of its own next to the alert sounds (neither cuts the other off). Replaces one still waiting to start.
     */
    fun playSpeech(samples: ShortArray, gain: Float = 1f) = stage.playSpeech(samples, gain)

    /** Fades the TTS message out (~20 ms) and drops one waiting to start. */
    fun stopSpeech() = stage.stopSpeech()

    /** Whether a TTS message is waiting to start or playing. */
    val speechPlaying: Boolean get() = stage.speechPlaying

    /** Card music, mixed under the voice inside the limiter; null for none. */
    internal var music: MusicSource?
        get() = stage.music
        set(v) { stage.music = v }

    /** Live captions' feed (see [app.irlsupreme.engine.captions.VoiceTap]); null while captions are off. */
    var voiceTap: app.irlsupreme.engine.captions.VoiceTap?
        get() = stage.voiceTap
        set(v) { stage.voiceTap = v }

    /**
     * An RTMP camera's audio (Pocket 3 / DJI Mic). When set, each mic chunk is replaced by the camera's samples for
     * the same moment whenever the camera has them; the mic stays the clock and the fallback.
     */
    @Volatile var camera: CameraAudio? = null
    /** Whether the last chunk came from the camera. */
    val usingCamera: Boolean get() = stage.usingCamera
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
    /** Chunks of silence encoded to fill a gap in the mic's timeline (an overrun, or a mic that came back late). */
    @Volatile var gapFillChunks = 0
        private set
    /** Samples of the final mix at full scale: should stay 0 (the limiter covers alert sounds too). */
    val postMixClips: Long get() = stage.postMixClips
    /** Chunks whose voice arrived clipped (in the phone or camera, before any gain), and the clipped samples in them. */
    val inputClipChunks: Long get() = stage.inputClipChunks
    val inputClips: Long get() = stage.inputClips
    /** Chunks timed without an AudioRecord timestamp (extrapolated), across every record of this stream. */
    @Volatile var fallbackChunks = 0L
        private set
    /** How far chunk timestamps landed from one chunk after the last: <0.5, <1, <2, <5, >=5 ms. A copy. */
    fun ptsJitter(): LongArray = ptsJitterCounts.copyOf()
    private val ptsJitterCounts = LongArray(AudioPts.JITTER_BUCKETS)
    /** The lip-sync delay applied to the audio right now, ns. */
    @Volatile var avDelayNs = 0L
        private set
    /** The voice leveler's gain now, dB (0 when it's off). */
    val levelerDb: Float get() = dsp.levelerDb

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
            postMixClips = postMixClips,
            inputClipChunks = inputClipChunks,
            inputClips = inputClips,
            gapFillChunks = gapFillChunks,
            fallbackChunks = fallbackChunks,
            cameraDecoderDrops = cam?.decoderDrops ?: 0,
            cameraRate = cam?.cameraRate ?: 0,
            levelerDb = levelerDb,
            aacEncoder = encoderName.orEmpty(),
            activeInput = activeInput?.name.orEmpty(),
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
    private val stage = MixStage(sampleRate, channels, dsp)

    /**
     * The level for the raw mic. Picked per chunk by the source actually open, so switching modes changes source and
     * level together, never a chunk of one at the other's level.
     */
    @Volatile var rawMicGainDb = 20f

    /** The phone mic's low cut. [LowCut.AUTO] (the default): 80 Hz on the raw mic, off with the phone's auto level. */
    @Volatile var lowCut = LowCut.AUTO

    /**
     * The voice leveler on the raw mic (the "Voice" preset; off is "Flat", the level stays where the fader puts it).
     * Off by default, as streams have always sounded; it only acts while the mic is open raw.
     */
    @Volatile var voiceLeveler = false

    /** Fold the phone mic to mono ((L+R)/2 on both channels). Off by default; the stream stays stereo either way. */
    @Volatile var monoVoice = false

    /**
     * Which mic to record from. Changing it reopens the mic between two chunks (the old one fades out). Needs
     * [context]; without one, Android picks as it always has.
     */
    @Volatile var inputChoice = InputChoice.AUTO

    /**
     * The level for a USB or wired mic, dB, used while one is the mic in use ([activeInput]). Null (the default): the
     * same level as the phone's own mic, as before. An external mic arrives at its own level, so it should get its own
     * (0 dB to start with, never the raw phone mic's +20).
     */
    @Volatile var externalGainDb: Float? = null

    /** The mic the recording is really using (null before one opens). */
    @Volatile var activeInput: ActiveInput? = null
        private set

    /** The user's mic level, -20..+30 dB; applies from the next chunk, gliding so it doesn't click. */
    var micGainDb: Float
        get() = dsp.gainDb
        set(v) { dsp.gainDb = v }
    private val bytesPerFrame = 2 * channels
    private val chunkFrames = CHUNK_FRAMES
    private val chunkNs = chunkFrames * 1_000_000_000L / sampleRate
    private val avDelay = AvDelayLine(chunkNs, chunkFrames * bytesPerFrame)
    private val toEncoder = AvDelayLine.Emit { b, p -> encode(b, p) }
    private var codecRetryAtNs = 0L
    private var codecBackoffMs = CODEC_RETRY_MIN_MS

    fun start() {
        running = true
        watchInputs()
        capture = Thread(::captureLoop, "engine-audio-capture").apply { start() }
    }

    fun stop() {
        running = false
        capture?.join(2_000)
        stopCodec()
        unwatchInputs()
    }

    /** Set when the route may have changed (a callback, on any thread); the capture thread re-reads it. */
    @Volatile private var routingDirty = false
    /** Set when the mic should reopen to pick a device again (one plugged in or out while a choice is set). */
    @Volatile private var reopenWanted = false
    private var deviceCallback: AudioDeviceCallback? = null
    @Volatile private var externalIds: Set<Int>? = null

    private fun watchInputs() {
        val am = context?.getSystemService(AudioManager::class.java) ?: return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = changed()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = changed()
            private fun changed() {
                routingDirty = true
                val ids = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrNull()
                    ?.filter { inputClassOf(it.type) == InputClass.EXTERNAL }?.map { it.id }?.toSet() ?: return
                val before = externalIds
                externalIds = ids
                // The first call lists what's there already; after that, a change with a choice set means pick again.
                if (before != null && before != ids && inputChoice != InputChoice.AUTO) reopenWanted = true
            }
        }
        if (runCatching { am.registerAudioDeviceCallback(cb, null) }.isSuccess) deviceCallback = cb
    }

    private fun unwatchInputs() {
        val cb = deviceCallback ?: return
        deviceCallback = null
        runCatching { context?.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(cb) }
    }

    /** Capture thread. False (with [codecError] set) if the encoder couldn't start; the caller retries later. */
    private fun startCodec(): Boolean {
        var c: MediaCodec? = null
        return try {
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            configureAac(c, sampleRate, stereo, bitrate)
            c.start()
            if (encoderName != c.name) Log.i(TAG, "audio encoder ${c.name}")
            encoderName = c.name
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
    private var openedChoice = InputChoice.AUTO
    private val routingListener = AudioRouting.OnRoutingChangedListener { routingDirty = true }

    /** Opens the mic ([micSources]) and watches whether the system silences it. Null if no source will record. */
    private fun openRecord(): AudioRecord? {
        val raw = rawMic
        val choice = inputChoice
        reopenWanted = false
        val preferred = context?.let { runCatching { preferredInput(it, choice) }.getOrNull() }
        val m = openMic(sampleRate, stereo, chunkFrames * bytesPerFrame, raw, unprocessedOk, preferred) ?: return null
        mic = m
        openedRaw = raw
        openedChoice = choice
        val r = m.record
        runCatching { r.addOnRoutingChangedListener(routingListener, null) }
        routingDirty = true
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
        runCatching { r.removeOnRoutingChangedListener(routingListener) }
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
        stage.start(muted)
        val pcm = ByteArray(chunkFrames * bytesPerFrame)
        val ts = AudioTimestamp()
        val pts = AudioPts(chunkNs)
        var record: AudioRecord? = null
        var everOpened = false
        var recordFrames = 0L
        val clock = ChunkClock(sampleRate, chunkNs)
        val overruns = OverrunDetector(chunkNs)
        var openAtNs = 0L
        var openBackoffMs = OPEN_RETRY_MIN_MS
        var goodChunks = 0
        var silenceNextNs = 0L
        var closeAfter = false
        val silent = ByteArray(chunkFrames * bytesPerFrame)
        try {
            while (running) {
                // The user switched the phone's auto level: reopen with the other source, at once (the last chunk of
                // the old one has faded out).
                record?.let { cur ->
                    if (closeAfter || wantsReopen()) {
                        closeAfter = false
                        Log.i(TAG, "mic: phone auto level ${if (rawMic) "off" else "on"}, input ${inputChoice.name.lowercase()}, reopening")
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
                        stage.micOpened()
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
                    // About to switch source: fade this chunk out, so the cut doesn't click.
                    closeAfter = wantsReopen()
                    // A USB mic plugged in or out, or Android moved the recording: see what it uses now.
                    if (routingDirty) checkRoute(r)
                    if (stereo) stereoProbe.add(pcm, chunkFrames)
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
                fallbackChunks = clock.fallbackChunks
                pts.jitter.copyInto(ptsJitterCounts)

                // A gap in the timeline (audio lost to an overrun, or a mic that came back late): encode that many
                // chunks of silence first, through the whole mix (a chime or the camera carries on through them),
                // then fade the real chunk in. The cut at the start of the gap can't be faded: it's already encoded.
                val rawOpen = record != null && openedRaw
                val gainDb = micGainFor(activeInput, rawOpen)
                if (pts.fillChunks > 0) {
                    for (k in 0 until pts.fillChunks) {
                        silent.fill(0)
                        mixAndEncode(silent, pts.fillFromNs + k * chunkNs, gainDb, rawOpen, micPresent = false, fadeOut = false)
                    }
                    gapFillChunks += pts.fillChunks
                    stage.micFadeInNext()
                }
                val level = mixAndEncode(pcm, ptsNs, gainDb, rawOpen, micPresent = r != null, fadeOut = closeAfter)
                chunks++
                if (level.reductionDb > HEAVY_LIMIT_DB) heavyLimitChunks++
                onLevel(level)
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

    private fun wantsReopen() = openedRaw != rawMic || openedChoice != inputChoice || reopenWanted

    /** Probes the phone mic's channels over the first 10 s of the stream (stereo only). */
    private val stereoProbe = StereoProbe(sampleRate)

    /** The level for the mic in use: an external mic's own (once one is set), the raw mic's, or the user's. */
    private fun micGainFor(input: ActiveInput?, rawOpen: Boolean): Float? {
        val ext = externalGainDb
        return when {
            ext != null && input?.inputClass == InputClass.EXTERNAL -> ext
            rawOpen -> rawMicGainDb
            else -> null
        }
    }

    /** Re-reads the device [r] records from; on a change, tells [onInputChange]. The level follows within a glide. */
    private fun checkRoute(r: AudioRecord) {
        routingDirty = false
        val d = runCatching { r.routedDevice }.getOrNull() ?: return
        val now = activeInputOf(d)
        val before = activeInput
        if (now == before) return
        activeInput = now
        val msg = inputChangeMessage(before, now)
        Log.i(TAG, "mic input: ${describe(d)}${msg?.let { " ($it)" } ?: ""}")
        runCatching { onInputChange(now, msg) }
    }

    /** Everything finer than [counters], for the session log. */
    fun diagnostics(): AudioDiagnostics = AudioDiagnostics(
        inputClipChunks = inputClipChunks,
        inputClips = inputClips,
        postMixClips = postMixClips,
        gapFillChunks = gapFillChunks,
        fallbackChunks = fallbackChunks,
        ptsJitter = ptsJitter().toList(),
        levelerDb = levelerDb,
        avDelayNs = avDelayNs,
        stereo = if (stereo) stereoProbe.stats() else null,
        encoderName = encoderName,
        encoderFormat = encoderFormat,
        encoderDelayFrames = encoderDelayFrames,
        activeInput = activeInput,
    )

    /** The AAC encoder in use, its output format, and its priming delay (frames; -1 if it didn't say). */
    @Volatile var encoderName: String? = null
        private set
    @Volatile var encoderFormat: String? = null
        private set
    @Volatile var encoderDelayFrames = -1
        private set

    /**
     * Mixes one chunk ([MixStage]) and sends it on: to the playback ring, then through the lip-sync delay to the
     * encoder. Capture thread.
     */
    private fun mixAndEncode(pcm: ByteArray, ptsNs: Long, micGainDb: Float?, rawOpen: Boolean, micPresent: Boolean, fadeOut: Boolean): MicLevel {
        // The camera is pulled every chunk, used or not, so its playout keeps time.
        val cam = camera
        val covered = cam != null && cam.fill(ptsNs, chunkFrames, camScratch)
        val wasCamera = stage.usingCamera
        stage.lowCutHz = lowCutHz(lowCut, rawOpen)
        stage.leveler = voiceLeveler && rawOpen
        stage.monoVoice = monoVoice
        stage.clockOffsetNs = avDelay.appliedNs
        val level = stage.process(
            pcm, chunkFrames, ptsNs, if (cam != null) camScratch else null, covered, micGainDb,
            muted = muted, micPresent = micPresent, micFadeOut = fadeOut,
        )
        if (stage.usingCamera != wasCamera) Log.i(TAG, if (stage.usingCamera) "audio: camera" else "audio: phone mic")
        mix?.write(pcm, chunkFrames)
        avDelay.push(pcm, stage.outPtsNs(ptsNs), videoDelayNs.ns() + userAvOffsetNs.ns(), toEncoder)
        avDelayNs = avDelay.appliedNs
        return level
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
     * stays over [MAX_QUEUED_CHUNKS] chunks drops its oldest.
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
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    encoderFormat = f.toString()
                    encoderDelayFrames = if (f.containsKey(MediaFormat.KEY_ENCODER_DELAY)) f.getInteger(MediaFormat.KEY_ENCODER_DELAY) else -1
                    Log.i(TAG, "audio encoder output: $f")
                } else if (idx >= 0) {
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
        private const val OPEN_RETRY_MIN_MS = 200L
        private const val OPEN_RETRY_MAX_MS = 2_000L
        private const val CODEC_RETRY_MIN_MS = 500L
        private const val CODEC_RETRY_MAX_MS = 5_000L
        private const val GOOD_CHUNKS_TO_RESET = 47
        /**
         * Chunks that may wait for the encoder before the oldest is dropped: ~250 ms of normal backlog, plus room for
         * the longest gap fill, which encodes up to [AudioPts.MAX_FILL_CHUNKS] chunks of silence and the real chunk
         * in one go. Without that room an encoder with few input buffers would drop the start of the very fill that
         * was meant to close the hole.
         */
        internal const val MAX_QUEUED_CHUNKS = 12 + AudioPts.MAX_FILL_CHUNKS + 1
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
 *
 * While timestamps keep failing, the extrapolation is steered gently (1/256 of the difference per chunk) towards the
 * read times, so it follows the monotonic clock instead of free-running at the nominal rate and drifting by the mic's
 * clock error. When timestamps come back, the timeline eases over to them (at most [EASE_STEP_NS] per chunk) instead
 * of stepping: the fallback is anchored at read time, which is late by the record's buffering, so the real timestamp
 * would otherwise jump the audio back by that much.
 */
internal class ChunkClock(private val sampleRate: Int, private val chunkNs: Long) {
    /** Monotonic time of the record's frame 0; 0 = not known yet (monotonic times are never 0). */
    private var baseNs = 0L
    private var lastWasFallback = false
    /** What's left of the step between the fallback timeline and the real one, still being eased out. */
    private var easeNs = 0L

    /** Chunks timed without a timestamp, since this clock was made. */
    var fallbackChunks = 0L
        private set

    /** A new record: frame counts start again from 0. */
    fun reset() { baseNs = 0L; lastWasFallback = false; easeNs = 0L }

    /** From a good AudioRecord timestamp ([tsNanoTime] at frame [tsFramePosition]). */
    fun fromTimestamp(tsNanoTime: Long, tsFramePosition: Long, chunkStartFrame: Long): Long {
        val t = tsNanoTime + (chunkStartFrame - tsFramePosition) * 1_000_000_000L / sampleRate
        if (lastWasFallback && baseNs != 0L) {
            val d = baseNs + framesNs(chunkStartFrame) - t
            easeNs = if (d > -MAX_EASE_NS && d < MAX_EASE_NS) d else 0L
        }
        lastWasFallback = false
        baseNs = t - framesNs(chunkStartFrame)
        if (easeNs != 0L) {
            val step = (easeNs / 64).coerceIn(-EASE_STEP_NS, EASE_STEP_NS).let { if (it == 0L) easeNs else it }
            easeNs -= step
        }
        return t + easeNs
    }

    /** No timestamp for this chunk; [nowNs] is just after it was read. */
    fun fallback(nowNs: Long, chunkStartFrame: Long): Long {
        fallbackChunks++
        // Carry on from where an ease left the timeline, not from the bare timestamps.
        if (easeNs != 0L) { baseNs += easeNs; easeNs = 0L }
        val target = nowNs - chunkNs - framesNs(chunkStartFrame)
        baseNs = if (baseNs == 0L) target else baseNs + (target - baseNs) / 256
        lastWasFallback = true
        return baseNs + framesNs(chunkStartFrame)
    }

    private fun framesNs(frames: Long) = frames * 1_000_000_000L / sampleRate

    private companion object {
        /** Easing moves the timeline at most this much per chunk (spacing stays within 0.5 ms of a chunk). */
        const val EASE_STEP_NS = 500_000L
        /** A bigger step than this isn't a handover to ease: something else is wrong, let [AudioPts] deal with it. */
        const val MAX_EASE_NS = 500_000_000L
    }
}

/**
 * Turns each chunk's own time into the PTS the encoder gets (pure, unit-tested): strictly increasing across mic
 * rebuilds and silence fill, evenly spaced within a source, and with no holes the encoder could fill itself.
 *
 * Each source (an AudioRecord, or the silence clock) has its own timeline; a new one can start slightly before the
 * last chunk of the old one (the mic's buffer latency vs. the silence clock). On the first chunk after
 * [sourceChanged], the new timeline is shifted just enough to follow on, and that offset is kept for the rest of that
 * source.
 *
 * Within a source each chunk is expected one chunk after the last. A timestamp within [TOLERANCE_NS] of that is
 * jitter: the PTS moves only 1/64 of the way towards it, which smooths the jitter out but still follows the real
 * clock (no long-run drift).
 *
 * A chunk a whole chunk or more late is a real gap (an overrun lost audio, or a mic came back late): it keeps its
 * time, and [fillChunks] says how many whole chunks of silence to encode before it, from [fillFromNs], so the AAC
 * timeline has no hole (the server would otherwise splice across it or a player would stutter). A chunk late by less
 * than that is only believed when the next one is late by the same amount: phones' timestamps spike now and then,
 * and taking a lone spike as a gap would leave a hole and, once the next chunk came back, a backwards step.
 *
 * A chunk [TOLERANCE_NS] or more early (the clock re-anchored backwards, or a forward spike just ended) is eased
 * towards, at most [EASE_STEP_NS] per chunk, rather than pinned a run of chunks 1 µs apart or rebased for good. Easing
 * keeps the PTS on the capture clock: a rebase that stays would leave every later chunk late by the jump (a lasting
 * lip-sync error), and a spike would add its size to that each time. Only a jump back of more than [MAX_EASE_NS]
 * (a clock reset, not jitter) rebases at once.
 */
internal class AudioPts(private val chunkNs: Long, private val maxFillChunks: Int = MAX_FILL_CHUNKS) {
    private var last = Long.MIN_VALUE
    private var offset = 0L
    private var rebase = true
    /** A sub-chunk forward jump seen on the last chunk and not yet confirmed (0: none). */
    private var pendingNs = 0L

    /** Whole chunks of silence to encode just before the chunk [next] last stamped (0: none), the first at [fillFromNs]. */
    var fillChunks = 0
        private set
    var fillFromNs = 0L
        private set

    /** How far chunks landed from where they were expected, in buckets: <0.5, <1, <2, <5 and >=5 ms. */
    val jitter = LongArray(JITTER_BUCKETS)

    /** The next chunk comes from a new source (a reopened mic, or silence). */
    fun sourceChanged() { rebase = true }

    /** The PTS (ns) to use for a chunk whose own clock says [rawNs]. */
    fun next(rawNs: Long): Long {
        fillChunks = 0
        if (last == Long.MIN_VALUE) {
            rebase = false
            offset = 0L
            pendingNs = 0L
            last = rawNs
            return rawNs
        }
        if (rebase) {
            rebase = false
            pendingNs = 0L
            offset = if (rawNs < last + chunkNs) last + chunkNs - rawNs else 0L
        }
        val p = rawNs + offset
        val expected = last + chunkNs
        val err = p - expected
        val wasPending = pendingNs
        pendingNs = 0L
        val out = when {
            err >= chunkNs -> {
                val n = err / chunkNs
                if (n <= maxFillChunks) { fillChunks = n.toInt(); fillFromNs = expected }
                p // what's left (under a chunk) stays a short gap
            }
            err >= TOLERANCE_NS -> {
                // Late by part of a chunk: a gap only if the last chunk was late by about as much.
                val d = err - wasPending
                if (wasPending != 0L && d > -TOLERANCE_NS && d < TOLERANCE_NS) p
                else { pendingNs = err; expected + TOLERANCE_NS / 64 }
            }
            err <= -MAX_EASE_NS -> { offset -= err; expected }
            err <= -TOLERANCE_NS -> expected - EASE_STEP_NS
            else -> expected + err / 64
        }
        val a = if (err < 0) -err else err
        jitter[when { a < 500_000 -> 0; a < 1_000_000 -> 1; a < 2_000_000 -> 2; a < TOLERANCE_NS -> 3; else -> 4 }]++
        last = out
        return out
    }

    companion object {
        const val TOLERANCE_NS = 5_000_000L
        /** Easing back moves the PTS at most this much per chunk: frames overlap by at most 0.5 ms meanwhile. */
        const val EASE_STEP_NS = 500_000L
        /** A step back bigger than this is a clock reset, not something to ease out over minutes: rebase. */
        const val MAX_EASE_NS = 500_000_000L
        /** Gaps up to ~213 ms are filled; longer ones are left for the server. */
        const val MAX_FILL_CHUNKS = 10
        const val JITTER_BUCKETS = 5
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
