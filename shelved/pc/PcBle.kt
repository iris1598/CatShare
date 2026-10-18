package moe.reimu.catshare.pc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BLE advertising/scanning for the Xiaomi legacy MiDrop "PC mode".
 *
 * Both directions only use non-connectable legacy advertisements carrying
 * manufacturer specific data with company id 0x038F (Xiaomi Inc.).
 */
object PcBle {
    private const val TAG = "CatSharePc"
    const val MANUFACTURER_ID = 0x038F

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Advertises one packet for [durationMs]. Returns true if the advertiser accepted it.
     * The [packet] is the complete payload that goes inside the manufacturer data field.
     */
    @SuppressLint("MissingPermission")
    fun advertise(
        context: Context,
        packet: ByteArray,
        durationMs: Int = 10_000,
        onStarted: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val btAdapter = adapter(context) ?: run {
            Log.e(TAG, "no bluetooth adapter")
            return false
        }
        if (!btAdapter.isEnabled) {
            Log.e(TAG, "bluetooth disabled")
            return false
        }
        val advertiser: BluetoothLeAdvertiser = btAdapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "no BLE advertiser")
            return false
        }
        val data = try {
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(MANUFACTURER_ID, packet)
                .build()
        } catch (e: Throwable) {
            Log.e(TAG, "failed to build advertise data (len=${packet.size})", e)
            return false
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(durationMs)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "advertising ok (len=${packet.size}, duration=${durationMs})")
                onStarted?.invoke(true)
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "advertising failed: $errorCode (len=${packet.size})")
                onStarted?.invoke(false)
            }
        }
        return try {
            advertiser.startAdvertising(settings, data, callback)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "startAdvertising threw", e)
            false
        }
    }

    /** Continuously advertises [packet] until [stopAdvertising] is called. */
    @SuppressLint("MissingPermission")
    fun advertiseLoop(
        context: Context,
        tag: String,
        packet: ByteArray,
        intervalMs: Long = 11_000,
        durationMs: Int = 10_000,
        onStarted: ((Boolean) -> Unit)? = null,
    ): Thread {
        val thread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                advertise(context, packet, durationMs, onStarted)
                try {
                    Thread.sleep(intervalMs)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        thread.name = "pc-adv-$tag"
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * Rotates through [packets], advertising each one for [durationMs].
     * Legacy BLE advertising can only run one advertising instance at a time,
     * so the phone must cycle between "ability", "transfer" and "server ready".
     */
    fun advertiseSequence(
        context: Context,
        tag: String,
        packets: List<ByteArray>,
        durationMs: Int = 1500,
    ): Thread {
        val thread = Thread {
            var index = 0
            while (!Thread.currentThread().isInterrupted) {
                val packet = packets[index % packets.size]
                index++
                advertise(context, packet, durationMs)
                try {
                    Thread.sleep(durationMs.toLong() + 200)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        thread.name = "pc-adv-$tag"
        thread.isDaemon = true
        thread.start()
        return thread
    }

    class Scanner(
        private val context: Context,
        private val onPacket: (deviceAddress: String, rssi: Int, data: ByteArray) -> Unit,
    ) {
        private var scanner: BluetoothLeScanner? = null
        private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                // Diagnostics: log every distinct advertisement (name / manufacturer / service data).
                val mfIds = StringBuilder()
                record.manufacturerSpecificData?.let { mf ->
                    for (i in 0 until mf.size()) {
                        mfIds.append("%04x,".format(mf.keyAt(i)))
                    }
                }
                val key = result.device.address + ":" + (record.deviceName ?: "") + ":" +
                    mfIds.toString() + ":" + record.serviceUuids?.joinToString(",")
                if (seen.add(key)) {
                    val names = record.deviceName ?: "(noname)"
                    val mf = record.manufacturerSpecificData
                    var mfSummary = ""
                    if (mf != null) {
                        for (i in 0 until mf.size()) {
                            val id = mf.keyAt(i)
                            val bytes = mf.valueAt(i)
                            mfSummary += " 0x%04x=%s".format(id, bytes.joinToString("") { b -> "%02x".format(b) })
                        }
                    }
                    Log.i(TAG, "BLE adv from ${result.device.address} rssi=${result.rssi} name=$names mf=[$mfSummary]")
                }
                val data = record.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
                onPacket(result.device.address, result.rssi, data)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
            }
        }

        @SuppressLint("MissingPermission")
        fun start() {
            val btAdapter = adapter(context) ?: return
            if (!btAdapter.isEnabled) return
            scanner = btAdapter.bluetoothLeScanner
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            @Suppress("DEPRECATION")
            scanner?.startScan(null, settings, callback)
            Log.i(TAG, "BLE scan started")
        }

        @SuppressLint("MissingPermission")
        fun stop() {
            try {
                scanner?.stopScan(callback)
            } catch (e: Throwable) {
                Log.e(TAG, "stopScan failed", e)
            }
            scanner = null
            Log.i(TAG, "BLE scan stopped")
        }
    }
}
