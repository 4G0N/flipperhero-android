package com.flipperhero.agent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ported from Swift `EngagementTests`. */
class EngagementTests {
    private data class Setup(
        val ex: ToolExecutor, val flipper: FakeFlipper, val gate: ScriptedGate,
        val audit: InMemoryAuditLog, val controls: FakeAppControls,
    )

    private fun make(engagement: EngagementState = EngagementState.inactive, approve: Boolean = true, files: Map<String, String> = emptyMap()): Setup {
        val flipper = FakeFlipper(files)
        val gate = ScriptedGate(approve)
        val audit = InMemoryAuditLog()
        val controls = FakeAppControls()
        val ex = ToolExecutor(flipper, gate, audit, appControls = controls, settings = { ApprovalSettings(engagement = engagement) })
        return Setup(ex, flipper, gate, audit, controls)
    }

    private fun armed(autoApprovals: Boolean = true, rawRPC: Boolean = false, autoBadKB: Boolean = false): EngagementState =
        EngagementState(active = true, profile = EngagementProfile(autoApprovals, rawRPC, autoBadKB), startedAt = System.currentTimeMillis())

    // MARK: Policy

    @Test
    fun engagementAutoApprovalsSkipPromptsButBlockedStaysBlocked(): Unit = runBlocking {
        val (ex, flipper, gate, audit, _) = make(engagement = armed())
        val medium = ex.execute(ToolCall("1", "create_directory", """{"path":"/ext/e"}"""))
        assertFalse(medium.isError)
        flipper.seed("/ext/subghz/g.sub", "x".toByteArray())
        val high = ex.execute(ToolCall("2", "transmit_subghz", """{"path":"/ext/subghz/g.sub"}"""))
        assertFalse(high.isError, high.content)
        val blocked = ex.execute(ToolCall("3", "write_file", """{"path":"/int/x","content":"x"}"""))
        assertEquals(ToolResult.Kind.BLOCKED, blocked.kind)
        assertTrue(gate.requests.isEmpty(), "armed engagement must not prompt")
        assertEquals(
            listOf(AuditRecord.Decision.ENGAGED, AuditRecord.Decision.ENGAGED, AuditRecord.Decision.BLOCKED),
            audit.records.map { it.decision },
        )
        assertTrue(flipper.calls.any { it.startsWith("transmit") })
    }

    @Test
    fun engagementWithoutAutoApprovalsStillAsks(): Unit = runBlocking {
        val (ex, _, gate, _, _) = make(engagement = armed(autoApprovals = false))
        ex.execute(ToolCall("1", "create_directory", """{"path":"/ext/e"}"""))
        assertEquals(1, gate.requests.size, "capabilities alone do not skip prompts")
    }

    @Test
    fun armingAlwaysNeedsConsentEvenUnderEveryYoloFlag(): Unit = runBlocking {
        val controls = FakeAppControls()
        val gate = ScriptedGate(false)
        val ex = ToolExecutor(FakeFlipper(), gate, InMemoryAuditLog(), appControls = controls,
            settings = { ApprovalSettings(autoApproveMedium = true, yolo = true, yoloAsksAfterUntrusted = false) })
        val result = ex.execute(ToolCall("1", "set_engagement_mode", """{"enabled":true,"raw_rpc":true}"""))
        assertEquals(ToolResult.Kind.DENIED, result.kind)
        assertEquals(1, gate.requests.size)
        assertTrue(gate.requests[0].isPermissionChange)
        assertFalse(controls.engagement.active, "a denied request must leave engagement mode off")
    }

    @Test
    fun approvedArmingAppliesProfileAndDisarmingIsFree(): Unit = runBlocking {
        val (ex, _, gate, _, controls) = make()
        ex.execute(ToolCall("1", "set_engagement_mode", """{"enabled":true,"raw_rpc":true,"note":"client X physical"}"""))
        val armedState = controls.engagement
        assertTrue(armedState.active)
        assertTrue(armedState.profile.rawRPC)
        assertEquals("client X physical", armedState.profile.note)
        assertEquals(1, gate.requests.size)

        ex.execute(ToolCall("2", "set_engagement_mode", """{"enabled":false}"""))
        assertEquals(1, gate.requests.size, "disarming needs no dialog")
        assertFalse(controls.engagement.active)
    }

    // MARK: Capability gates

    @Test
    fun badUsbExecuteIsLockedUntilArmed(): Unit = runBlocking {
        val (ex, flipper, gate, audit, _) = make(files = mapOf("/ext/badusb/a.txt" to "REM x"))
        val locked = ex.execute(ToolCall("1", "badusb_execute", """{"path":"/ext/badusb/a.txt"}"""))
        assertTrue(locked.isError)
        assertTrue(locked.content.contains("engagement mode"))
        assertTrue(flipper.calls.isEmpty(), "a locked capability must not touch the device")
        assertTrue(gate.requests.isEmpty())
        assertEquals(listOf(AuditRecord.Decision.BLOCKED), audit.records.map { it.decision })

        val s2 = make(engagement = armed(autoApprovals = false, autoBadKB = true), approve = false, files = mapOf("/ext/badusb/a.txt" to "REM x"))
        val asked = s2.ex.execute(ToolCall("1", "badusb_execute", """{"path":"/ext/badusb/a.txt"}"""))
        assertEquals(ToolResult.Kind.DENIED, asked.kind, "armed capability without auto-approvals still asks")
        assertTrue(s2.flipper.presses.isEmpty())
    }

    @Test
    fun badUsbExecuteRunsTheScriptWhenArmed(): Unit = runBlocking {
        val (ex, flipper, _, audit, _) = make(engagement = armed(autoBadKB = true), files = mapOf("/ext/badusb/a.txt" to "STRING hi"))
        val result = ex.execute(ToolCall("1", "badusb_execute", """{"path":"/ext/badusb/a.txt"}"""))
        assertFalse(result.isError, result.content)
        assertEquals(1, result.images.size, "the capture after Run is attached")
        assertTrue(flipper.calls.contains("badusb /ext/badusb/a.txt"))
        assertTrue(flipper.calls.contains("capture"))
        assertEquals(listOf("ok"), flipper.presses, "the OK input event starts the script")
        assertEquals(listOf(AuditRecord.Decision.ENGAGED), audit.records.map { it.decision })
    }

    @Test
    fun rawRPCIsLockedUntilArmedAndFencesOutput(): Unit = runBlocking {
        val (ex, flipper, _, _, _) = make()
        val locked = ex.execute(ToolCall("1", "rpc_raw", """{"request":"{\"content\":{\"systemPingRequest\":{}}}"}"""))
        assertTrue(locked.isError)
        assertTrue(flipper.calls.isEmpty())

        val s2 = make(engagement = armed(rawRPC = true))
        val ok = s2.ex.execute(ToolCall("1", "rpc_raw", """{"request":"{\"content\":{\"systemPingRequest\":{}}}"}"""))
        assertFalse(ok.isError, ok.content)
        assertTrue(ok.content.contains("<<<FLIPPER_DATA"), "raw device data is untrusted content")
        assertEquals("""{"content":{"systemPingRequest":{}}}""", s2.flipper.rawRequests.first())
        assertTrue(s2.ex.isTainted)
    }

    @Test
    fun gpioTools(): Unit = runBlocking {
        val (ex, flipper, gate, _, _) = make(approve = false)
        val read = ex.execute(ToolCall("1", "gpio", """{"action":"read","pin":"pa4"}"""))
        assertFalse(read.isError)
        assertTrue(read.content.contains("high"))
        val write = ex.execute(ToolCall("2", "gpio", """{"action":"write","pin":"pa7","value":"1"}"""))
        assertEquals(ToolResult.Kind.DENIED, write.kind, "driving a pin asks")
        assertEquals(1, gate.requests.size)
        val configure = ex.execute(ToolCall("3", "gpio", """{"action":"configure","pin":"pb3","direction":"input","pull":"up"}"""))
        assertEquals(ToolResult.Kind.DENIED, configure.kind)
        assertEquals(listOf("gpio read pa4"), flipper.calls)
        assertThrows(ToolError::class.java) { ToolInvocation("gpio", """{"action":"read","pin":"zz9"}""") }
        assertThrows(ToolError::class.java) { ToolInvocation("gpio", """{"action":"explode","pin":"pa4"}""") }
    }

    // MARK: Report

    @Test
    fun reportContainsTimelineAndDecisions(): Unit = runBlocking {
        val (ex, _, _, audit, _) = make(engagement = armed(autoBadKB = true), files = mapOf("/ext/badusb/a.txt" to "STRING hi"))
        ex.execute(ToolCall("1", "get_device_info", "{}"))
        ex.execute(ToolCall("2", "badusb_execute", """{"path":"/ext/badusb/a.txt"}"""))
        val result = ex.execute(ToolCall("3", "generate_engagement_report", "{}"))
        assertFalse(result.isError, result.content)
        assertTrue(result.content.contains("# Engagement report"))
        assertTrue(result.content.contains("| get_device_info | low | auto | ok |"))
        assertTrue(result.content.contains("| badusb_execute | high | engaged | ok |"))
        assertTrue(result.content.contains("auto_approvals+auto_badusb"))

        val markdown = EngagementReport.markdown(audit.recent(10), EngagementState.inactive)
        assertFalse(markdown.contains("Engagement armed"), "a disarmed session reports no arming")
        val hostile = AuditRecord("read_file", "path | with pipe\nnewline", RiskLevel.LOW, AuditRecord.Decision.AUTO, true, "")
        val escaped = EngagementReport.markdown(listOf(hostile), EngagementState.inactive)
        assertFalse(escaped.split("\n").any { it.contains("path | with pipe") }, "table cells must not break the markdown table")
    }

    // MARK: Settings and prompt

    @Test
    fun settingsReportEngagementState(): Unit = runBlocking {
        val (ex, _, _, _, controls) = make()
        controls.setEngagement(armed(rawRPC = true))
        val result = ex.execute(ToolCall("1", "get_app_settings", "{}"))
        assertTrue(result.content.contains("engagement_mode: armed (auto_approvals+raw_rpc)"), result.content)
        val off = make().ex
        val disabled = off.execute(ToolCall("1", "get_app_settings", "{}"))
        assertTrue(disabled.content.contains("engagement_mode: off"))
    }

    @Test
    fun promptReflectsArming() {
        val locked = AgentPrompts.system(nonce = "n1")
        assertTrue(locked.contains("not armed"))
        assertTrue(locked.contains("<<<FLIPPER_DATA"), "the data fence rule stays in every mode")
        val armedPrompt = AgentPrompts.system(nonce = "n1",
            engagement = EngagementState(active = true, profile = EngagementProfile(autoBadKB = true), startedAt = System.currentTimeMillis()))
        assertTrue(armedPrompt.contains("ARMED"))
        assertTrue(armedPrompt.contains("badusb_execute"))
        assertFalse(armedPrompt.contains("Scope note"), "no note, no scope line")
        val scoped = AgentPrompts.system(nonce = "n1",
            engagement = EngagementState(active = true, profile = EngagementProfile(note = "office lobby"), startedAt = System.currentTimeMillis()))
        assertTrue(scoped.contains("office lobby"))
    }

    @Test
    fun argumentParsing() {
        val engage = ToolInvocation("set_engagement_mode", """{"enabled":true,"raw_rpc":true,"note":"x"}""")
        assertEquals("set_engagement_mode", engage.toolName)
        assertTrue(engage.requiresExplicitConsent)
        engage as ToolInvocation.SetEngagementMode
        assertTrue(engage.enabled)
        assertTrue(engage.profile.rawRPC)
        assertFalse(engage.profile.autoBadKB)
        assertFalse(ToolInvocation("set_engagement_mode", """{"enabled":false}""").requiresExplicitConsent)
        assertEquals(ToolInvocation.GenerateEngagementReport(200), ToolInvocation("generate_engagement_report", "{}"))
        val raw = ToolInvocation("rpc_raw", """{"request":"{}"}""")
        assertEquals("Send the raw device command request", raw.summary)
    }
}
