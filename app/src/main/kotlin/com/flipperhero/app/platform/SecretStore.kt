package com.flipperhero.app.platform

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Credential storage. Keys never reach the agent — the only exposure is "is one stored"
 * (AGENTS.md: credentials are out of reach). No tool reads or writes these.
 */
interface SecretStore {
    fun apiKey(provider: String): String?
    fun setApiKey(provider: String, key: String?)
    fun hasApiKey(provider: String): Boolean
}

/**
 * Android Keystore-backed implementation via EncryptedSharedPreferences. Works on GrapheneOS with
 * no Google Play Services — the key material lives in the hardware-backed AndroidKeyStore.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "flipperhero_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun key(provider: String) = "api_key_$provider"

    override fun apiKey(provider: String): String? = prefs.getString(key(provider), null)?.ifEmpty { null }

    override fun setApiKey(provider: String, key: String?) {
        prefs.edit().apply {
            if (key.isNullOrEmpty()) remove(key(provider)) else putString(key(provider), key)
        }.apply()
    }

    override fun hasApiKey(provider: String): Boolean = !prefs.getString(key(provider), null).isNullOrEmpty()
}
