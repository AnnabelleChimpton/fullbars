package app.irlsupreme.dji

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

/** A DJI camera seen nearby over Bluetooth. */
data class FoundCamera(val address: String, val name: String, val model: Int?, val rssi: Int)

/** Scans for DJI cameras (by their Bluetooth manufacturer data) for a few seconds. Main thread only. */
@SuppressLint("MissingPermission") // BLUETOOTH_SCAN is requested first
class DjiScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private var callback: ScanCallback? = null

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun scan(seconds: Int, onUpdate: (List<FoundCamera>) -> Unit, onDone: (String?) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return onDone("Turn on Bluetooth to find your Pocket 3")
        val found = LinkedHashMap<String, FoundCamera>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val data = r.scanRecord?.manufacturerSpecificData ?: return
                for (i in 0 until data.size()) {
                    if (data.keyAt(i) !in DjiAdvert.COMPANY_IDS) continue
                    val model = DjiAdvert.modelId(data.valueAt(i))
                    val name = r.scanRecord?.deviceName ?: DjiAdvert.name(model)
                    found[r.device.address] = FoundCamera(r.device.address, name, model, r.rssi)
                    // Only while this scan is still the one running: a result queued as the user picked a camera
                    // used to bring the list back after it had closed.
                    main.post { if (callback === this) onUpdate(found.values.sortedByDescending { it.rssi }) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post { if (callback === this) { stop(); onDone("Couldn't look for the Pocket 3 ($errorCode). Try again.") } }
            }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: SecurityException) {
            return onDone("Allow Nearby devices so Fullbars can find your Pocket 3")
        }
        callback = cb
        main.postDelayed({ if (callback === cb) { stop(); onDone(null) } }, seconds * 1000L)
    }

    /**
     * Ends the scan without calling onDone: picking a camera stops it early, and the scan's own timeout used to fire 8 s
     * later anyway, finding the (now cleared) list empty and showing "No Pocket 3 found" under the one just chosen.
     */
    fun stop() {
        callback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        callback = null
        main.removeCallbacksAndMessages(null)
    }

    /**
     * The camera at [address], or null while Bluetooth is off (Android hands out a device even then, and connecting to
     * it just fails, which used to read as "is the camera on?") or the saved address is malformed.
     */
    fun device(address: String) = adapter?.takeIf { it.isEnabled }?.let { a -> runCatching { a.getRemoteDevice(address) }.getOrNull() }
}
