package com.flipperhero.app.platform

import android.content.Context
import com.flipperhero.agent.AIProvider
import com.flipperhero.agent.ApprovalSettings
import com.flipperhero.agent.EngagementState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the app's approval/preference flags and provider choice (NOT credentials — see
 * [SecretStore]). Engagement mode is deliberately session-only and never persisted: a restart
 * always returns to disarmed (mirrors the iOS invariant).
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("flipperhero_settings", Context.MODE_PRIVATE)

    // Session-only engagement arming.
    private val engagementFlow = MutableStateFlow(EngagementState.inactive)
    val engagement: StateFlow<EngagementState> = engagementFlow.asStateFlow()

    private val changeFlow = MutableStateFlow(0)
    /** Bumps whenever a persisted flag changes, so the UI can recompose. */
    val revision: StateFlow<Int> = changeFlow.asStateFlow()

    var yolo: Boolean
        get() = prefs.getBoolean("yolo", false)
        set(v) = put("yolo", v)
    var yoloAsksAfterReading: Boolean
        get() = prefs.getBoolean("yolo_asks_after_reading", true)
        set(v) = put("yolo_asks_after_reading", v)
    var autoApproveMedium: Boolean
        get() = prefs.getBoolean("auto_approve_medium", false)
        set(v) = put("auto_approve_medium", v)
    var readAloud: Boolean
        get() = prefs.getBoolean("read_aloud", false)
        set(v) = put("read_aloud", v)
    var autoConnect: Boolean
        get() = prefs.getBoolean("auto_connect", false)
        set(v) = put("auto_connect", v)
    var provider: String
        get() = prefs.getString("provider", AIProvider.OPENROUTER.id) ?: AIProvider.OPENROUTER.id
        set(v) = put("provider", v)
    var model: String
        get() = prefs.getString("model", AIProvider.OPENROUTER.defaultModel) ?: ""
        set(v) = put("model", v)
    var apiBaseURL: String
        get() = prefs.getString("api_base_url", AIProvider.OPENROUTER.baseURL) ?: AIProvider.OPENROUTER.baseURL
        set(v) = put("api_base_url", v)
    var lastDeviceAddress: String?
        get() = prefs.getString("last_device", null)
        set(v) = prefs.edit().apply { if (v == null) remove("last_device") else putString("last_device", v) }.apply()

    fun setEngagement(state: EngagementState) { engagementFlow.value = state }

    /** The pure-agent view of the current approval settings, including session engagement. */
    fun approvalSettings(): ApprovalSettings = ApprovalSettings(
        autoApproveMedium = autoApproveMedium,
        yolo = yolo,
        yoloAsksAfterUntrusted = yoloAsksAfterReading,
        engagement = engagementFlow.value,
    )

    private fun put(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); bump() }
    private fun put(key: String, value: String) { prefs.edit().putString(key, value).apply(); bump() }
    private fun bump() { changeFlow.value = changeFlow.value + 1 }
}
