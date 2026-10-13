package com.flipperhero.agent

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

enum class ChatRole(val raw: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool") }

data class ToolCall(val id: String, val name: String, /** Raw JSON string exactly as the model produced it. */ val arguments: String)

data class ChatMessage(
    val role: ChatRole,
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallID: String? = null,
    /** PNG/JPEG data sent alongside content, for models that accept images. */
    var images: List<ByteArray> = emptyList(),
    /** Opaque provider fields required when continuing a tool-calling reasoning turn. */
    val reasoning: Map<String, JsonValue> = emptyMap(),
)

interface LLMClient {
    suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage
}

sealed class LLMError(message: String) : Exception(message) {
    data class Http(val code: Int, val detail: String) : LLMError("Model API error $code: $detail")
    data class Malformed(val why: String) : LLMError("Unexpected model response: $why")
}

/** OpenAI-compatible chat completions with tool calling. Mirrors Swift `ChatCompletionsClient`. */
data class ChatCompletionsClient(
    val apiKey: String,
    val model: String,
    val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    val provider: AIProvider = AIProvider.OPENROUTER,
    val connectionTest: Boolean = false,
    private val http: OkHttpClient = ProviderAPI.defaultClient,
) : LLMClient {

    fun makeRequestBody(messages: List<ChatMessage>, tools: List<ToolSpec>): ByteArray {
        val wireMessages = JsonArray()
        for (m in messages) {
            val d = JsonObject()
            d.addProperty("role", m.role.raw)
            if (m.images.isNotEmpty()) {
                val parts = JsonArray()
                m.content?.takeIf { it.isNotEmpty() }?.let {
                    parts.add(JsonObject().apply { addProperty("type", "text"); addProperty("text", it) })
                }
                for (image in m.images) {
                    val mime = if (image.size >= 4 && image[0].toInt() and 0xFF == 0x89 &&
                        image[1].toInt() == 0x50 && image[2].toInt() == 0x4E && image[3].toInt() == 0x47
                    ) "image/png" else "image/jpeg"
                    val url = JsonObject().apply { addProperty("url", "data:$mime;base64," + Base64.getEncoder().encodeToString(image)) }
                    parts.add(JsonObject().apply { addProperty("type", "image_url"); add("image_url", url) })
                }
                d.add("content", parts)
            } else if (m.content != null) {
                d.addProperty("content", m.content)
            } else if (m.role == ChatRole.ASSISTANT) {
                d.add("content", JsonNull.INSTANCE)
            }
            if (m.toolCalls.isNotEmpty()) {
                val calls = JsonArray()
                for (c in m.toolCalls) {
                    calls.add(JsonObject().apply {
                        addProperty("id", c.id)
                        addProperty("type", "function")
                        add("function", JsonObject().apply { addProperty("name", c.name); addProperty("arguments", c.arguments) })
                    })
                }
                d.add("tool_calls", calls)
            }
            m.toolCallID?.let { d.addProperty("tool_call_id", it) }
            if (m.role == ChatRole.ASSISTANT) {
                // Anthropic's stored content blocks mean nothing to an OpenAI-shape provider.
                for ((key, value) in m.reasoning) if (key != AnthropicClient.CONTENT_KEY) d.add(key, value.toElement())
            }
            wireMessages.add(d)
        }

        val body = JsonObject()
        body.addProperty("model", model)
        body.add("messages", wireMessages)
        if (provider == AIProvider.OPENROUTER) body.addProperty("temperature", 0.2)
        if (provider == AIProvider.MINIMAX) body.addProperty("reasoning_split", true)
        if (connectionTest) {
            body.addProperty("max_tokens", 1024)
            if (provider == AIProvider.MINIMAX && model == "MiniMax-M3") {
                body.add("thinking", JsonObject().apply { addProperty("type", "disabled") })
            }
            if (provider == AIProvider.KIMI && model == "kimi-k3") body.addProperty("reasoning_effort", "low")
        }
        if (tools.isNotEmpty()) {
            val toolsArray = JsonArray()
            for (spec in tools) {
                toolsArray.add(JsonObject().apply {
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", spec.name)
                        addProperty("description", spec.description)
                        add("parameters", spec.parameters.toElement())
                    })
                })
            }
            body.add("tools", toolsArray)
            body.addProperty("tool_choice", "auto")
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "FlipperHero")
            .post(makeRequestBody(messages, tools).toRequestBody("application/json".toMediaType()))
            .build()
        // OkHttp execute() blocks; keep it off the caller's thread (e.g. the Android main thread).
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    // Error bodies can echo a credential. Only expose the status.
                    throw ProviderError.Status(response.code)
                }
                parseResponse(response.body?.string().orEmpty())
            }
        }
    }

    companion object {
        fun parseResponse(data: String): ChatMessage {
            val root = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()
                ?: throw LLMError.Malformed("not JSON")
            val message = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.getAsJsonObject("message")
                ?: throw LLMError.Malformed("no choices")
            val calls = message.getAsJsonArray("tool_calls")?.mapNotNull { element ->
                val raw = element.asJsonObject
                val id = raw.get("id")?.asString ?: return@mapNotNull null
                val fn = raw.getAsJsonObject("function") ?: return@mapNotNull null
                val name = fn.get("name")?.asString ?: return@mapNotNull null
                ToolCall(id, name, fn.get("arguments")?.asString ?: "{}")
            } ?: emptyList()
            val reasoning = LinkedHashMap<String, JsonValue>()
            for (key in listOf("reasoning_content", "reasoning_details", "reasoning")) {
                message.get(key)?.takeIf { !it.isJsonNull }?.let { reasoning[key] = JsonValue.from(it) }
            }
            val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString
            return ChatMessage(role = ChatRole.ASSISTANT, content = content, toolCalls = calls, reasoning = reasoning)
        }
    }
}
