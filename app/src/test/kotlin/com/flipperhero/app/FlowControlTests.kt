package com.flipperhero.app

import com.flipperhero.app.ble.CreditGate
import com.flipperhero.app.ble.FlowControl
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlowControlTests {

    @Test
    fun parsesBigEndianUInt32Credits() {
        assertEquals(0, FlowControl.parseCredits(byteArrayOf(0, 0, 0, 0)))
        assertEquals(1, FlowControl.parseCredits(byteArrayOf(0, 0, 0, 1)))
        assertEquals(0x0102_0304, FlowControl.parseCredits(byteArrayOf(1, 2, 3, 4)))
        // High bytes set (would be negative as a signed Int if parsed wrong).
        assertEquals(0x0000_FF00, FlowControl.parseCredits(byteArrayOf(0, 0, 0xFF.toByte(), 0)))
        // Extra trailing bytes are ignored.
        assertEquals(256, FlowControl.parseCredits(byteArrayOf(0, 0, 1, 0, 9, 9)))
    }

    @Test
    fun chunkSizeIsBoundedByWantFreeAndMtuMinus3() {
        assertEquals(20, FlowControl.chunkSize(want = 100, free = 50, mtu = 23)) // mtu-3
        assertEquals(50, FlowControl.chunkSize(want = 100, free = 50, mtu = 512)) // free
        assertEquals(10, FlowControl.chunkSize(want = 10, free = 50, mtu = 512)) // want
        assertTrue(FlowControl.chunkSize(want = 100, free = 50, mtu = 1) >= 1) // never zero/negative
    }

    @Test
    fun reserveBlocksUntilCreditsArriveThenHonoursMtuMinus3(): Unit = runBlocking {
        val gate = CreditGate()
        val granted = async { gate.reserve(want = 1000, mtu = 23) }
        delay(50)
        assertTrue(granted.isActive, "reserve must block while the device reports no free space")
        gate.supply(500)
        assertEquals(20, granted.await(), "chunk is capped at MTU-3 even with ample credits")
    }

    @Test
    fun reserveDrainsCreditsAcrossCalls(): Unit = runBlocking {
        val gate = CreditGate()
        gate.supply(45)
        // Large MTU so credits are the binding constraint.
        assertEquals(45, gate.reserve(want = 45, mtu = 512))
        // Credits now exhausted; next reserve blocks until more arrive.
        val next = async { gate.reserve(want = 10, mtu = 512) }
        delay(50)
        assertTrue(next.isActive)
        gate.supply(8)
        assertEquals(8, next.await())
    }

    @Test
    fun closePropagatesToReservers(): Unit = runBlocking {
        val gate = CreditGate()
        val blocked = async { runCatching { gate.reserve(want = 10, mtu = 512) } }
        delay(50)
        gate.close(IllegalStateException("link lost"))
        assertTrue(blocked.await().isFailure, "a closed gate must fail pending reserves, not hang")
    }
}
