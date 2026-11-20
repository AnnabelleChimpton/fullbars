package app.irlsupreme.engine.audio

import android.media.AudioDeviceInfo
import app.irlsupreme.engine.audio.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Choosing the mic, telling the streamer when it changes, a silenced mic check, and the stereo probe. */
class MicInputTest {
    private val builtIn = 1 to AudioDeviceInfo.TYPE_BUILTIN_MIC
    private val usb = 7 to AudioDeviceInfo.TYPE_USB_DEVICE
    private val wired = 9 to AudioDeviceInfo.TYPE_WIRED_HEADSET
    private val bt = 4 to AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    @Test fun `kinds of mic`() {
        assertEquals(InputClass.BUILT_IN, inputClassOf(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(InputClass.EXTERNAL, inputClassOf(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(InputClass.EXTERNAL, inputClassOf(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(InputClass.BLUETOOTH, inputClassOf(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(InputClass.OTHER, inputClassOf(AudioDeviceInfo.TYPE_TELEPHONY))
    }

    @Test fun `auto leaves it to Android, phone and external ask for their kind, Bluetooth never`() {
        val all = listOf(bt, builtIn, usb, wired)
        assertNull(pickInputId(all, InputChoice.AUTO))
        assertEquals(1, pickInputId(all, InputChoice.PHONE))
        assertEquals(7, pickInputId(all, InputChoice.EXTERNAL))
        assertNull(pickInputId(listOf(builtIn, bt), InputChoice.EXTERNAL)) // nothing plugged in: Android's pick
    }

    @Test fun `the streamer hears about a change of kind, not a change of device within one`() {
        val phone = ActiveInput(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Phone mic")
        val rx = ActiveInput(7, AudioDeviceInfo.TYPE_USB_DEVICE, "Wireless GO II RX")
        assertEquals("Using Wireless GO II RX", inputChangeMessage(phone, rx))
        assertEquals("External mic disconnected", inputChangeMessage(rx, phone))
        assertNull(inputChangeMessage(null, rx)) // the first mic of a session
        assertNull(inputChangeMessage(phone, phone.copy(id = 2))) // the phone's other mic
    }

    @Test fun `the mic check finds its own recording among the silenced ones`() {
        val configs = listOf(11 to false, 42 to true)
        assertEquals(true, silencedIn(configs, 42))
        assertEquals(false, silencedIn(configs, 11))
        assertNull(silencedIn(configs, 5))
        assertNull(silencedIn(emptyList(), 42))
    }

    @Test fun `the stereo probe measures each channel and how alike they are`() {
        val p = StereoProbe(48_000, seconds = 1)
        assertNull(p.stats())
        val x = Signals.sine(440.0, 0.5, 48_000)
        for (f in 0 until 48_000) x[2 * f + 1] *= 0.25f // right is 12 dB down, same voice
        val pcm = Signals.toPcm(x)
        p.add(pcm, 48_000)
        assertTrue(p.done)
        val s = p.stats()!!
        assertEquals(-9.0f, s.rmsLDb, 0.2f) // a 0.5 sine: -6 dB peak, -9 dB RMS
        assertEquals(-21.0f, s.rmsRDb, 0.2f)
        assertEquals(1f, s.correlation, 0.01f)
        p.add(pcm, 48_000) // done: ignored
        assertEquals(s, p.stats())
    }

    @Test fun `uncorrelated channels read as such`() {
        val p = StereoProbe(48_000, seconds = 1)
        val l = Signals.pink(48_000, 1); val r = Signals.pink(48_000, 2)
        val x = FloatArray(96_000) { if (it % 2 == 0) l[it / 2] else r[it / 2] }
        p.add(Signals.toPcm(x), 48_000)
        assertTrue(abs(p.stats()!!.correlation) < 0.1f)
    }
}
