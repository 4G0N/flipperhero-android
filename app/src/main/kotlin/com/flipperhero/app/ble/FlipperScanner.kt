package com.flipperhero.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Scans for Flippers. Emits the current sorted set of discoveries while collected. The caller must
 * hold BLUETOOTH_SCAN (API 31+) / location (pre-31); permission denial surfaces as [BleError].
 */
class FlipperScanner(private val context: Context) {

    val status: BleStatus
        get() {
            val adapter = adapter() ?: return BleStatus.UNSUPPORTED
            return if (adapter.isEnabled) BleStatus.READY else BleStatus.POWERED_OFF
        }

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<DiscoveredFlipper>> = callbackFlow {
        val scanner = adapter()?.bluetoothLeScanner ?: run {
            close(BleError.BluetoothUnavailable(status)); return@callbackFlow
        }
        val found = LinkedHashMap<String, DiscoveredFlipper>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: result.scanRecord?.deviceName ?: ""
                val isFlipper = name.startsWith("Flipper") ||
                    (result.scanRecord?.serviceUuids?.any { it in FlipperGatt.ADVERTISED } == true)
                if (!isFlipper) return
                found[device.address] = DiscoveredFlipper(device.address, name.ifEmpty { "Flipper" }, result.rssi)
                trySend(found.values.sortedByDescending { it.rssi })
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleError.Underlying("scan failed ($errorCode)"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No service filter: some Flippers (e.g. already connected to the OS) advertise differently;
        // we match by name/known UUIDs in the callback instead. An empty filter list scans all.
        try {
            scanner.startScan(emptyList<ScanFilter>(), settings, callback)
        } catch (e: SecurityException) {
            close(BleError.PermissionDenied); return@callbackFlow
        }
        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }
}
