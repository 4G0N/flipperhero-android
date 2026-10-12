package com.flipperhero.app.platform

import com.flipperhero.agent.AIModel
import com.flipperhero.agent.AIProvider
import com.flipperhero.agent.AppControls
import com.flipperhero.agent.AppSettingsSnapshot
import com.flipperhero.agent.EngagementState
import com.flipperhero.agent.ProviderAPI

/**
 * Bridges the agent's [AppControls] to the Android settings/secret stores and the live connection.
 * Loosening changes arrive here only after the executor obtained explicit consent, so they apply
 * without asking again. Credentials are never returned — only "is one stored".
 */
class AppControlsImpl(
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    /** Restarts the connected Flipper; supplied by the connection layer. */
    private val onRestartDevice: suspend () -> Unit,
) : AppControls {

    override suspend fun settings(): AppSettingsSnapshot {
        val engagement = settings.engagement.value
        return AppSettingsSnapshot(
            yolo = settings.yolo,
            yoloAsksAfterReading = settings.yoloAsksAfterReading,
            autoApproveMedium = settings.autoApproveMedium,
            readAloud = settings.readAloud,
            autoConnect = settings.autoConnect,
            model = settings.model,
            apiKeyStored = secrets.hasApiKey(settings.provider),
            provider = settings.provider,
            apiBaseURL = settings.apiBaseURL,
            engagementActive = engagement.active,
            engagementSummary = if (engagement.active) engagement.profile.summary else "off",
        )
    }

    override suspend fun setReadAloud(enabled: Boolean) { settings.readAloud = enabled }
    override suspend fun setAutoConnect(enabled: Boolean) { settings.autoConnect = enabled }
    override suspend fun setYolo(enabled: Boolean) { settings.yolo = enabled }
    override suspend fun setYoloAsksAfterReading(enabled: Boolean) { settings.yoloAsksAfterReading = enabled }
    override suspend fun setAutoApproveMedium(enabled: Boolean) { settings.autoApproveMedium = enabled }
    override suspend fun setModel(model: String) { settings.model = model }

    override suspend fun setProvider(provider: AIProvider, baseURL: String?) {
        settings.provider = provider.id
        settings.apiBaseURL = baseURL ?: provider.baseURL
        // Restore the provider's default model if none is set for it.
        if (settings.model.isEmpty()) settings.model = provider.defaultModel
    }

    override suspend fun listModels(): List<AIModel> {
        val provider = AIProvider.fromId(settings.provider) ?: AIProvider.OPENROUTER
        return ProviderAPI(provider, settings.apiBaseURL, secrets.apiKey(settings.provider) ?: "").models()
    }

    override suspend fun testConnection() {
        val provider = AIProvider.fromId(settings.provider) ?: AIProvider.OPENROUTER
        ProviderAPI(provider, settings.apiBaseURL, secrets.apiKey(settings.provider) ?: "").test(settings.model)
    }

    override suspend fun setEngagement(state: EngagementState) { settings.setEngagement(state) }

    override suspend fun restartDevice() = onRestartDevice()

    // Firmware-over-BLE install is a large, hardware-coupled flow (download, stage, reboot into the
    // updater). It is deferred for v1; the tool reports this honestly rather than silently no-op.
    override suspend fun startFirmwareUpdate(): String =
        throw UnsupportedOperationException("Firmware update over Bluetooth is not implemented in this build yet.")
    override suspend fun firmwareUpdateStatus(): String = "No update running."
    override suspend fun cancelFirmwareUpdate(): String = "No update to cancel."
}
