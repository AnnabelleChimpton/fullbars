package app.irlsupreme.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log

/**
 * The helper phone's side of [HotspotShare]: scans for its streaming phone's Bluetooth advert (filtered by the tag,
 * so it also works with the screen off), reads the sealed hotspot details over GATT and opens them with the helper
 * code. Reads only while [wanted] says so (not once the helper is already connected), at most every [RETRY_MS].
 *
 * A scan Android refuses is retried after 5, 15, then every 60 s; with Bluetooth off it waits for the owner to call
 * [start] again when it comes back on ([onAdapterOff] in between). [usable] is true only while it is really scanning,
 * so the helper never says "waiting" when nothing is listening.
 */
@SuppressLint("MissingPermission") // checked in start()
class HotspotFinder(
    private val context: Context,
    private val keys: HotspotShare.Keys,
    private val wanted: () -> Boolean,
    private val onFound: (HotspotShare.Credentials) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val bm = context.getSystemService(BluetoothManager::class.java)
    private var scanning: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var lastReadMs = 0L

    /** Whether it is listening for the streaming phone right now (Bluetooth on, allowed, and the scan running). */
    val usable: Boolean
        get() = scanning != null && permitted() && bm?.adapter?.isEnabled == true

    private fun permitted() = listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private var failures = 0
    private val retry = Runnable { start() }

    fun start() {
        if (scanning != null) return
        main.removeCallbacks(retry)
        if (!permitted()) { Log.i(TAG, "helper: no Bluetooth permission; join the hotspot by hand"); return }
        val scanner = bm?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: run { Log.i(TAG, "helper: Bluetooth is off; join the hotspot by hand, or turn it on"); return }
        val filter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(HotspotShare.SERVICE), keys.tag, ByteArray(HotspotShare.TAG_BYTES) { -1 })
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { main.post { found(result.device) } }
            override fun onScanFailed(errorCode: Int) {
                if (errorCode == SCAN_FAILED_ALREADY_STARTED) return // this very scan is running: nothing failed
                main.post { if (scanning === this) { scanning = null; retryLater("scan failed ($errorCode)") } }
            }
        }
        try {
            scanner.startScan(listOf(filter), settings, cb)
            scanning = cb
            Log.i(TAG, "helper: looking for the streaming phone's hotspot over Bluetooth")
        } catch (e: RuntimeException) {
            retryLater("can't scan: $e")
        }
    }

    private fun retryLater(why: String) {
        val delay = retryDelayMs(failures++)
        Log.w(TAG, "helper: Bluetooth $why; trying again in ${delay / 1000} s")
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay)
    }

    /** Bluetooth went off: the scan is gone with it. Call [start] once it's back on. */
    fun onAdapterOff() {
        main.removeCallbacks(retry)
        main.removeCallbacks(timeout)
        scanning = null // Android dropped it; stopScan would only complain
        gatt?.let { runCatching { it.close() } }
        gatt = null
        failures = 0
    }

    private fun found(device: BluetoothDevice) {
        failures = 0 // the scan works
        val now = System.currentTimeMillis()
        if (scanning == null || gatt != null || !wanted() || now - lastReadMs < RETRY_MS) return
        lastReadMs = now
        Log.i(TAG, "helper: found the streaming phone over Bluetooth; reading the hotspot details")
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // A bigger MTU makes it one round trip; the read works either way (long reads).
                    if (!g.requestMtu(185)) g.discoverServices()
                } else {
                    main.post { finish(g, if (newState == BluetoothProfile.STATE_DISCONNECTED) "disconnected ($status)" else null) }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val c = g.getService(HotspotShare.SERVICE)?.getCharacteristic(HotspotShare.CREDENTIALS)
                if (c == null || !g.readCharacteristic(c)) main.post { finish(g, "no hotspot details offered") }
            }

            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
                read(g, value, status)

            @Deprecated("Android 12")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < 33) read(g, c.value ?: ByteArray(0), status)
            }
        }, BluetoothDevice.TRANSPORT_LE)
        main.postDelayed(timeout, READ_TIMEOUT_MS)
    }

    private fun read(g: BluetoothGatt, value: ByteArray, status: Int) {
        val creds = if (status == BluetoothGatt.GATT_SUCCESS) HotspotShare.open(keys, value) else null
        main.post {
            if (creds == null) { finish(g, "couldn't open the hotspot details ($status)"); return@post }
            finish(g, null)
            Log.i(TAG, "helper: got the streaming phone's hotspot \"${creds.ssid}\"")
            onFound(creds)
        }
    }

    private val timeout = Runnable { gatt?.let { finish(it, "timed out") } }

    private fun finish(g: BluetoothGatt, problem: String?) {
        if (gatt !== g) return
        problem?.let { Log.i(TAG, "helper: Bluetooth read failed: $it") }
        main.removeCallbacks(timeout)
        runCatching { g.disconnect() }
        runCatching { g.close() }
        gatt = null
    }

    /** Read again on the next sighting (e.g. the hotspot went away and a new stream started). */
    fun readSoon() { lastReadMs = 0 }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        scanning?.let { cb -> runCatching { bm?.adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanning = null
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
    }

    internal companion object {
        const val TAG = "Engine"
        const val RETRY_MS = 15_000L
        const val READ_TIMEOUT_MS = 15_000L

        /** After a refused scan: 5 s, 15 s, then every minute. */
        fun retryDelayMs(failures: Int): Long = when (failures) {
            0 -> 5_000L
            1 -> 15_000L
            else -> 60_000L
        }
    }
}
