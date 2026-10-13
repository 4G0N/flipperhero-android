package com.flipperhero.agent

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnthropicClientTests {
    private fun message(content: String, stop: String, extra: String = "") = jsonMapper().readValue(
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":$content,
            "stop_reason":"$stop","stop_sequence":null,$extra
            "usage":{"input_tokens":1,"output_tokens":1}}""",
        Message::class.java,
    )

    @Test
    fun wireShapeMergesToolResultsAndLiftsSystem() {
        val calls = listOf(ToolCall("t1", "list_directory", """{"path":"/ext"}"""), ToolCall("t2", "device_info", "{}"))
        val history = listOf(
            ChatMessage(role = ChatRole.SYSTEM, content = "sys"),
            ChatMessage(role = ChatRole.USER, content = "hi"),
            ChatMessage(role = ChatRole.ASSISTANT, content = null, toolCalls = calls),
            ChatMessage(role = ChatRole.TOOL, content = "a", toolCallID = "t1"),
            ChatMessage(role = ChatRole.TOOL, content = "b", toolCallID = "t2"),
            ChatMessage(role = ChatRole.USER, content = "capture", images = listOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))),
        )
        assertEquals("sys", AnthropicClient.systemPrompt(history))
        val wire = AnthropicClient.wireMessages(history)
        assertEquals(listOf("user", "assistant", "user", "user"), wire.map { it.asJsonObject.get("role").asString })
        val uses = wire[1].asJsonObject.getAsJsonArray("content")
        assertEquals(listOf("tool_use", "tool_use"), uses.map { it.asJsonObject.get("type").asString })
        assertEquals("/ext", uses[0].asJsonObject.getAsJsonObject("input").get("path").asString)
        val results = wire[2].asJsonObject.getAsJsonArray("content")
        assertEquals(listOf("t1", "t2"), results.map { it.asJsonObject.get("tool_use_id").asString })
        val image = wire[3].asJsonObject.getAsJsonArray("content")[1].asJsonObject
        assertEquals("image/png", image.getAsJsonObject("source").get("media_type").asString)
    }

    @Test
    fun replyRoundTripsItsContentBlocksVerbatim() {
        val content = """[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":"Listing."},""" +
            """{"type":"tool_use","id":"t1","name":"list_directory","input":{"path":"/ext","depth":2}}]"""
        val parsed = AnthropicClient.parse(message(content, "tool_use"))
        assertEquals("Listing.", parsed.content)
        assertEquals("list_directory", parsed.toolCalls.single().name)
        assertEquals(JsonParser.parseString("""{"path":"/ext","depth":2}"""), JsonParser.parseString(parsed.toolCalls.single().arguments))

        val replayed = AnthropicClient.wireMessages(listOf(parsed))[0].asJsonObject.getAsJsonArray("content")
        assertEquals(JsonParser.parseString(content), replayed)
        val stripped = AnthropicClient.wireMessages(listOf(parsed), dropThinking = true)[0].asJsonObject.getAsJsonArray("content")
        assertEquals(listOf("text", "tool_use"), stripped.map { it.asJsonObject.get("type").asString })

        // Another provider must never be sent Anthropic's blocks.
        val openAi = String(ChatCompletionsClient(apiKey = "k", model = "m").makeRequestBody(listOf(parsed), emptyList()))
        assertFalse(openAi.contains(AnthropicClient.CONTENT_KEY))
    }

    @Test
    fun refusalIsAnErrorNotAReply() {
        val refused = message("[]", "refusal", """"stop_details":{"type":"refusal","category":"cyber","explanation":"x"},""")
        val error = assertThrows(ProviderError.Refused::class.java) { AnthropicClient.parse(refused) }
        assertEquals("cyber", error.category)
        assertTrue(error.message!!.contains("cyber"))
        assertNull(AnthropicClient.parse(message("[]", "end_turn")).content)
    }

    @Test
    fun providerIsPinnedToItsOwnHost() {
        assertEquals("api.anthropic.com", AIProvider.ANTHROPIC.validatedBaseURL(AIProvider.ANTHROPIC.baseURL).host)
        assertThrows(ProviderError.InvalidEndpoint::class.java) { AIProvider.ANTHROPIC.validatedBaseURL("https://openrouter.ai/api/v1") }
        assertTrue(ProviderAPI(AIProvider.ANTHROPIC, AIProvider.ANTHROPIC.baseURL, "k").client(AIProvider.ANTHROPIC.defaultModel) is AnthropicClient)
        assertEquals("claude-opus-4-8", AIProvider.ANTHROPIC.defaultModel)
    }
}
