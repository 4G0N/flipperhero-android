package com.flipperhero.flipper

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Exercises the JsonFormat-based raw-RPC bridge against the simulator (no iOS analog test). */
class RawRpcTests {

    @Test
    fun rawRpcParsesJsonSendsAndPrintsResponse(): Unit = runBlocking {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        val response = client.rawRpc("""{"content":{"systemPingRequest":{}}}""")
        // The simulator answers with an OK empty reply, re-encoded to JSON.
        assertTrue(response.contains("empty"), response)
    }

    @Test
    fun rawRpcRejectsRequestWithoutContent(): Unit = runBlocking {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        assertThrowsSuspend<FlipperException.Rpc> { client.rawRpc("""{}""") }
    }
}
