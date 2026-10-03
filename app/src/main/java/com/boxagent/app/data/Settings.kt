package com.boxagent.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore(name = "boxagent_prefs")

enum class ConfirmPolicy { STRICT, BALANCED, AUTONOMOUS }

data class LlmProfile(
    val name: String = "Default",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.2,
    val maxTokens: Int = 4096,
)

class Settings(private val context: Context) {

    companion object {
        val KEY_BASE_URL = stringPreferencesKey("llm_base_url")
        val KEY_MODEL = stringPreferencesKey("llm_model")
        val KEY_TEMP = stringPreferencesKey("llm_temperature")
        val KEY_MAX_TOKENS = intPreferencesKey("llm_max_tokens")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val KEY_CONFIRM_POLICY = stringPreferencesKey("confirm_policy")
        val KEY_MAX_STEPS = intPreferencesKey("max_steps")
        val KEY_MAX_WALL_MS = stringPreferencesKey("max_wall_ms")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        val KEY_KEEP_WATCHDOG = booleanPreferencesKey("keep_watchdog")
        val KEY_PRIV_BACKEND = stringPreferencesKey("priv_backend")
        val KEY_DAEMON_SOCKET = stringPreferencesKey("daemon_socket")
        val KEY_ADB_HOST = stringPreferencesKey("adb_host")
        val KEY_ADB_PORT = intPreferencesKey("adb_port")
        val KEY_THEME = stringPreferencesKey("theme")

        const val DEFAULT_SYSTEM_PROMPT =
            "You are BoxAgent, an operator running on the user's Android phone. " +
            "You act only through the provided tools. Read before you act: use " +
            "ui_tree to see the screen, screenshot when coordinates are unclear. " +
            "Prefer small verifiable steps. For destructive operations, explain " +
            "what you are about to do first. Always respond in the same language " +
            "the user writes in. When finished, call task_done with a concise " +
            "summary."
    }

    val baseUrl: Flow<String> = context.prefs.data.map { it[KEY_BASE_URL] ?: LlmProfile().baseUrl }
    val model: Flow<String> = context.prefs.data.map { it[KEY_MODEL] ?: LlmProfile().model }
    val temperature: Flow<Double> =
        context.prefs.data.map { (it[KEY_TEMP] ?: "0.2").toDoubleOrNull() ?: 0.2 }
    val maxTokens: Flow<Int> = context.prefs.data.map { it[KEY_MAX_TOKENS] ?: 4096 }
    val systemPrompt: Flow<String> =
        context.prefs.data.map { it[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT }
    val confirmPolicy: Flow<ConfirmPolicy> = context.prefs.data.map {
        runCatching { ConfirmPolicy.valueOf(it[KEY_CONFIRM_POLICY] ?: "BALANCED") }
            .getOrDefault(ConfirmPolicy.BALANCED)
    }
    val maxSteps: Flow<Int> = context.prefs.data.map { it[KEY_MAX_STEPS] ?: 40 }
    val maxWallMs: Flow<Long> =
        context.prefs.data.map { (it[KEY_MAX_WALL_MS] ?: "600000").toLongOrNull() ?: 600_000L }
    val onboarded: Flow<Boolean> = context.prefs.data.map { it[KEY_ONBOARDED] ?: false }
    val keepWatchdog: Flow<Boolean> = context.prefs.data.map { it[KEY_KEEP_WATCHDOG] ?: true }
    val privBackend: Flow<String> = context.prefs.data.map { it[KEY_PRIV_BACKEND] ?: "builtin" }
    val daemonSocket: Flow<String> = context.prefs.data.map { it[KEY_DAEMON_SOCKET] ?: "" }
    val adbHost: Flow<String> = context.prefs.data.map { it[KEY_ADB_HOST] ?: "127.0.0.1" }
    val adbPort: Flow<Int> = context.prefs.data.map { it[KEY_ADB_PORT] ?: 0 }
    val theme: Flow<String> = context.prefs.data.map { it[KEY_THEME] ?: "system" }

    suspend fun setLlm(baseUrl: String, model: String, temperature: Double, maxTokens: Int) {
        context.prefs.edit {
            it[KEY_BASE_URL] = baseUrl
            it[KEY_MODEL] = model
            it[KEY_TEMP] = temperature.toString()
            it[KEY_MAX_TOKENS] = maxTokens
        }
    }

    suspend fun setSystemPrompt(v: String) = context.prefs.edit { it[KEY_SYSTEM_PROMPT] = v }
    suspend fun setConfirmPolicy(v: ConfirmPolicy) =
        context.prefs.edit { it[KEY_CONFIRM_POLICY] = v.name }
    suspend fun setMaxSteps(v: Int) = context.prefs.edit { it[KEY_MAX_STEPS] = v }
    suspend fun setMaxWallMs(v: Long) =
        context.prefs.edit { it[KEY_MAX_WALL_MS] = v.toString() }
    suspend fun setOnboarded(v: Boolean) = context.prefs.edit { it[KEY_ONBOARDED] = v }
    suspend fun setKeepWatchdog(v: Boolean) = context.prefs.edit { it[KEY_KEEP_WATCHDOG] = v }
    suspend fun setPrivBackend(v: String) = context.prefs.edit { it[KEY_PRIV_BACKEND] = v }
    suspend fun setDaemonSocket(v: String) = context.prefs.edit { it[KEY_DAEMON_SOCKET] = v }
    suspend fun setAdbEndpoint(host: String, port: Int) = context.prefs.edit {
        it[KEY_ADB_HOST] = host
        it[KEY_ADB_PORT] = port
    }
    suspend fun setTheme(v: String) = context.prefs.edit { it[KEY_THEME] = v }
}
