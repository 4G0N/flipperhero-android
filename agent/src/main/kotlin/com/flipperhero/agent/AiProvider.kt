package com.flipperhero.agent

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

enum class AIProvider(val id: String) {
    OPENROUTER("openrouter"),
    AI2342("ai2342"),
    BLACKBIT("blackbit"),
    ORCAROUTER("orcarouter"),
    ZAI("zai"),
    KIMI("kimi"),
    QWEN("qwen"),
    MINIMAX("minimax");

    val displayName: String
        get() = when (this) {
            OPENROUTER -> "OpenRouter"
            AI2342 -> "2342.ai"
            BLACKBIT -> "Blackbit"
            ORCAROUTER -> "OrcaRouter"
            ZAI -> "Z.ai"
            KIMI -> "Kimi"
            QWEN -> "Qwen Cloud"
            MINIMAX -> "MiniMax"
        }

    val baseURL: String
        get() = when (this) {
            OPENROUTER -> "https://openrouter.ai/api/v1"
            AI2342 -> "https://2342.ai/v1"
            BLACKBIT -> "https://void.blackbit.sh/v1"
            ORCAROUTER -> "https://api.orcarouter.ai/v1"
            ZAI -> "https://api.z.ai/api/paas/v4"
            KIMI -> "https://api.moonshot.ai/v1"
            QWEN -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
            MINIMAX -> "https://api.minimax.io/v1"
        }

    val defaultModel: String
        get() = when (this) {
            OPENROUTER -> "anthropic/claude-sonnet-4.5"
            AI2342 -> ""
            BLACKBIT -> "zai_glm_5_2"
            ORCAROUTER -> "orcarouter/auto"
            ZAI -> "glm-5.3-flash"
            KIMI -> "kimi-k3"
            QWEN -> "qwen-plus"
            MINIMAX -> "MiniMax-M3"
        }

    /** Keep keys within the selected provider, including its regional API hosts. */
    fun validatedBaseURL(address: String): URI {
        val uri = try {
            URI(address.trim())
        } catch (e: Exception) {
            throw ProviderError.InvalidEndpoint
        }
        val host = uri.host?.lowercase()
        if (uri.scheme != "https" || host == null || uri.userInfo != null ||
            uri.query != null || uri.fragment != null || !(uri.port == -1 || uri.port == 443)
        ) {
            throw ProviderError.InvalidEndpoint
        }
        val allowed = when (this) {
            OPENROUTER -> host == "openrouter.ai"
            AI2342 -> host == "2342.ai"
            BLACKBIT -> host == "void.blackbit.sh"
            ORCAROUTER -> host == "api.orcarouter.ai"
            ZAI -> host in listOf("api.z.ai", "open.bigmodel.cn")
            KIMI -> host in listOf("api.moonshot.ai", "api.moonshot.cn")
            QWEN -> host in listOf(
                "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com", "dashscope.aliyuncs.com",
            ) || host.endsWith(".maas.aliyuncs.com")
            MINIMAX -> host in listOf("api.minimax.io", "api.minimaxi.com")
        }
        if (!allowed) throw ProviderError.InvalidEndpoint
        return uri
    }

    companion object {
        fun fromId(id: String): AIProvider? = entries.firstOrNull { it.id == id }
    }
}

sealed class ProviderError(message: String) : Exception(message) {
    object InvalidEndpoint : ProviderError("Use an HTTPS API URL from this provider.") { private fun readResolve(): Any = InvalidEndpoint }
    object MissingKey : ProviderError("Add an API key in Settings first.") { private fun readResolve(): Any = MissingKey }
    object MissingModel : ProviderError("Choose a model first.") { private fun readResolve(): Any = MissingModel }
    object EmptyReply : ProviderError("The model did not reply. Try another model.") { private fun readResolve(): Any = EmptyReply }
    object ModelsUnavailable : ProviderError("No model list is available. Enter a model ID from the provider documentation.") { private fun readResolve(): Any = ModelsUnavailable }
    data class Status(val code: Int) : ProviderError("API request failed (HTTP $code). Check your key, model and account credit.")
}

data class AIModel(val id: String, val name: String)

/** Refuses redirects (so a provider cannot forward a key to another host) and talks the OpenAI shape. */
class ProviderAPI(
    val provider: AIProvider,
    baseURL: String,
    val apiKey: String,
    private val client: OkHttpClient = defaultClient,
) {
    val baseURL: URI = provider.validatedBaseURL(baseURL)

    fun client(model: String): ChatCompletionsClient =
        ChatCompletionsClient(apiKey = apiKey, model = model,
            endpoint = baseURL.toString().trimEnd('/') + "/chat/completions", provider = provider, http = client)

    suspend fun models(): List<AIModel> {
        if (apiKey.isEmpty() && provider !in listOf(AIProvider.OPENROUTER, AIProvider.BLACKBIT, AIProvider.ORCAROUTER)) {
            throw ProviderError.MissingKey
        }
        val builder = Request.Builder().url(baseURL.toString().trimEnd('/') + "/models")
        if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
        client.newCall(builder.build()).execute().use { response ->
            val status = response.code
            if (status in listOf(404, 405, 501)) throw ProviderError.ModelsUnavailable
            if (status !in 200..299) throw ProviderError.Status(status)
            val body = response.body?.string().orEmpty()
            val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                ?: throw ProviderError.ModelsUnavailable
            val data = root.getAsJsonArray("data") ?: throw ProviderError.ModelsUnavailable
            val seen = HashSet<String>()
            val models = data.mapNotNull { element ->
                val obj = element.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                if (id.isEmpty() || !seen.add(id)) return@mapNotNull null
                val supported = obj.getAsJsonArray("supported_parameters")?.map { it.asString }
                if (supported != null && !supported.contains("tools")) return@mapNotNull null
                AIModel(id, obj.get("name")?.asString ?: id)
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            if (models.isEmpty()) throw ProviderError.ModelsUnavailable
            return models
        }
    }

    /** Only this fixed prompt is sent: no conversation, device data or tools. */
    suspend fun test(model: String) {
        if (apiKey.isEmpty()) throw ProviderError.MissingKey
        if (model.trim().isEmpty()) throw ProviderError.MissingModel
        val probe = client(model).copy(connectionTest = true)
        val reply = probe.complete(listOf(ChatMessage(role = ChatRole.USER, content = "Reply with exactly OK.")), emptyList())
        if ((reply.content ?: "").trim().isEmpty()) throw ProviderError.EmptyReply
    }

    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
