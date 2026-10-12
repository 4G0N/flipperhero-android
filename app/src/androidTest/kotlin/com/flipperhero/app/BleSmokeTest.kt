package com.flipperhero.app

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flipperdevices.protobuf.Main
import com.flipperhero.app.ble.AndroidBleTransport
import com.flipperhero.app.ble.FlipperScanner
import com.flipperhero.flipper.FlipperRpcClient
import com.flipperhero.flipper.Varint
import com.flipperhero.flipper.deviceInfo
import com.flipperhero.flipper.list
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ONE instrumented BLE test (an emulator can't do BLE). Run at the Step 2(a) hardware
 * checkpoint, with a real Momentum Flipper present and the operator confirming the pairing PIN —
 * bonding is interactive. NOT part of the JVM suite; does not run in CI.
 *
 * Covers both hardware acceptance criteria in one run:
 *  1. connect -> bond -> MTU -> device-info round-trip + list the SD card (/ext), over the real
 *     GATT stack (exercises real framing and credit flow control).
 *  2. proto WIRE-PARITY: the exact RPC frame bytes the firmware sent are captured, decoded with the
 *     pinned Momentum `.proto`, and re-encoded byte-identically — catching a wrong pinned commit.
 *
 * Self-skips (Assume) when no Flipper is in range so an accidental run doesn't hard-fail.
 */
@RunWith(AndroidJUnit4::class)
class BleSmokeTest {

    @Test
    fun connectBondMtuDeviceInfoListAndWireParity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        assumeTrue("Bluetooth must be on", manager.adapter?.isEnabled == true)

        val bonded = manager.adapter.bondedDevices.firstOrNull { it.name?.startsWith("Flipper") == true }
        val address = bonded?.address ?: run {
            val scanner = FlipperScanner(context)
            withTimeoutOrNull(15_000) { scanner.scan().first { it.isNotEmpty() } }?.firstOrNull()?.address
        }
        assumeTrue("No Flipper found in range; skipping hardware smoke test", address != null)

        val device = manager.adapter.getRemoteDevice(address)
        val transport = AndroidBleTransport(context, device).connect(timeoutMs = 60_000)
        val capturing = CapturingTransport(transport)
        val client = FlipperRpcClient(capturing)
        client.start()
        try {
            // (1) device-info round-trip
            val info = client.deviceInfo()
            assertTrue("device info should contain a hardware name", info.containsKey("hardware_name"))
            assertTrue("device info should report firmware", info.keys.any { it.startsWith("firmware_") })

            // (1) list the SD card
            val root = client.list("/ext")
            assertTrue("/ext should list entries", root.isNotEmpty())

            // (2) wire-parity over the captured real frames
            assertWireParity(capturing.capturedIncoming)
        } finally {
            client.stop()
        }
    }

    /** Walks captured length-delimited frames; each message must re-encode to the identical bytes. */
    private fun assertWireParity(raw: ByteArray) {
        var offset = 0
        var frames = 0
        var sawDeviceInfo = false
        while (true) {
            val decoded = Varint.decode(raw, offset) ?: break
            val (length, prefix) = decoded
            val total = prefix + length.toInt()
            if (offset + total > raw.size) break
            val body = raw.copyOfRange(offset + prefix, offset + total)
            val message = Main.parseFrom(body)
            assertArrayEquals(
                "pinned .proto must decode real firmware bytes and re-encode byte-identically",
                body, message.toByteArray(),
            )
            if (message.contentCase == Main.ContentCase.SYSTEM_DEVICE_INFO_RESPONSE) sawDeviceInfo = true
            frames++
            offset += total
        }
        assertTrue("expected at least one framed RPC message captured", frames > 0)
        assertTrue("expected a device-info response among captured frames", sawDeviceInfo)
    }
}
