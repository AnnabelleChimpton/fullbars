package app.irlsupreme.engine.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/** Whether the mic check can hear the mic. */
enum class MicAvailability {
    OK,
    /** No mic source will open (another app has it exclusively, or there's no permission). */
    NO_MIC,
    /**
     * The mic is open but Android is feeding it silence, because another app (a call, the assistant, a recorder) has
     * priority. Android 10+ does this instead of refusing to open, so it looks like a very quiet mic unless asked.
     */
    SILENCED,
}

/**
 * Whether the recording with session [sessionId] is silenced, from the active recording configurations ([configs]: each
 * one's session id and whether it's silenced). Null when none of them is ours (then ask the record itself).
 */
internal fun silencedIn(configs: List<Pair<Int, Boolean>>, sessionId: Int): Boolean? =
    configs.firstOrNull { it.first == sessionId }?.second

/**
 * The mic on its own, for setting the level before going live (Settings › Microphone): the same sources and level stage
 * as a stream, metered, with nothing encoded or sent. Not while streaming: the stream owns the mic then, and the page
 * reads [app.irlsupreme.engine.StreamEngine.micLevel] instead.
 */
class MicCheck(private val context: Context, private val sampleRate: Int = 48_000, private val stereo: Boolean = true) {
    private val _level = MutableStateFlow(MicLevel.SILENT)
    val level: StateFlow<MicLevel> = _level.asStateFlow()

    /** True while no mic source will open (another app has it, or there's no permission). */
    private val _unavailable = MutableStateFlow(false)
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    private val _availability = MutableStateFlow(MicAvailability.OK)
    /** OK, no mic, or open but silenced by another app; see [MicAvailability]. */
    val availability: StateFlow<MicAvailability> = _availability.asStateFlow()

    private val _input = MutableStateFlow<ActiveInput?>(null)
    /** The mic the check is really hearing (the phone's, or a USB or wired one); null while none is open. */
    val input: StateFlow<ActiveInput?> = _input.asStateFlow()

    @Volatile var gainDb = 0f
    @Volatile var raw = false
    @Volatile var rawGainDb = 20f
    /** The level while a USB or wired mic is in use; null: the same as the phone's (as a stream does by default). */
    @Volatile var externalGainDb: Float? = null
    /** Which mic to hear; a change reopens it. */
    @Volatile var inputChoice = InputChoice.AUTO
    /** The same low cut, leveler and mono fold as a stream (see [AudioPipeline]), so the check sounds like one. */
    @Volatile var lowCut = LowCut.AUTO
    @Volatile var voiceLeveler = false
    @Volatile var monoVoice = false
    @Volatile private var running = false
    @Volatile private var silenced = false
    private var thread: Thread? = null

    /** What the check heard after the level stage; cleared when a new check starts, kept after it stops. */
    private val heard = PcmRing(sampleRate, if (stereo) 2 else 1, MAX_RECENT_S)

    /**
     * The last [seconds] (at most 10) the mic check heard, after the level stage (gain and limiter: what viewers would
     * get), for "play it back" on Settings > Microphone. Kept after [stop] until the next [start]. Null before anything
     * was heard.
     */
    fun recent(seconds: Int = MAX_RECENT_S): PcmClip? = heard.recent(seconds.coerceAtMost(MAX_RECENT_S))

    fun start() {
        if (running) return
        heard.clear()
        running = true
        thread = Thread(::loop, "mic-check").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        _level.value = MicLevel.SILENT
    }

    private fun publish(open: Boolean) {
        _unavailable.value = !open
        _availability.value = when {
            !open -> MicAvailability.NO_MIC
            silenced -> MicAvailability.SILENCED
            else -> MicAvailability.OK
        }
    }

    private fun watch(r: AudioRecord): AudioManager.AudioRecordingCallback? {
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                silenced = silencedIn(configs.map { it.clientAudioSessionId to it.isClientSilenced }, r.audioSessionId)
                    ?: readSilenced(r)
                publish(open = true)
            }
        }
        silenced = readSilenced(r)
        return if (runCatching { r.registerAudioRecordingCallback(directExecutor, cb) }.isSuccess) cb else null
    }

    private fun readSilenced(r: AudioRecord) =
        runCatching { r.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)

    private fun loop() {
        val channels = if (stereo) 2 else 1
        val frames = AudioPipeline.CHUNK_FRAMES
        val pcm = ByteArray(frames * channels * 2)
        val dsp = MicDsp(sampleRate, channels)
        val unprocessed = unprocessedSupported(context)
        var mic: OpenMic? = null
        var callback: AudioManager.AudioRecordingCallback? = null
        var micRaw = false
        var micChoice = InputChoice.AUTO
        var lastPollMs = 0L
        // Every close goes through here: the callback must be unregistered first, or it outlives the record.
        fun close() {
            val m = mic ?: return
            callback?.let { cb -> runCatching { m.record.unregisterAudioRecordingCallback(cb) } }
            callback = null
            m.close()
            mic = null
            silenced = false
            _input.value = null
        }
        try {
            while (running) {
                if (mic == null || micRaw != raw || micChoice != inputChoice) {
                    close()
                    micRaw = raw
                    micChoice = inputChoice
                    val preferred = runCatching { preferredInput(context, micChoice) }.getOrNull()
                    val opened = openMic(sampleRate, stereo, pcm.size, micRaw, unprocessed, preferred)
                    mic = opened
                    if (opened == null) { publish(open = false); _level.value = MicLevel.SILENT; Thread.sleep(1_000); continue }
                    callback = watch(opened.record)
                    publish(open = true)
                    _input.value = runCatching { opened.record.routedDevice }.getOrNull()?.let(::activeInputOf)
                    dsp.micReopened()
                }
                val m = mic ?: continue
                var off = 0
                var empty = 0
                while (off < pcm.size && running) {
                    val n = m.record.read(pcm, off, pcm.size - off)
                    if (n < 0 || (n == 0 && ++empty > 50)) { off = -1; break }
                    if (n == 0) Thread.sleep(5)
                    off += n
                }
                if (off < 0) { close(); continue }
                if (!running) break
                // Once a second, ask directly too: some phones never call the callback.
                val now = System.currentTimeMillis()
                if (now - lastPollMs >= 1_000) {
                    lastPollMs = now
                    silenced = readSilenced(m.record)
                    publish(open = true)
                    runCatching { m.record.routedDevice }.getOrNull()?.let(::activeInputOf)?.let { if (it != _input.value) _input.value = it }
                }
                val ext = externalGainDb
                dsp.gainDb = when {
                    ext != null && _input.value?.inputClass == InputClass.EXTERNAL -> ext
                    micRaw -> rawGainDb
                    else -> gainDb
                }
                _level.value = dsp.process(pcm, frames, lowCutHz = lowCutHz(lowCut, micRaw), leveler = voiceLeveler && micRaw, mono = monoVoice)
                heard.write(pcm, frames)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w("Mic", "mic check stopped", t)
        } finally {
            close()
        }
    }

    private companion object {
        const val MAX_RECENT_S = 10
        val directExecutor = Executor { it.run() }
    }
}
