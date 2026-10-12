package com.flipperhero.app.ble

/** A Flipper seen while scanning. */
data class DiscoveredFlipper(val address: String, val name: String, val rssi: Int)

/** Adapter/permission state surfaced to the UI as a normal, messaged condition. */
enum class BleStatus { UNKNOWN, POWERED_OFF, UNAUTHORIZED, UNSUPPORTED, READY }

/** BLE/connection failures, mapped to user-facing messages (localized in the UI layer). */
sealed class BleError(message: String) : Exception(message) {
    data class BluetoothUnavailable(val status: BleStatus) : BleError("Bluetooth is not available ($status)")
    object PermissionDenied : BleError("Bluetooth permission was denied") { private fun readResolve(): Any = PermissionDenied }
    object UnknownDevice : BleError("Unknown Flipper, scan again") { private fun readResolve(): Any = UnknownDevice }
    object Timeout : BleError("Connecting timed out") { private fun readResolve(): Any = Timeout }
    object PairingFailed : BleError("Pairing failed. Confirm the PIN shown on the Flipper.") { private fun readResolve(): Any = PairingFailed }
    object ServiceMissing : BleError("This device does not expose the Flipper serial service") { private fun readResolve(): Any = ServiceMissing }
    object Disconnected : BleError("Flipper disconnected") { private fun readResolve(): Any = Disconnected }
    data class Underlying(val detail: String) : BleError(detail)
}
