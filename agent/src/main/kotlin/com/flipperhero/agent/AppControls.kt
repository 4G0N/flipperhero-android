package com.flipperhero.agent

/** The app settings as the agent may see them. Credentials are reduced to "is one stored". */
data class AppSettingsSnapshot(
    val yolo: Boolean,
    val yoloAsksAfterReading: Boolean,
    val autoApproveMedium: Boolean,
    val readAloud: Boolean,
    val autoConnect: Boolean,
    val model: String,
    val apiKeyStored: Boolean,
    val provider: String = "openrouter",
    val apiBaseURL: String = "https://openrouter.ai/api/v1",
    val engagementActive: Boolean = false,
    val engagementSummary: String = "off",
)

/**
 * App-level functions the agent can reach, so everything the UI offers is also available in chat.
 * Loosening changes only ever arrive here after the executor obtained explicit consent; implementations
 * apply them without asking again. Mirrors Swift `AppControls`.
 */
interface AppControls {
    suspend fun settings(): AppSettingsSnapshot
    suspend fun setReadAloud(enabled: Boolean)
    suspend fun setAutoConnect(enabled: Boolean)
    suspend fun setYolo(enabled: Boolean)
    suspend fun setYoloAsksAfterReading(enabled: Boolean)
    suspend fun setAutoApproveMedium(enabled: Boolean)
    suspend fun setModel(model: String)
    suspend fun setProvider(provider: AIProvider, baseURL: String?)
    suspend fun listModels(): List<AIModel>
    suspend fun testConnection()
    /** Arms or disarms engagement mode (arming arrives only after explicit consent). */
    suspend fun setEngagement(state: EngagementState)
    /** Restarts the Flipper. The app reconnects on its own afterwards. */
    suspend fun restartDevice()
    /** Starts installing the newest release of the detected firmware in the background. */
    suspend fun startFirmwareUpdate(): String
    suspend fun firmwareUpdateStatus(): String
    suspend fun cancelFirmwareUpdate(): String
}
