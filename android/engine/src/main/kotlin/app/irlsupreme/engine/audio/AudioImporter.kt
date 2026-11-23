package app.irlsupreme.engine.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * A short sound for an alert, ready to mix: 48 kHz stereo 16-bit PCM, trimmed, at a set loudness. [attackMs] is
 * where it lands ([ImportMath.attackFrame]), so it can start that much before the alert is fully shown.
 */
class AlertClip(val pcm: ShortArray, val attackMs: Int) {
    val seconds: Float get() = pcm.size / 2f / ImportMath.RATE

    /** For playing it on the phone (the preview). */
    fun toPcmClip(): PcmClip = PcmClip(pcm, ImportMath.RATE, 2)

    fun save(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(attackMs)
            out.writeInt(pcm.size)
            val bytes = ByteArray(pcm.size * 2)
            for (i in pcm.indices) { bytes[2 * i] = pcm[i].toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
            out.write(bytes)
        }
        if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("couldn't save") }
    }

    companion object {
        private const val MAGIC = 0x46424131 // "FBA1"

        /** Null when the file is missing or isn't one of ours. */
        fun load(file: File): AlertClip? = runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MAGIC) return null
                val attack = inp.readInt()
                val n = inp.readInt()
                if (n < 0 || n > ImportMath.RATE * 2 * (ImportMath.ALERT_MAX_S + 1)) return null
                val bytes = ByteArray(n * 2)
                inp.readFully(bytes)
                AlertClip(ShortArray(n) { ((bytes[2 * it].toInt() and 0xff) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }, attack)
            }
        }.getOrNull()
    }
}

/**
 * What a first pass over a music file found: where its sound starts and ends (frames at 48 kHz, silence trimmed),
 * the gain that brings it to [ImportMath.MUSIC_TARGET_LUFS], and its length.
 */
data class MusicInfo(val startFrame: Long, val endFrame: Long, val gainDb: Double, val durationMs: Long)

/** Why an import didn't work. The app words each one plainly. */
enum class ImportFailure { UNREADABLE, SILENT, TOO_LONG, TOO_SHORT }

sealed class ImportResult<out T> {
    /** [cut]: an alert sound longer than [ImportMath.ALERT_MAX_S] was cut to fit. */
    data class Ok<T>(val value: T, val cut: Boolean = false) : ImportResult<T>()
    data class Failed(val why: ImportFailure) : ImportResult<Nothing>()
}

/**
 * Turns a picked audio file into something the stream can play: a whole short clip for an alert ([decodeAlert]), or
 * the facts a looping card music needs ([analyzeMusic]); the music itself is decoded again as it plays. Both run on
 * the caller's (background) thread and take a few seconds at most for an alert, longer for a long song.
 */
object AudioImporter {
    /** Music's longest. */
    const val MUSIC_MAX_S = 15 * 60
    /** Shorter than this can't loop without sounding like a stutter. */
    const val MUSIC_MIN_S = 1
    /** Leading silence an alert file may have before its sound starts. */
    private const val ALERT_LEAD_MAX_S = 60

    /**
     * A viewer's TTS message synthesised to [file] (any rate, usually 16-24 kHz mono WAV), as a clip for
     * [app.irlsupreme.engine.StreamEngine.playSpeech]: silence trimmed at the ends (never inside), cut to
     * [ImportMath.SPEECH_MAX_S] with a fade, at the alert sounds' loudness so a voice never towers over the streamer.
     */
    fun decodeSpeech(file: File): ImportResult<AlertClip> = decodeAlert(file, ImportMath.SPEECH_MAX_S)

    fun decodeAlert(file: File, maxS: Int = ImportMath.ALERT_MAX_S): ImportResult<AlertClip> {
        val dec = AudioFileDecoder(file.path)
        try {
            try { dec.open() } catch (_: Exception) { return ImportResult.Failed(ImportFailure.UNREADABLE) }
            val rate = ImportMath.RATE
            val keepMax = (maxS + 1) * rate
            val preroll = rate * ImportMath.EDGE_FADE_MS / 1000
            var buf = FloatArray(rate * 2 * 2)
            var frames = 0
            var firstLoud = -1
            var dropped = 0L // leading silence thrown away
            val block = FloatArray(BLOCK * 2)
            while (true) {
                val n = try { dec.read(block, BLOCK) } catch (_: Exception) { return ImportResult.Failed(ImportFailure.UNREADABLE) }
                if (n < 0) break
                if (buf.size < (frames + n) * 2) buf = buf.copyOf(maxOf(buf.size * 2, (frames + n) * 2))
                System.arraycopy(block, 0, buf, frames * 2, n * 2)
                if (firstLoud < 0) {
                    for (f in 0 until n) if (ImportMath.frameLoud(block, f, 2, ImportMath.SILENCE)) { firstLoud = frames + f; break }
                }
                frames += n
                if (firstLoud < 0) {
                    // Still silence: keep only the last few ms of it.
                    if (frames > rate) {
                        val keep = preroll
                        System.arraycopy(buf, (frames - keep) * 2, buf, 0, keep * 2)
                        dropped += frames - keep
                        frames = keep
                    }
                    if (dropped > ALERT_LEAD_MAX_S.toLong() * rate) return ImportResult.Failed(ImportFailure.SILENT)
                } else if (frames - firstLoud >= keepMax) break
            }
            if (frames == 0 && firstLoud < 0 && dropped == 0L) return ImportResult.Failed(ImportFailure.UNREADABLE)
            val range = ImportMath.soundRange(buf, frames, 2) ?: return ImportResult.Failed(ImportFailure.SILENT)
            val (clip, cut) = finishAlert(buf, range.first, range.last + 1, frames, maxS)
            return ImportResult.Ok(clip, cut)
        } finally {
            dec.close()
        }
    }

    /**
     * Trims [x] to [from, until) with a few ms of what was before and after it, cuts it to [maxS] seconds
     * (fading the last [ImportMath.CUT_FADE_MS]), fades the edges, brings it to [ImportMath.ALERT_TARGET_LUFS] and
     * finds its hit. Pure. Also says whether it was cut.
     */
    internal fun finishAlert(x: FloatArray, from: Int, until: Int, frames: Int, maxS: Int = ImportMath.ALERT_MAX_S): Pair<AlertClip, Boolean> {
        val rate = ImportMath.RATE
        val edge = rate * ImportMath.EDGE_FADE_MS / 1000
        val start = (from - edge).coerceAtLeast(0)
        val max = maxS * rate
        val cut = until - start > max
        val end = if (cut) start + max else (until + edge).coerceAtMost(frames)
        val n = end - start
        val y = x.copyOfRange(start * 2, end * 2)
        ImportMath.fadeEdges(y, n, 2, edge, if (cut) rate * ImportMath.CUT_FADE_MS / 1000 else edge)
        val meter = LoudnessMeter(2).apply { add(y, n) }
        val gainDb = ImportMath.loudnessGainDb(meter.integratedLufs(), ImportMath.peak(y), ImportMath.ALERT_TARGET_LUFS) ?: 0.0
        ImportMath.applyGain(y, n * 2, ImportMath.dbToGain(gainDb))
        val attack = ImportMath.framesToMs(ImportMath.attackFrame(y, n, 2).toLong()).toInt()
        return AlertClip(ImportMath.toShorts(y, n * 2), attack) to cut
    }

    /**
     * One pass over a music file: its sound's start and end, its loudness and length. [onProgress] gets 0..1 now and
     * then when the file says how long it is.
     */
    fun analyzeMusic(file: File, onProgress: (Float) -> Unit = {}): ImportResult<MusicInfo> {
        val dec = AudioFileDecoder(file.path)
        try {
            try { dec.open() } catch (_: Exception) { return ImportResult.Failed(ImportFailure.UNREADABLE) }
            val rate = ImportMath.RATE
            val maxFrames = (MUSIC_MAX_S + 1).toLong() * rate
            if (dec.durationUs > maxFrames * 1_000_000 / rate) return ImportResult.Failed(ImportFailure.TOO_LONG)
            val expected = dec.durationUs * rate / 1_000_000
            val meter = LoudnessMeter(2)
            val block = FloatArray(BLOCK * 2)
            var total = 0L
            var first = -1L
            var last = -1L
            var peak = 0f
            var nextReport = rate * 10L
            while (true) {
                val n = try { dec.read(block, BLOCK) } catch (_: Exception) { return ImportResult.Failed(ImportFailure.UNREADABLE) }
                if (n < 0) break
                for (f in 0 until n) if (ImportMath.frameLoud(block, f, 2, ImportMath.SILENCE)) {
                    if (first < 0) first = total + f
                    last = total + f
                }
                peak = maxOf(peak, ImportMath.peak(block, n * 2))
                meter.add(block, n)
                total += n
                if (total > maxFrames) return ImportResult.Failed(ImportFailure.TOO_LONG)
                if (expected > 0 && total >= nextReport) { onProgress((total.toFloat() / expected).coerceIn(0f, 1f)); nextReport += rate * 10L }
            }
            if (total == 0L) return ImportResult.Failed(ImportFailure.UNREADABLE)
            if (first < 0) return ImportResult.Failed(ImportFailure.SILENT)
            val end = last + 1
            if (end - first < MUSIC_MIN_S.toLong() * rate) return ImportResult.Failed(ImportFailure.TOO_SHORT)
            val gainDb = ImportMath.loudnessGainDb(meter.integratedLufs(), peak, ImportMath.MUSIC_TARGET_LUFS)
                ?: return ImportResult.Failed(ImportFailure.SILENT)
            return ImportResult.Ok(MusicInfo(first, end, gainDb, ImportMath.framesToMs(end - first)))
        } finally {
            dec.close()
        }
    }

    private const val BLOCK = 4096
}

/** An [AlertClip] in the stream's layout. */
internal object AlertSoundLayout {
    /**
     * [clip]'s samples for a stream at [rate] Hz, stereo or mono: as they are for the usual 48 kHz stereo, folded to
     * mono or converted ([CameraResampler]) otherwise.
     */
    fun forStream(clip: AlertClip, rate: Int, stereo: Boolean): ShortArray {
        val outCh = if (stereo) 2 else 1
        if (rate == ImportMath.RATE && stereo) return clip.pcm
        val frames = clip.pcm.size / 2
        if (rate == ImportMath.RATE) return ShortArray(frames) { ((clip.pcm[2 * it] + clip.pcm[2 * it + 1]) / 2).toShort() }
        val r = CameraResampler(rate, outCh)
        r.configure(ImportMath.RATE, 2)
        // The clip plus enough silence to flush the converter's taps.
        val padded = clip.pcm.copyOf(clip.pcm.size + 2 * 8 * CameraResampler.HALF_TAPS * 2)
        r.process(padded, padded.size / 2, 0L)
        return r.out.copyOf(r.outFrames * outCh)
    }
}
