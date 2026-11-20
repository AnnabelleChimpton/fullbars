package app.irlsupreme.dji

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

/**
 * Where the Pocket 3 is in starting its stream. [label] is the live screen's line: while starting, it carries the step
 * number ("2 of 5 · Tap OK on the Pocket 3", see [PocketStep]); a failure is its reason alone, kept short. [step] is
 * the step it's on, or the one that failed.
 */
sealed class PocketState(val label: String, val step: PocketStep?) {
    /** Fullbars' own hotspot is starting (only when that's the choice). */
    data object StartingHotspot : PocketState(PocketStep.HOTSPOT.progress("Starting the hotspot"), PocketStep.HOTSPOT)
    data object Connecting : PocketState(PocketStep.BLUETOOTH.progress("Connecting to the Pocket 3"), PocketStep.BLUETOOTH)
    data object Pairing : PocketState(PocketStep.BLUETOOTH.progress("Pairing with the Pocket 3"), PocketStep.BLUETOOTH)
    data object ApproveOnCamera : PocketState(PocketStep.BLUETOOTH.progress("Tap OK on the Pocket 3"), PocketStep.BLUETOOTH)
    data object Preparing : PocketState(PocketStep.JOIN.progress("Getting the Pocket 3 ready"), PocketStep.JOIN)
    /** [hotspot]: false when the Pocket 3 joins a Wi‑Fi rather than a hotspot, so the line names the right thing. */
    data class JoiningWifi(val hotspot: Boolean = true) :
        PocketState(PocketStep.JOIN.progress(if (hotspot) "Pocket 3 joining the hotspot" else "Pocket 3 joining the Wi‑Fi"), PocketStep.JOIN)
    data object Starting : PocketState(PocketStep.START.progress("Pocket 3 starting its stream"), PocketStep.START)
    /** The Pocket 3 says it streams; its picture reaching the phone is the engine's to see (step 5). */
    data class Streaming(val battery: Int?) :
        PocketState("Streaming" + (battery?.let { " · battery $it%" } ?: ""), PocketStep.PICTURE)
    data class Failed(val reason: String, val at: PocketStep? = null) : PocketState(reason, at)
    data class Reconnecting(val reason: String, val inSeconds: Int, val at: PocketStep? = null) :
        PocketState(reason.trimEnd('.') + " · trying again", at)
    data object Stopped : PocketState("Pocket 3 stopped", null)
}

/**
 * Starts (and stops) a DJI Pocket 3's livestream over Bluetooth LE: connect, pair, prepare, send it the phone's
 * hotspot login, configure 1080p30 to our RTMP URL, start; then keep the session alive and read battery pushes.
 * DJI's own app (Mimo) isn't involved and nothing has to be re-done after the camera is power-cycled.
 *
 * Adapted from Osmosis's RtmpStreamer + GattClient (MIT, see [Duml.kt]); the frames are djictl's Pocket 3 flow.
 * Unofficial: DJI may change this in firmware (Pocket 3 support is listed to 2026-12-31).
 */
@SuppressLint("MissingPermission") // the app asks for BLUETOOTH_CONNECT before it gets here
class PocketLink(
    private val context: Context,
    private val device: BluetoothDevice,
    /** Per-install app identity the camera remembers its approval by (32 chars, persisted). */
    private val identity: String,
    private val onState: (PocketState) -> Unit,
) {
    /** [hotspot]: the network is a hotspot (the phone's or Fullbars'), not a Wi‑Fi both join; it only changes words. */
    class Settings(val ssid: String, val password: String, val url: String, val bitrateKbps: Int = 6000, val hotspot: Boolean = true)

    private val thread = HandlerThread("dji-link").apply { start() }
    private val handler = Handler(thread.looper)
    private var gatt: BluetoothGatt? = null
    private val reassembler = DumlReassembler()
    private val cccdQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val writeLock = Object()

    private val lock = Object()
    private var reply: DjiMessage? = null
    @Volatile private var pairApproved = false
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile var streaming = false
        private set
    @Volatile var battery: Int? = null
        private set
    private var settings: Settings? = null
    /** The step the last state was on, so a failure says which step failed. */
    @Volatile private var step = PocketStep.BLUETOOTH

    private fun emit(s: PocketState) {
        s.step?.let { step = it }
        onState(s)
    }

    fun start(s: Settings) {
        settings = s
        closed = false
        emit(PocketState.Connecting)
        // Revoked in Settings since setup (or never granted on a restored phone): say so instead of crashing the app.
        if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return fail(NO_PERMISSION)
        }
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            return fail(NO_PERMISSION)
        }
        handler.postDelayed(readyGuard, READY_TIMEOUT_MS)
        handler.postDelayed(keepalive, KEEPALIVE_MS)
    }

    /** Stops the camera's stream (if it started) and drops the link. Safe to call any time. */
    fun stop() {
        if (closed) return
        if (streaming) {
            write(DjiCommands.stopStream())
            streaming = false
            Thread.sleep(150) // let the stop frame leave before the link goes
        }
        close()
        emit(PocketState.Stopped)
    }

    private fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        synchronized(lock) { lock.notifyAll() }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        thread.quitSafely()
    }

    /** [at]: the step that failed; the one it was on unless the cause says otherwise (Bluetooth dropping mid-stream). */
    private fun fail(reason: String, at: PocketStep = step) {
        if (closed) return
        Log.w(TAG, "Pocket: $reason")
        streaming = false
        close()
        emit(PocketState.Failed(reason, at))
    }

    private val readyGuard = Runnable { if (!ready) fail("Can't reach the Pocket 3. Is it on and close?") }

    private val keepalive = object : Runnable {
        override fun run() {
            if (closed) return
            if (ready) write(DjiCommands.sessionPing(keepalive = true))
            handler.postDelayed(this, KEEPALIVE_MS)
        }
    }

    // --- GATT ---------------------------------------------------------------------------------------------------

    private fun write(frame: ByteArray): Boolean = synchronized(writeLock) {
        val g = gatt ?: return false
        val ch = g.getService(SERVICE)?.getCharacteristic(CHAR_FFF5) ?: return false
        repeat(3) { // write-without-response can be refused while another write is in flight
            val ok = if (android.os.Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; ch.value = frame; g.writeCharacteristic(ch) }
            }
            if (ok) return true
            Thread.sleep(25)
        }
        false
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.requestMtu(517) // frames are up to ~255 bytes
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val was = streaming
                    streaming = false
                    if (!closed) fail(if (was) "Lost Bluetooth to the Pocket 3" else "The Pocket 3 disconnected", PocketStep.BLUETOOTH)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "Pocket: MTU $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE) ?: return fail("That isn't a Pocket 3 Fullbars can start")
            cccdQueue.clear()
            listOf(CHAR_FFF4, CHAR_FFF5).mapNotNull { svc.getCharacteristic(it) }.forEach {
                g.setCharacteristicNotification(it, true)
                cccdQueue.add(it)
            }
            nextCccd(g)
        }

        private fun nextCccd(g: BluetoothGatt) {
            val ch = cccdQueue.poll()
            if (ch == null) { armPairing(g); return }
            val d = ch.getDescriptor(CCCD) ?: return nextCccd(g)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = nextCccd(g)

        /** `01 00` to fff4 (with response) arms pairing; the session starts once that write completes. */
        private fun armPairing(g: BluetoothGatt) {
            val ch = g.getService(SERVICE)?.getCharacteristic(CHAR_FFF4) ?: return onReady()
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, byteArrayOf(1, 0), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                run { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; ch.value = byteArrayOf(1, 0); g.writeCharacteristic(ch) }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (ch.uuid == CHAR_FFF4) onReady()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = onBytes(value)

        @Deprecated("API <= 32")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) = onBytes(ch.value ?: ByteArray(0))
    }

    private fun onReady() {
        if (ready) return
        ready = true
        handler.removeCallbacks(readyGuard)
        write(DjiCommands.sessionPing(keepalive = false)) // wake
        val s = settings ?: return
        handler.postDelayed({ Thread({ runSequence(s) }, "dji-sequence").start() }, 120)
    }

    private fun onBytes(raw: ByteArray) {
        for (f in reassembler.feed(raw)) {
            if (f.isRequest) {
                // The camera drops us within ~6 s unless its requests are answered.
                val payload = if (f.cmdSet == 0x00 && f.cmdId == 0x81) DjiCommands.APP_DEVICE_INFO else f.payload
                write(f.reply(payload).encode())
                if (f.cmdSet == 0x07 && f.cmdId == 0x46) {
                    pairApproved = true
                    synchronized(lock) { lock.notifyAll() }
                }
                continue
            }
            if (f.cmdSet == 0x0D && f.cmdId == 0x02) { // battery pushes while streaming
                val p = f.payload
                val pct = (if (p.size >= 21) p[20].toInt() else if (p.size >= 13) p[12].toInt() else -1) and 0xFF
                if (pct in 0..100 && pct != battery) {
                    battery = pct
                    if (streaming) emit(PocketState.Streaming(pct))
                }
                continue
            }
            synchronized(lock) { reply = f; lock.notifyAll() }
        }
    }

    // --- the start sequence (blocking, on its own thread) ------------------------------------------------------

    private fun await(set: Int, id: Int, timeoutMs: Long): DjiMessage? {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!closed) {
                val r = reply
                // Matched on cmdSet/cmdId, not message id: several replies share 0x02/0x8E.
                if (r != null && r.isResponse && r.cmdSet == set && r.cmdId == id) { reply = null; return r }
                val left = end - System.currentTimeMillis()
                if (left <= 0) return null
                lock.wait(left)
            }
        }
        return null
    }

    /** Write-without-response frames can be lost: each step is retried, draining any stale reply first. */
    private fun request(frame: ByteArray, set: Int, id: Int, attempts: Int = 2): DjiMessage? {
        repeat(attempts) {
            synchronized(lock) { reply = null }
            write(frame)
            await(set, id, STEP_TIMEOUT_MS)?.let { return it }
            if (pairApproved && set == 0x07 && id == 0x45) return null
        }
        return null
    }

    private fun runSequence(s: Settings) {
        try {
            emit(PocketState.Pairing)
            pairApproved = false
            val pairEnd = System.currentTimeMillis() + PAIR_TIMEOUT_MS
            var paired = false
            while (!closed && System.currentTimeMillis() < pairEnd) {
                val r = request(DjiCommands.pair(identity, PIN), 0x07, 0x45)
                val status = r?.payload?.takeIf { it.size >= 2 }?.let { it[1].toInt() and 0xFF } ?: -1
                if (status == 0x01 || pairApproved) { paired = true; break }
                if (status == 0x02) emit(PocketState.ApproveOnCamera)
            }
            if (!paired) return fail("The Pocket 3 wasn't approved. Tap OK on it when it asks")

            emit(PocketState.Preparing)
            val st1 = request(DjiCommands.prepareStage1(), 0x02, 0xE1)
            if (st1 == null || st1.payload.isEmpty() || st1.payload[0].toInt() != 0) return fail("The Pocket 3 couldn't get its stream ready")
            request(DjiCommands.prepareStage2(), 0x02, 0x8E) ?: return fail("No answer from the Pocket 3 while getting ready")

            emit(PocketState.JoiningWifi(s.hotspot))
            val wifi = request(DjiCommands.connectWifi(s.ssid, s.password), 0x07, 0x47, attempts = 3)
            if (wifi == null || wifi.payload.isEmpty() || wifi.payload[0].toInt() != 0) return fail(if (s.hotspot) "The Pocket 3 couldn't join the hotspot" else "The Pocket 3 couldn't join the Wi‑Fi")

            emit(PocketState.Starting)
            val cfg = request(DjiCommands.configureStream(DjiCommands.RES_1080P, s.bitrateKbps, DjiCommands.FPS_30, s.url), 0x08, 0x78)
                ?: return fail("No answer from the Pocket 3 while setting up its stream")
            if (cfg.payload.isNotEmpty() && cfg.payload[0].toInt() != 0) return fail("The Pocket 3 refused the stream settings")
            request(DjiCommands.startStream(), 0x02, 0x8E) ?: return fail("No answer from the Pocket 3 while starting")
            streaming = true
            emit(PocketState.Streaming(battery))
        } catch (e: Exception) {
            Log.w(TAG, "Pocket: start sequence threw", e)
            fail("Something went wrong talking to the Pocket 3")
        }
    }

    private companion object {
        const val TAG = "Engine"
        const val PIN = "fbrs"
        const val KEEPALIVE_MS = 1_000L
        const val STEP_TIMEOUT_MS = 8_000L
        const val PAIR_TIMEOUT_MS = 60_000L
        const val READY_TIMEOUT_MS = 15_000L
        const val NO_PERMISSION = "Allow Nearby devices so Fullbars can start your Pocket 3"
        val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val CHAR_FFF4: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val CHAR_FFF5: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
