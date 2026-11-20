package app.irlsupreme.engine.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.util.Log

/**
 * Which Android mic sources to try, in order.
 *
 * With the phone's auto level on ([raw] false), the camcorder source: tuned for video, and on many phones it also
 * levels the voice automatically. That's what most streaming apps use, and it's where "my phone makes me loud and
 * harsh" comes from: the phone turns everything up, room noise included, and pushes loud speech into clipping.
 *
 * With it off, the unprocessed source where the phone says it has one (no gain, no noise suppression, flat), else voice
 * recognition (Android requires it without noise reduction and recommends no auto gain), else the plain mic. The level
 * is then the user's, set with [MicDsp.gainDb].
 */
internal fun micSources(raw: Boolean, unprocessedOk: Boolean): IntArray = when {
    !raw -> intArrayOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)
    unprocessedOk -> intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
    else -> intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
}

/** An open, recording mic, and the auto-gain effect switched off on it (released with it). */
internal class OpenMic(val record: AudioRecord, val source: Int, private val agc: AutomaticGainControl?) {
    fun close() {
        runCatching { record.stop() }
        runCatching { record.release() }
        agc?.let { runCatching { it.release() } }
    }
}

/**
 * Opens and starts the first of [micSources] that records. With [raw], also switches off the auto-gain effect if the
 * phone attached one to the session (some do, whatever the source). With [preferred], asks Android to record from
 * that device (a USB or wired mic, or the phone's own) rather than the one it would pick; it's a request, and the
 * device it really used is logged. Null if none will record.
 */
@SuppressLint("MissingPermission") // RECORD_AUDIO is checked before anything opens the mic
internal fun openMic(
    sampleRate: Int,
    stereo: Boolean,
    minBytes: Int,
    raw: Boolean,
    unprocessedOk: Boolean,
    preferred: AudioDeviceInfo? = null,
): OpenMic? {
    val channelMask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
    for (src in micSources(raw, unprocessedOk)) {
        val r = runCatching {
            AudioRecord.Builder()
                .setAudioSource(src)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                // ~170 ms at 48 kHz stereo: headroom before an overrun if the capture thread stalls. Reads stay
                // chunked and timestamps come from the record, so a bigger buffer adds no latency.
                .setBufferSizeInBytes(maxOf(minBuf, minBytes) * 8)
                .build()
        }.getOrNull() ?: continue
        // Before starting: the route is picked when recording starts. The builder has no setter for this.
        if (preferred != null && r.state == AudioRecord.STATE_INITIALIZED) runCatching { r.setPreferredDevice(preferred) }
        val started = r.state == AudioRecord.STATE_INITIALIZED && runCatching { r.startRecording() }.isSuccess &&
            r.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) { runCatching { r.release() }; continue }
        val agc = if (raw && AutomaticGainControl.isAvailable()) {
            runCatching { AutomaticGainControl.create(r.audioSessionId)?.apply { enabled = false } }.getOrNull()
        } else null
        val routed = runCatching { r.routedDevice }.getOrNull()
        Log.i(
            "Mic",
            "mic open: source $src, phone auto level ${if (raw) "off" else "on"}, " +
                "device ${routed?.let { describe(it) } ?: "unknown"}${preferred?.let { ", asked for ${describe(it)}" } ?: ""}, " +
                "${runCatching { r.sampleRate }.getOrDefault(0)} Hz",
        )
        return OpenMic(r, src, agc)
    }
    return null
}

/** Whether this phone says it has a truly unprocessed mic source. */
fun unprocessedSupported(context: Context): Boolean = runCatching {
    context.getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
}.getOrDefault(false)

/** What kind of mic a device is. Levels are kept per kind: an external mic arrives at its own level, not the phone's. */
enum class InputClass { BUILT_IN, EXTERNAL, BLUETOOTH, OTHER }

/** The kind of an [AudioDeviceInfo.getType]. */
fun inputClassOf(type: Int): InputClass = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputClass.BUILT_IN
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
    AudioDeviceInfo.TYPE_DOCK -> InputClass.EXTERNAL
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID -> InputClass.BLUETOOTH
    else -> InputClass.OTHER
}

/** The mic a recording is really using: Android's device id and type, and a name to show. */
data class ActiveInput(val id: Int, val type: Int, val name: String) {
    val inputClass: InputClass get() = inputClassOf(type)
}

/** Which mic to record from (Settings › Microphone › Input). */
enum class InputChoice {
    /** Android's own pick: a USB or wired mic when one is plugged in, else the phone's. Never Bluetooth. */
    AUTO,
    /** Always the phone's own mic, even with something plugged in. */
    PHONE,
    /** The USB or wired mic, when there is one (else the phone's). */
    EXTERNAL,
}

/**
 * The device to ask for, by id, for [choice] among the inputs [devices] (id to type), or null to leave it to Android.
 * Bluetooth is never picked: recording from it needs the phone in call mode, which reroutes the whole phone's audio
 * and adds call-grade processing.
 */
fun pickInputId(devices: List<Pair<Int, Int>>, choice: InputChoice): Int? = when (choice) {
    InputChoice.AUTO -> null
    InputChoice.PHONE -> devices.firstOrNull { inputClassOf(it.second) == InputClass.BUILT_IN }?.first
    InputChoice.EXTERNAL -> devices.firstOrNull { inputClassOf(it.second) == InputClass.EXTERNAL }?.first
}

/**
 * What to tell the streamer when the mic in use changes kind ([from] to [to]), or null when it doesn't (the same kind,
 * or the first mic of a session).
 */
fun inputChangeMessage(from: ActiveInput?, to: ActiveInput?): String? {
    if (from == null || to == null) return null
    val a = from.inputClass
    val b = to.inputClass
    if (a == b) return null
    return when {
        a == InputClass.EXTERNAL -> "External mic disconnected"
        else -> "Using ${to.name}"
    }
}

/** "USB-C receiver (USB)" from a device: its product name and kind. */
internal fun describe(d: AudioDeviceInfo): String = "${d.productName ?: "mic"} (${inputTypeName(d.type)})"

internal fun inputTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in"
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line in"
    AudioDeviceInfo.TYPE_DOCK -> "dock"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
    else -> "type $type"
}

/** The phone mic or external mic a [d] is, to show and keep: the name a person would recognise. */
fun activeInputOf(d: AudioDeviceInfo): ActiveInput {
    val name = when (inputClassOf(d.type)) {
        InputClass.BUILT_IN -> "Phone mic"
        else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: inputTypeName(d.type).replaceFirstChar { it.uppercase() } + " mic"
    }
    return ActiveInput(d.id, d.type, name)
}

/** The phone's audio inputs right now (never throws; empty if it can't tell). */
fun audioInputs(context: Context): List<AudioDeviceInfo> = runCatching {
    context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.toList()
}.getOrNull() ?: emptyList()

/** The plugged-in (USB or wired) mics, to offer as a choice. */
fun externalInputs(context: Context): List<ActiveInput> =
    audioInputs(context).filter { inputClassOf(it.type) == InputClass.EXTERNAL }.map(::activeInputOf)

/** The device to ask for when opening the mic with [choice], or null to leave it to Android. */
fun preferredInput(context: Context, choice: InputChoice): AudioDeviceInfo? {
    if (choice == InputChoice.AUTO) return null
    val all = audioInputs(context)
    val id = pickInputId(all.map { it.id to it.type }, choice) ?: return null
    return all.firstOrNull { it.id == id }
}
