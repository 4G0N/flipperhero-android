package com.flipperhero.app

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flipperhero.app.ble.AndroidBleTransport
import com.flipperhero.app.ble.FlipperScanner
import com.flipperhero.flipper.FlipperRpcClient
import com.flipperhero.flipper.deviceInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ONE instrumented BLE test (an emulator can't do BLE). Run at the Step 2(a) hardware
 * checkpoint, with a real Momentum Flipper present and the operator confirming the pairing PIN —
 * bonding is interactive. It is NOT part of the JVM suite and does not run in CI.
 *
 * Covers: connect -> bond -> MTU -> device-info round-trip over the real GATT stack, which also
 * exercises real framing/flow-control. It self-skips (Assume) when no Flipper is in range so an
 * accidental run on a device without one does not hard-fail.
 *
 * Proto WIRE-PARITY (byte-identical decode/re-encode of a captured exchange) is checked separately
 * at the bench from captured frames; see the report. Functional parity is implied here: real
 * firmware bytes must decode into a populated device-info map.
 */
@RunWith(AndroidJUnit4::class)
class BleSmokeTest {

    @Test
    fun connectBondMtuAndReadDeviceInfo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        assumeTrue("Bluetooth must be on", manager.adapter?.isEnabled == true)

        // Prefer an already-bonded Flipper; otherwise scan briefly.
        val bonded = manager.adapter.bondedDevices.firstOrNull { it.name?.startsWith("Flipper") == true }
        val address = bonded?.address ?: run {
            val scanner = FlipperScanner(context)
            withTimeoutOrNull(15_000) { scanner.scan().first { it.isNotEmpty() } }?.firstOrNull()?.address
        }
        assumeTrue("No Flipper found in range; skipping hardware smoke test", address != null)

        val device = manager.adapter.getRemoteDevice(address)
        val transport = AndroidBleTransport(context, device).connect(timeoutMs = 60_000)
        val client = FlipperRpcClient(transport)
        client.start()
        try {
            val info = client.deviceInfo()
            assertTrue("device info should contain a hardware name", info.containsKey("hardware_name"))
            assertTrue("device info should report firmware", info.keys.any { it.startsWith("firmware_") })
        } finally {
            client.stop()
        }
    }
}
