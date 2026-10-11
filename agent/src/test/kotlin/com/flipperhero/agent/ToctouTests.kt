package com.flipperhero.agent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SAFETY INVARIANT 4: the exact payload bytes shown at approval equal the bytes handed to
 * execution — nothing is re-fetched or re-generated between approve and run (no TOCTOU).
 */
class ToctouTests {
    /** Returns a different payload on each call, so a second generation would be observable. */
    private class CountingLlm : LLMClient {
        var count = 0
        override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage {
            count += 1
            return ChatMessage(role = ChatRole.ASSISTANT, content = "REM demo $count")
        }
    }

    @Test
    fun forgedPayloadIsGeneratedOnceAndApprovedBytesAreExecuted(): Unit = runBlocking {
        val llm = CountingLlm()
        val flipper = FakeFlipper()
        val gate = ScriptedGate(true)
        val ex = ToolExecutor(flipper, gate, InMemoryAuditLog(), forge = PayloadForge(llm))

        val result = ex.execute(ToolCall("1", "forge_payload", """{"type":"badusb","description":"demo","path":"/ext/badusb/x.txt"}"""))
        assertFalse(result.isError, result.content)

        // Generated exactly once (during approval preview); not regenerated for execution.
        assertEquals(1, llm.count, "payload must not be generated a second time between approve and run")

        // The bytes shown in the approval diff are exactly the bytes written to the device.
        val approvedDiff = gate.requests.single().diff!!
        val written = String(flipper.files["/ext/badusb/x.txt"]!!, Charsets.UTF_8)
        assertEquals("REM demo 1", written)
        assertTrue(approvedDiff.contains("REM demo 1"), approvedDiff)
    }

    @Test
    fun writtenFileEqualsApprovedContent(): Unit = runBlocking {
        val flipper = FakeFlipper(files = mapOf("/ext/a.txt" to "old"))
        val gate = ScriptedGate(true)
        val ex = ToolExecutor(flipper, gate, InMemoryAuditLog())
        ex.execute(ToolCall("1", "write_file", """{"path":"/ext/a.txt","content":"the new content"}"""))
        // The diff presented at approval reflects exactly what gets written.
        assertTrue(gate.requests.single().diff!!.contains("+ the new content"))
        assertEquals("the new content", String(flipper.files["/ext/a.txt"]!!, Charsets.UTF_8))
    }
}
