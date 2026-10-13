package com.flipperhero.app.di

import android.content.Context
import com.flipperhero.agent.AIProvider
import com.flipperhero.agent.AppControls
import com.flipperhero.agent.AuditSink
import com.flipperhero.agent.ChatMessage
import com.flipperhero.agent.DeviceProfileStore
import com.flipperhero.agent.FapHubClient
import com.flipperhero.agent.FileAuditLog
import com.flipperhero.agent.FirmwareUpdateChecker
import com.flipperhero.agent.GitHubClient
import com.flipperhero.agent.LLMClient
import com.flipperhero.agent.ProviderAPI
import com.flipperhero.agent.ToolSpec
import com.flipperhero.app.approval.ComposeApprover
import com.flipperhero.app.ble.FlipperScanner
import com.flipperhero.app.platform.AndroidSpeechProvider
import com.flipperhero.app.platform.AndroidTtsProvider
import com.flipperhero.app.platform.AppControlsImpl
import com.flipperhero.app.platform.ForegroundServiceOngoingActivity
import com.flipperhero.app.platform.KeystoreSecretStore
import com.flipperhero.app.platform.OngoingActivity
import com.flipperhero.app.platform.SecretStore
import com.flipperhero.app.platform.SettingsStore
import com.flipperhero.app.platform.SpeechProvider
import com.flipperhero.app.platform.TtsProvider
import java.io.File

/** Manual dependency injection: one container built by the Application, holding the singletons. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val scanner = FlipperScanner(appContext)
    val secrets: SecretStore = KeystoreSecretStore(appContext)
    val settings = SettingsStore(appContext)
    val approver = ComposeApprover()
    val tts: TtsProvider = AndroidTtsProvider(appContext)
    val speech: SpeechProvider = AndroidSpeechProvider(appContext)
    val ongoing: OngoingActivity = ForegroundServiceOngoingActivity(appContext)
    val audit: AuditSink = FileAuditLog(File(appContext.filesDir, "audit.jsonl"))
    val profileStore = DeviceProfileStore(File(appContext.filesDir, "profiles"))
    val fapHub = FapHubClient()
    val gitHub = GitHubClient()
    val firmwareChecker = FirmwareUpdateChecker()

    /**
     * The chat/forge LLM client. It follows Settings: provider, model, base URL and stored key are
     * re-read on every request, so a key or model saved mid-session takes effect on the next message
     * instead of the session carrying the values it was connected with. The underlying client is
     * rebuilt only when one of them changes. Built through [ProviderAPI] so the base URL is checked
     * against the provider's own hosts.
     */
    val llm: LLMClient = object : LLMClient {
        private var config: List<String>? = null
        private var client: LLMClient? = null

        @Synchronized
        private fun current(): LLMClient {
            val wanted = listOf(settings.provider, settings.model, settings.apiBaseURL, secrets.apiKey(settings.provider) ?: "")
            client?.takeIf { config == wanted }?.let { return it }
            val provider = AIProvider.fromId(wanted[0]) ?: AIProvider.OPENROUTER
            return ProviderAPI(provider, wanted[2], wanted[3]).client(wanted[1]).also { client = it; config = wanted }
        }

        override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage =
            current().complete(messages, tools)
    }

    fun appControls(onRestartDevice: suspend () -> Unit): AppControls =
        AppControlsImpl(settings, secrets, onRestartDevice)
}
