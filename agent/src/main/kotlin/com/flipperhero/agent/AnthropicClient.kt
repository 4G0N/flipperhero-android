package com.flipperhero.agent

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import com.anthropic.client.AnthropicClient as SdkClient
import com.anthropic.core.JsonValue as SdkJson

/**
 * Anthropic Messages API via the official SDK. Not part of the iOS app (which only speaks the
 * OpenAI shape); added for the Android port so a first-party Anthropic key works directly.
 *
 * A safety-classifier decline is surfaced as [ProviderError.Refused], never hidden or silently
 * retried on another model.
 */
class AnthropicClient(
    private val apiKey: String,
    val model: String,
    private val baseURL: String = AIProvider.ANTHROPIC.baseURL,
    private val connectionTest: Boolean = false,
) : LLMClient {

    private val sdk: SdkClient by lazy {
        AnthropicOkHttpClient.builder().apiKey(apiKey).baseUrl(baseURL).build()
    }

    /**
     * Set once the API reports that replayed thinking blocks no longer match the history (the
     * session prunes old screen captures, which edits earlier turns). From then on this client
     * replays text and tool calls only.
     */
    @Volatile private var dropThinking = false

    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage =
        withContext(Dispatchers.IO) {
            val reply = try {
                try {
                    sdk.messages().create(params(messages, tools))
                } catch (e: BadRequestException) {
                    if (dropThinking || e.message?.contains("thinking") != true) throw e
                    dropThinking = true
                    sdk.messages().create(params(messages, tools))
                }
            } catch (e: BadRequestException) {
                // A 400 is about the request, not the account; its explanation is the only way to
                // act on it. The key is scrubbed in case the body echoes it.
                val detail = e.message.orEmpty().replace(apiKey, "[key]").take(400)
                throw ProviderError.Rejected(e.statusCode(), detail)
            } catch (e: AnthropicServiceException) {
                // Error bodies can echo a credential. Only expose the status.
                throw ProviderError.Status(e.statusCode())
            }
            parse(reply)
        }

    suspend fun models(): List<AIModel> = withContext(Dispatchers.IO) {
        val models = try {
            sdk.models().list().autoPager().map { AIModel(it.id(), it.displayName()) }
        } catch (e: AnthropicServiceException) {
            throw ProviderError.Status(e.statusCode())
        }
        if (models.isEmpty()) throw ProviderError.ModelsUnavailable
        models
    }

    private fun params(messages: List<ChatMessage>, tools: List<ToolSpec>): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(if (connectionTest) 1024L else MAX_TOKENS)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
            // Explicit because models before Opus 5 run without thinking when this is omitted.
            .thinking(ThinkingConfigAdaptive.builder().build())
            .cacheControl(CacheControlEphemeral.builder().build())
        systemPrompt(messages).takeIf { it.isNotEmpty() }?.let { builder.system(it) }
        for (spec in tools) {
            builder.addTool(
                Tool.builder()
                    .name(spec.name)
                    .description(spec.description)
                    .inputSchema(sdkJson(spec.parameters.toElement().toString()))
                    .build(),
            )
        }
        for (element in wireMessages(messages, dropThinking)) {
            val wire = element.asJsonObject
            builder.addMessage(
                MessageParam.builder()
                    .role(if (wire.get("role").asString == "assistant") MessageParam.Role.ASSISTANT else MessageParam.Role.USER)
                    .content(sdkJson(wire.get("content").toString()))
                    .build(),
            )
        }
        return builder.build()
    }

    companion object {
        /** [ChatMessage.reasoning] key holding the assistant turn's content blocks exactly as returned. */
        const val CONTENT_KEY = "anthropic_content"
        private const val MAX_TOKENS = 16000L

        private fun sdkJson(json: String): SdkJson = jsonMapper().readValue(json, SdkJson::class.java)

        fun systemPrompt(messages: List<ChatMessage>): String =
            messages.filter { it.role == ChatRole.SYSTEM }.mapNotNull { it.content }.joinToString("\n\n")

        /** Messages in the Anthropic shape: `[{"role": ..., "content": [blocks]}]`. System messages are excluded. */
        fun wireMessages(messages: List<ChatMessage>, dropThinking: Boolean = false): JsonArray {
            val out = JsonArray()
            var results: JsonArray? = null
            for (m in messages) {
                if (m.role == ChatRole.TOOL) {
                    // Every result for one assistant turn goes back in a single user message.
                    val blocks = results ?: JsonArray().also {
                        results = it
                        out.add(JsonObject().apply { addProperty("role", "user"); add("content", it) })
                    }
                    blocks.add(JsonObject().apply {
                        addProperty("type", "tool_result")
                        addProperty("tool_use_id", m.toolCallID)
                        addProperty("content", m.content ?: "")
                    })
                    continue
                }
                results = null
                when (m.role) {
                    ChatRole.USER -> out.add(JsonObject().apply { addProperty("role", "user"); add("content", userBlocks(m)) })
                    ChatRole.ASSISTANT -> out.add(JsonObject().apply { addProperty("role", "assistant"); add("content", assistantBlocks(m, dropThinking)) })
                    else -> Unit
                }
            }
            return out
        }

        private fun text(value: String) = JsonObject().apply { addProperty("type", "text"); addProperty("text", value) }

        private fun userBlocks(m: ChatMessage): JsonArray {
            val blocks = JsonArray()
            // The API rejects empty text blocks.
            val content = m.content.orEmpty()
            if (content.isNotBlank() || m.images.isEmpty()) blocks.add(text(content.ifBlank { "(empty)" }))
            for (image in m.images) {
                val mime = if (image.size >= 4 && image[0].toInt() and 0xFF == 0x89 &&
                    image[1].toInt() == 0x50 && image[2].toInt() == 0x4E && image[3].toInt() == 0x47
                ) "image/png" else "image/jpeg"
                blocks.add(JsonObject().apply {
                    addProperty("type", "image")
                    add("source", JsonObject().apply {
                        addProperty("type", "base64")
                        addProperty("media_type", mime)
                        addProperty("data", Base64.getEncoder().encodeToString(image))
                    })
                })
            }
            return blocks
        }

        private fun assistantBlocks(m: ChatMessage, dropThinking: Boolean): JsonArray {
            val stored = m.reasoning[CONTENT_KEY]?.stringValue
                ?.let { runCatching { JsonParser.parseString(it).asJsonArray }.getOrNull() }
            if (stored != null) {
                if (!dropThinking) return stored
                val kept = JsonArray()
                stored.filterNot { it.asJsonObject.get("type")?.asString in listOf("thinking", "redacted_thinking") }
                    .forEach { kept.add(it) }
                if (kept.size() > 0) return kept
            }
            // A turn produced by another provider (or the session's own step-limit note).
            val blocks = JsonArray()
            m.content?.takeIf { it.isNotBlank() }?.let { blocks.add(text(it)) }
            for (call in m.toolCalls) {
                blocks.add(JsonObject().apply {
                    addProperty("type", "tool_use")
                    addProperty("id", call.id)
                    addProperty("name", call.name)
                    add("input", runCatching { JsonParser.parseString(call.arguments).asJsonObject }.getOrDefault(JsonObject()))
                })
            }
            if (blocks.size() == 0) blocks.add(text("(no reply)"))
            return blocks
        }

        fun parse(reply: Message): ChatMessage {
            if (reply.stopReason().orElse(null) == StopReason.REFUSAL) {
                val category = reply.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse(null)
                throw ProviderError.Refused(category)
            }
            val mapper = jsonMapper()
            val text = StringBuilder()
            val calls = ArrayList<ToolCall>()
            for (block in reply.content()) {
                block.text().ifPresent { text.append(it.text()) }
                block.toolUse().ifPresent { calls.add(ToolCall(it.id(), it.name(), mapper.writeValueAsString(it._input()))) }
            }
            return ChatMessage(
                role = ChatRole.ASSISTANT,
                content = text.toString().ifEmpty { null },
                toolCalls = calls,
                reasoning = mapOf(CONTENT_KEY to JsonValue.Str(mapper.writeValueAsString(reply.content()))),
            )
        }
    }
}
