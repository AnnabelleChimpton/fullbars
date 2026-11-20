package app.irlsupreme.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log

/**
 * The streaming phone's side of [HotspotShare]: while it's live with its private hotspot up and accepting helpers,
 * it advertises over Bluetooth LE and serves the hotspot's name and password, sealed with the helper code, to any
 * phone that reads them. Needs Bluetooth on and the Nearby devices permission; without them helpers simply join
 * by hand (or over the same Wi-Fi).
 */
@SuppressLint("MissingPermission") // checked in start()
class HotspotBeacon(private val context: Context, private val keys: HotspotShare.Keys, credentials: HotspotShare.Credentials) {
    private val sealed = HotspotShare.seal(keys, credentials)
    private var server: BluetoothGattServer? = null
    private var advertising: AdvertiseCallback? = null

    /** False (and logs why) when Bluetooth is off or not allowed. */
    fun start(): Boolean {
        val missing = listOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT)
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) return no("no permission (${missing.joinToString { it.substringAfterLast('.') }})")
        val bm = context.getSystemService(BluetoothManager::class.java) ?: return no("no Bluetooth")
        val adapter = bm.adapter?.takeIf { it.isEnabled } ?: return no("Bluetooth is off")
        val advertiser = adapter.bluetoothLeAdvertiser ?: return no("this phone can't advertise")

        val s = bm.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (status != BluetoothGatt.GATT_SUCCESS) { Log.w(TAG, "helpers: Bluetooth service not added ($status)"); return }
                advertise(advertiser)
            }

            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
                val srv = server ?: return
                if (c.uuid != HotspotShare.CREDENTIALS || offset > sealed.size) {
                    srv.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                    return
                }
                if (offset == 0) Log.i(TAG, "helpers: a phone read the hotspot details over Bluetooth")
                srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, sealed.copyOfRange(offset, sealed.size))
            }
        }) ?: return no("couldn't open a Bluetooth server")
        server = s
        val service = BluetoothGattService(HotspotShare.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    HotspotShare.CREDENTIALS,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                ),
            )
        }
        if (!s.addService(service)) { stop(); return no("couldn't add the Bluetooth service") }
        return true
    }

    private fun advertise(advertiser: android.bluetooth.le.BluetoothLeAdvertiser) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        // 3 (flags) + 2 + 16 (UUID) + 8 (tag) = 29 of the 31 bytes a legacy advert has: every BLE phone can see it.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(HotspotShare.SERVICE), keys.tag)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { Log.i(TAG, "helpers: sharing the hotspot over Bluetooth") }
            override fun onStartFailure(errorCode: Int) { Log.w(TAG, "helpers: Bluetooth advertising failed ($errorCode)") }
        }
        try {
            advertiser.startAdvertising(settings, data, cb)
            advertising = cb
        } catch (e: RuntimeException) {
            Log.w(TAG, "helpers: Bluetooth advertising failed: $e")
        }
    }

    fun stop() {
        val bm = context.getSystemService(BluetoothManager::class.java)
        advertising?.let { cb -> runCatching { bm?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) } }
        advertising = null
        runCatching { server?.close() }
        server = null
    }

    private fun no(why: String): Boolean {
        Log.i(TAG, "helpers: not sharing the hotspot over Bluetooth: $why")
        return false
    }

    private companion object { const val TAG = "Engine" }
}
