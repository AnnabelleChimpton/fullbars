package app.irlsupreme.engine.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    @Volatile var gainDb = 0f
    @Volatile var raw = false
    @Volatile var rawGainDb = 20f
    /** The same low cut, leveler and mono fold as a stream (see [AudioPipeline]), so the check sounds like one. */
    @Volatile var lowCut = LowCut.AUTO
    @Volatile var voiceLeveler = false
    @Volatile var monoVoice = false
    @Volatile private var running = false
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

    private fun loop() {
        val channels = if (stereo) 2 else 1
        val frames = AudioPipeline.CHUNK_FRAMES
        val pcm = ByteArray(frames * channels * 2)
        val dsp = MicDsp(sampleRate, channels)
        val unprocessed = unprocessedSupported(context)
        var mic: OpenMic? = null
        var micRaw = false
        try {
            while (running) {
                if (mic == null || micRaw != raw) {
                    mic?.close()
                    micRaw = raw
                    mic = openMic(sampleRate, stereo, pcm.size, micRaw, unprocessed)
                    _unavailable.value = mic == null
                    if (mic == null) { _level.value = MicLevel.SILENT; Thread.sleep(1_000); continue }
                    dsp.micReopened()
                }
                var off = 0
                var empty = 0
                while (off < pcm.size && running) {
                    val n = mic.record.read(pcm, off, pcm.size - off)
                    if (n < 0 || (n == 0 && ++empty > 50)) { off = -1; break }
                    if (n == 0) Thread.sleep(5)
                    off += n
                }
                if (off < 0) { mic.close(); mic = null; continue }
                if (!running) break
                dsp.gainDb = if (micRaw) rawGainDb else gainDb
                _level.value = dsp.process(pcm, frames, lowCutHz = lowCutHz(lowCut, micRaw), leveler = voiceLeveler && micRaw, mono = monoVoice)
                heard.write(pcm, frames)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w("Mic", "mic check stopped", t)
        } finally {
            mic?.close()
        }
    }

    private companion object {
        const val MAX_RECENT_S = 10
    }
}
