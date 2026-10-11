package com.flipperhero.agent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The SAFETY INVARIANTS from the port spec, written as assertions. Everything fails closed.
 * These are the load-bearing guarantees of the app; all must hold.
 */
class SafetyInvariantsTests {

    // 1. Unparseable action risk -> never LOW (here: BLOCKED), and a malformed call never executes.
    @Test
    fun invariant1_unparseableIsNeverLow(): Unit = runBlocking {
        // A path that cannot be normalized is assessed BLOCKED, never LOW/MEDIUM.
        for (badPath in listOf("relative/x", "/etc/passwd", "/ext/\u0000bad", "")) {
            val level = RiskAssessor.assess(ToolInvocation.WriteFile(badPath, "x")).level
            assertNotEquals(RiskLevel.LOW, level, badPath)
            assertEquals(RiskLevel.BLOCKED, level, badPath)
        }
        // A malformed tool call is rejected before any risk-skipping path and never touches the device.
        val flipper = FakeFlipper()
        val ex = ToolExecutor(flipper, ScriptedGate(true), InMemoryAuditLog())
        val result = ex.execute(ToolCall("1", "delete", "{oops"))
        assertTrue(result.isError)
        assertTrue(flipper.calls.isEmpty())
    }

    // 2. A path that doesn't canonicalize cleanly -> BLOCKED.
    @Test
    fun invariant2_dirtyPathsAreBlocked() {
        val dirty = listOf(
            "/ext/../int/secret",   // .. traversal
            "/int/anything",        // internal flash
            "/ext/x\nlisting",      // control char
            "notabsolute",          // relative
            "/nope/outside/roots",  // outside allowed roots
        )
        for (p in dirty) {
            assertEquals(RiskLevel.BLOCKED, RiskAssessor.assess(ToolInvocation.ReadFile(p)).level, p)
        }
    }

    // 3. rpc_raw refused unless engagement-with-raw armed, enforced AT THE EXECUTOR.
    @Test
    fun invariant3_rpcRawGatedAtExecutor(): Unit = runBlocking {
        val request = """{"request":"{\"content\":{\"systemPingRequest\":{}}}"}"""
        // Not armed — even with YOLO + auto-approve on (which only bypass approval, not capability gates).
        val flipper = FakeFlipper()
        val ex = ToolExecutor(flipper, ScriptedGate(true), InMemoryAuditLog(),
            settings = { ApprovalSettings(yolo = true, autoApproveMedium = true, yoloAsksAfterUntrusted = false) })
        val refused = ex.execute(ToolCall("1", "rpc_raw", request))
        assertTrue(refused.isError, "rpc_raw must be refused when engagement raw_rpc is not armed")
        assertTrue(flipper.calls.isEmpty(), "a locked capability must not reach the device")

        // Armed with raw_rpc — now it runs.
        val armed = EngagementState(active = true, profile = EngagementProfile(rawRPC = true), startedAt = System.currentTimeMillis())
        val ex2 = ToolExecutor(FakeFlipper(), ScriptedGate(true), InMemoryAuditLog(), settings = { ApprovalSettings(engagement = armed) })
        assertFalse(ex2.execute(ToolCall("1", "rpc_raw", request)).isError)
    }

    // 4. Approved bytes == executed bytes (no TOCTOU) — covered in depth by ToctouTests.

    // 5. Reading device-sourced content taints the turn; a later state change re-prompts.
    @Test
    fun invariant5_readingTaintsAndReprompts(): Unit = runBlocking {
        val flipper = FakeFlipper(files = mapOf("/ext/a.txt" to "hi"))
        val gate = ScriptedGate(true)
        // autoApproveMedium would normally skip a clean medium action...
        val ex = ToolExecutor(flipper, gate, InMemoryAuditLog(), settings = { ApprovalSettings(autoApproveMedium = true) })
        ex.execute(ToolCall("1", "read_file", """{"path":"/ext/a.txt"}"""))
        assertTrue(ex.isTainted)
        ex.execute(ToolCall("2", "create_directory", """{"path":"/ext/new"}"""))
        assertEquals(1, gate.requests.size, "a state change after reading tainted content must re-prompt")
        assertTrue(gate.requests[0].afterUntrustedContent)
    }

    // 6. Risk level never originates from model output or a model-supplied field.
    @Test
    fun invariant6_riskIsNotModelSupplied() {
        // Injecting bogus risk fields into tool arguments does not lower the computed risk.
        val clean = RiskAssessor.assess(ToolInvocation("delete", """{"path":"/ext/x.txt"}""")).level
        val spoofed = RiskAssessor.assess(
            ToolInvocation("delete", """{"path":"/ext/x.txt","risk":"low","risk_level":"low","level":0}"""),
        ).level
        assertEquals(RiskLevel.HIGH, clean)
        assertEquals(clean, spoofed, "extra model-supplied risk fields must be ignored")

        // A transmit stays HIGH no matter what the model claims.
        val transmit = RiskAssessor.assess(
            ToolInvocation("transmit_subghz", """{"path":"/ext/subghz/g.sub","risk":"low"}"""),
        ).level
        assertEquals(RiskLevel.HIGH, transmit)
    }
}
