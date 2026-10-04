package com.boxagent.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Keystore-backed secret store: LLM API key + ADB identity (PKCS#8 PEM).
 * Values never enter logs — callers must redact before audit.
 */
class Secrets(context: Context) {

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            "boxagent_secrets",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String
        get() = prefs.getString(K_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(K_API_KEY, v).apply()

    var adbKeyPem: String
        get() = prefs.getString(K_ADB_PEM, "") ?: ""
        set(v) = prefs.edit().putString(K_ADB_PEM, v).apply()

    var daemonToken: String
        get() = prefs.getString(K_DAEMON_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(K_DAEMON_TOKEN, v).apply()

    var vscreenToken: String
        get() = prefs.getString(K_VSCREEN_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(K_VSCREEN_TOKEN, v).apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val K_API_KEY = "llm_api_key"
        const val K_ADB_PEM = "adb_key_pem"
        const val K_DAEMON_TOKEN = "daemon_token"
        const val K_VSCREEN_TOKEN = "vscreen_token"
    }
}
