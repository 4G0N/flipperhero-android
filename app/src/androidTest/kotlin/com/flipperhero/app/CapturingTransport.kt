package com.flipperhero.app

import com.flipperhero.flipper.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.io.ByteArrayOutputStream

/**
 * Test-only [Transport] wrapper that records the exact RPC frame bytes the client receives, so the
 * Step 2(a) wire-parity check can assert the pinned `.proto` decodes real firmware bytes AND
 * re-encodes them byte-identically. `incoming` here carries only serial-read RPC frames (the real
 * transport routes flow-control to credits, not to this stream), so the capture is clean.
 */
class CapturingTransport(private val delegate: Transport) : Transport {
    private val lock = Any()
    private val recorded = ByteArrayOutputStream()

    /** Everything the client has received so far, concatenated. */
    val capturedIncoming: ByteArray get() = synchronized(lock) { recorded.toByteArray() }

    override val incoming: Flow<ByteArray> =
        delegate.incoming.onEach { chunk -> synchronized(lock) { recorded.write(chunk) } }

    override suspend fun send(data: ByteArray) = delegate.send(data)
    override suspend fun close() = delegate.close()
}
