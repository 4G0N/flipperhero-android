package com.flipperhero.app.ble

import android.os.ParcelUuid
import java.util.UUID

/**
 * Flipper serial-service GATT identifiers, from the iOS app (BLETransport.swift:44-50) and the
 * Momentum firmware.
 */
object FlipperGatt {
    val SERVICE: UUID = UUID.fromString("8FE5B3D5-2E7F-4A98-2A48-7ACC60FE0000")

    /** Notify (Flipper -> phone). Subscribing to this authenticated characteristic triggers bonding. */
    val SERIAL_READ: UUID = UUID.fromString("19ED82AE-ED21-4C9D-4145-228E61FE0000")

    /** Write (phone -> Flipper), written with response. */
    val SERIAL_WRITE: UUID = UUID.fromString("19ED82AE-ED21-4C9D-4145-228E62FE0000")

    /** Notify + read. Big-endian UInt32 = free buffer bytes on the Flipper (credit-based flow control). */
    val FLOW_CONTROL: UUID = UUID.fromString("19ED82AE-ED21-4C9D-4145-228E63FE0000")

    /**
     * HARDWARE HAZARD (iOS AGENTS.md:162): NEVER subscribe to or write this "RPC status"
     * characteristic. Writing a 0 here makes the firmware restart its Bluetooth profile and drops
     * the link. It is listed only so the code is explicit about leaving it alone.
     */
    val FORBIDDEN_STATUS: UUID = UUID.fromString("19ED82AE-ED21-4C9D-4145-228E64FE0000")

    /** Client Characteristic Configuration Descriptor, for enabling notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** 16-bit service UUIDs the Flipper advertises; used as scan filters. */
    val ADVERTISED: List<ParcelUuid> = listOf("3080", "3081", "3082", "3083").map {
        ParcelUuid(UUID.fromString("0000$it-0000-1000-8000-00805F9B34FB"))
    }

    /** Characteristics we are allowed to touch — the status characteristic is deliberately excluded. */
    val ALLOWED = setOf(SERIAL_READ, SERIAL_WRITE, FLOW_CONTROL)
}
