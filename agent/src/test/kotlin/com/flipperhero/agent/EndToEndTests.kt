package com.flipperhero.agent

import com.flipperhero.flipper.SimulatedFlipper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The key no-hardware test: run the whole :agent -> :flipper stack (real FlipperRpcClient, real
 * framing and RPC) against the Kotlin SimulatedFlipper. Invariants are asserted as behavior.
 */
class EndToEndTests {

    private fun controlling(sim: SimulatedFlipper) = sim.client().asControlling()

    @Test
    fun transmitRequiresApprovalAndIsRefusedWhenDenied(): Unit = runBlocking {
        val sim = SimulatedFlipper(mapOf("/ext/subghz/gate.sub" to "Filetype: Flipper SubGhz Key File"))
        val gate = ScriptedGate(false) // user does not press-and-hold
        val ex = ToolExecutor(controlling(sim), gate, InMemoryAuditLog())

        val refused = ex.execute(ToolCall("1", "transmit_subghz", """{"path":"/ext/subghz/gate.sub"}"""))
        assertEquals(ToolResult.Kind.DENIED, refused.kind)
        assertEquals(1, gate.requests.size)
        assertEquals(RiskLevel.HIGH, gate.requests[0].risk)
        // Nothing was transmitted through the real RPC stack.
        assertFalse(sim.log.any { it.startsWith("appPress") })
    }

    @Test
    fun approvedTransmitDrivesTheRealRpcSequence(): Unit = runBlocking {
        val sim = SimulatedFlipper(mapOf("/ext/subghz/gate.sub" to "Filetype: Flipper SubGhz Key File"))
        val ex = ToolExecutor(controlling(sim), ScriptedGate(true), InMemoryAuditLog())
        val result = ex.execute(ToolCall("1", "transmit_subghz", """{"path":"/ext/subghz/gate.sub"}"""))
        assertFalse(result.isError, result.content)
        // The full app-control dance went over real framing to the simulator.
        assertEquals(
            listOf("stat /ext/subghz/gate.sub", "appStart Sub-GHz RPC", "appLoad /ext/subghz/gate.sub", "appPress ", "appRelease", "appExit"),
            sim.log,
        )
    }

    @Test
    fun readingTaintedContentRePromptsNextChangeDespitePriorApproval(): Unit = runBlocking {
        val sim = SimulatedFlipper(mapOf("/ext/notes.txt" to "hello"))
        val gate = ScriptedGate(true)
        val ex = ToolExecutor(controlling(sim), gate, InMemoryAuditLog(), settings = { ApprovalSettings(autoApproveMedium = true) })

        // A clean medium action is auto-approved (no prompt).
        ex.execute(ToolCall("1", "create_directory", """{"path":"/ext/one"}"""))
        assertEquals(0, gate.requests.size)

        // Read device content -> taints the turn.
        ex.execute(ToolCall("2", "read_file", """{"path":"/ext/notes.txt"}"""))
        assertTrue(ex.isTainted)

        // The next state change must re-prompt despite auto-approve and a prior approved change.
        ex.execute(ToolCall("3", "create_directory", """{"path":"/ext/two"}"""))
        assertEquals(1, gate.requests.size)
        assertTrue(gate.requests[0].afterUntrustedContent)
    }

    @Test
    fun rpcRawRefusedUnlessArmedThroughTheRealStack(): Unit = runBlocking {
        val request = """{"request":"{\"content\":{\"systemPingRequest\":{}}}"}"""

        val sim1 = SimulatedFlipper()
        val ex1 = ToolExecutor(controlling(sim1), ScriptedGate(true), InMemoryAuditLog())
        assertTrue(ex1.execute(ToolCall("1", "rpc_raw", request)).isError, "refused when engagement raw_rpc is not armed")

        val armed = EngagementState(active = true, profile = EngagementProfile(rawRPC = true), startedAt = System.currentTimeMillis())
        val sim2 = SimulatedFlipper()
        val ex2 = ToolExecutor(controlling(sim2), ScriptedGate(true), InMemoryAuditLog(), settings = { ApprovalSettings(engagement = armed) })
        val ok = ex2.execute(ToolCall("1", "rpc_raw", request))
        assertFalse(ok.isError, ok.content)
        // Real FlipperRpcClient.rawRpc parsed the JSON, sent a ping over framing, and fenced the reply.
        assertTrue(ok.content.contains("<<<FLIPPER_DATA"))
    }
}
