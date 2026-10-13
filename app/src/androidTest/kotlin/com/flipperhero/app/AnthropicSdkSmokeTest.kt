package com.flipperhero.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flipperhero.agent.AIProvider
import com.flipperhero.agent.ChatMessage
import com.flipperhero.agent.ChatRole
import com.flipperhero.agent.ProviderAPI
import com.flipperhero.agent.ProviderError
import com.flipperhero.agent.ToolCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the Anthropic SDK works on the Android runtime (not just the JVM): it builds a full
 * request with the real system prompt shape and tool catalog, reaches the API, and maps the
 * rejection of a deliberately invalid key to a status-only error. Needs network; uses no real key.
 */
@RunWith(AndroidJUnit4::class)
class AnthropicSdkSmokeTest {

    @Test
    fun invalidKeyIsRejectedWithStatusOnly() {
        val client = ProviderAPI(AIProvider.ANTHROPIC, AIProvider.ANTHROPIC.baseURL, "sk-ant-invalid-smoke-test")
            .client(AIProvider.ANTHROPIC.defaultModel)
        val history = listOf(
            ChatMessage(role = ChatRole.SYSTEM, content = "You are a test."),
            ChatMessage(role = ChatRole.USER, content = "Reply with exactly OK."),
        )
        try {
            runBlocking { client.complete(history, ToolCatalog.specs) }
            fail("an invalid key must not produce a reply")
        } catch (e: ProviderError.Status) {
            assertEquals(401, e.code)
        }
    }
}
