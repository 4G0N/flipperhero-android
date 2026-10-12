package com.flipperhero.app.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pure flow-control math for the Flipper write path, kept separate from the Android GATT stack so
 * it is unit-testable on the JVM.
 *
 * The Flipper exposes credit-based flow control: the flow-control characteristic carries a
 * big-endian UInt32 of how many buffer bytes are free on the device. We never send more than the
 * device can hold, and never more than one ATT payload (negotiated MTU minus the 3-byte ATT header).
 */
object FlowControl {
    const val ATT_HEADER = 3

    /** Parses the big-endian UInt32 credit value from a flow-control notification. */
    fun parseCredits(value: ByteArray): Int {
        require(value.size >= 4) { "flow-control value must be at least 4 bytes" }
        // Matches iOS BLETransport.swift:388 — reduce((0) { ($0 << 8) | byte }).
        var result = 0L
        for (i in 0 until 4) result = (result shl 8) or (value[i].toLong() and 0xFF)
        return result.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** Bytes we may send right now: bounded by what's wanted, what the device can hold, and one ATT payload. */
    fun chunkSize(want: Int, free: Int, mtu: Int): Int =
        minOf(want, free, (mtu - ATT_HEADER).coerceAtLeast(1))
}

/**
 * Coroutine credit gate: `reserve` hands out a chunk size and blocks when the device reports no free
 * space, resuming when a fresh flow-control value arrives. Pure (coroutines only), so it is tested
 * on the JVM without a device. Mirrors the reserve/spaceWaiters logic in iOS BLETransport.swift.
 */
class CreditGate {
    private val mutex = Mutex()
    private var free = 0
    private var closed = false
    private var closeReason: Throwable? = null
    private var waiters = mutableListOf<CompletableDeferred<Unit>>()

    /**
     * Reserves up to [want] bytes to send next, given the negotiated [mtu]. Suspends while no credits
     * are available. Throws if the gate is closed (link lost).
     */
    suspend fun reserve(want: Int, mtu: Int): Int {
        while (true) {
            val waiter: CompletableDeferred<Unit>
            mutex.withLock {
                if (closed) throw closeReason ?: IllegalStateException("flow control closed")
                if (free > 0) {
                    val n = FlowControl.chunkSize(want, free, mtu)
                    free -= n
                    return n
                }
                waiter = CompletableDeferred()
                waiters.add(waiter)
            }
            waiter.await() // resumed by supply() or close()
        }
    }

    /** Called when a flow-control notification arrives: sets the free-byte count and wakes waiters. */
    suspend fun supply(credits: Int) {
        val toWake: List<CompletableDeferred<Unit>>
        mutex.withLock {
            free = credits
            toWake = waiters
            waiters = mutableListOf()
        }
        toWake.forEach { it.complete(Unit) }
    }

    /** Closes the gate (link lost); pending and future reserves throw [reason]. */
    suspend fun close(reason: Throwable) {
        val toWake: List<CompletableDeferred<Unit>>
        mutex.withLock {
            closed = true
            closeReason = reason
            toWake = waiters
            waiters = mutableListOf()
        }
        toWake.forEach { it.complete(Unit) }
    }
}
