package com.flipperhero.app.di

import android.content.Context
import com.flipperhero.agent.AIProvider
import com.flipperhero.agent.AppControls
import com.flipperhero.agent.AuditSink
import com.flipperhero.agent.ChatCompletionsClient
import com.flipperhero.agent.DeviceProfileStore
import com.flipperhero.agent.FapHubClient
import com.flipperhero.agent.FileAuditLog
import com.flipperhero.agent.FirmwareUpdateChecker
import com.flipperhero.agent.GitHubClient
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

    /** Builds the chat/forge LLM client from the current provider, model, base URL and stored key. */
    fun llm(): ChatCompletionsClient {
        val provider = AIProvider.fromId(settings.provider) ?: AIProvider.OPENROUTER
        val base = settings.apiBaseURL.trimEnd('/')
        return ChatCompletionsClient(
            apiKey = secrets.apiKey(settings.provider) ?: "",
            model = settings.model,
            endpoint = "$base/chat/completions",
            provider = provider,
        )
    }

    fun appControls(onRestartDevice: suspend () -> Unit): AppControls =
        AppControlsImpl(settings, secrets, onRestartDevice)
}
