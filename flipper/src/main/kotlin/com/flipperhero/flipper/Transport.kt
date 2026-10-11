package com.flipperhero.flipper

import kotlinx.coroutines.flow.Flow

/**
 * Byte pipe to a Flipper. Implementations own chunking and flow control.
 *
 * This is the ports-and-adapters seam: `:flipper` is pure JVM and defines this
 * interface; the Android GATT implementation (`AndroidBleTransport`) lives in `:app`.
 * Mirrors Swift `FlipperTransport`.
 */
interface Transport {
    /** Raw bytes as they arrive (any chunking). */
    val incoming: Flow<ByteArray>

    suspend fun send(data: ByteArray)

    suspend fun close()
}

/** Optional diagnostics hook. The app sets [sink]; nothing is logged otherwise. Mirrors Swift `FlipperLog`. */
object FlipperLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun log(message: () -> String) {
        sink?.invoke(message())
    }
}
