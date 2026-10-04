package com.boxagent.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

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
        val KEY_CUSTOM_PROVIDERS = stringPreferencesKey("custom_providers")
        val KEY_COMPACT_TOOLS = booleanPreferencesKey("compact_tools")

        val KEY_VISION = booleanPreferencesKey("vision")
        val KEY_LEARN_SKILLS = booleanPreferencesKey("learn_skills")
        val KEY_SEEDED_SKILLS = stringSetPreferencesKey("seeded_skills")

        /**
         * Built-in prompts older builds persisted as the "system prompt"
         * (Settings saved the default verbatim). The operating guide now
         * ships in the core; these mean "no custom instructions".
         */
        private val LEGACY_DEFAULT_PROMPTS = listOf(
            "You are BoxAgent, an operator running on the user's Android phone. " +
                "You act only through the provided tools. Read before you act: use " +
                "ui_tree to see the screen, screenshot when coordinates are unclear. " +
                "Prefer small verifiable steps. For destructive operations, explain " +
                "what you are about to do first. When finished, call task_done with " +
                "a concise summary.",
            "You are BoxAgent, an operator running on the user's Android phone. " +
                "You act only through the provided tools. Read before you act: use " +
                "ui_tree to see the screen, screenshot when coordinates are unclear. " +
                "Prefer small verifiable steps. For destructive operations, explain " +
                "what you are about to do first. Reuse recent tool results instead " +
                "of re-calling a tool when nothing changed; prefer ui_find over " +
                "ui_tree and ui_tree over screenshot when possible. Always respond " +
                "in the same language the user writes in. When finished, call " +
                "task_done with a concise summary.",
            "You are BoxAgent, an operator running on the user's Android phone. " +
                "You act only through the provided tools. Read before you act: use " +
                "ui_find or ui_tree to see the screen (you cannot see screenshot " +
                "images; node bounds give tap coordinates). " +
                "Prefer small verifiable steps. For destructive operations, explain " +
                "what you are about to do first. Reuse recent tool results instead " +
                "of re-calling a tool when nothing changed; prefer ui_find over " +
                "ui_tree when you know what to look for. Always respond " +
                "in the same language the user writes in. When finished, call " +
                "task_done with a concise summary.",
        ).map(::squash).toSet()

        private fun squash(s: String) = s.trim().replace(Regex("\\s+"), " ")

        /** Stored custom instructions, with legacy defaults read as empty. */
        fun customInstructions(stored: String?): String =
            stored?.takeIf { squash(it) !in LEGACY_DEFAULT_PROMPTS }?.trim().orEmpty()
    }

    /**
     * Reads degrade to defaults on a corrupt store rather than crashing
     * every collector (`CorruptionException` is an `IOException`; a write
     * repairs the file).
     */
    private val data: Flow<androidx.datastore.preferences.core.Preferences> =
        context.prefs.data.catch { e ->
            if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw e
        }

    val baseUrl: Flow<String> = data.map { it[KEY_BASE_URL] ?: LlmProfile().baseUrl }
    val model: Flow<String> = data.map { it[KEY_MODEL] ?: LlmProfile().model }
    val temperature: Flow<Double> =
        data.map { (it[KEY_TEMP] ?: "0.2").toDoubleOrNull() ?: 0.2 }
    val maxTokens: Flow<Int> = data.map { it[KEY_MAX_TOKENS] ?: 4096 }
    /** User's custom instructions, appended to the core's built-in
     *  operating guide (stored under the old "system_prompt" key). */
    val systemPrompt: Flow<String> =
        data.map { customInstructions(it[KEY_SYSTEM_PROMPT]) }
    val confirmPolicy: Flow<ConfirmPolicy> = data.map {
        runCatching { ConfirmPolicy.valueOf(it[KEY_CONFIRM_POLICY] ?: "BALANCED") }
            .getOrDefault(ConfirmPolicy.BALANCED)
    }
    val maxSteps: Flow<Int> = data.map { it[KEY_MAX_STEPS] ?: 40 }
    val maxWallMs: Flow<Long> =
        data.map { (it[KEY_MAX_WALL_MS] ?: "600000").toLongOrNull() ?: 600_000L }
    val onboarded: Flow<Boolean> = data.map { it[KEY_ONBOARDED] ?: false }
    val keepWatchdog: Flow<Boolean> = data.map { it[KEY_KEEP_WATCHDOG] ?: true }
    val privBackend: Flow<String> = data.map { it[KEY_PRIV_BACKEND] ?: "builtin" }
    val daemonSocket: Flow<String> = data.map { it[KEY_DAEMON_SOCKET] ?: "" }
    val adbHost: Flow<String> = data.map { it[KEY_ADB_HOST] ?: "127.0.0.1" }
    val adbPort: Flow<Int> = data.map { it[KEY_ADB_PORT] ?: 0 }
    val theme: Flow<String> = data.map { it[KEY_THEME] ?: "system" }
    /** Short tool schemas (summary instead of full description). */
    val compactTools: Flow<Boolean> =
        data.map { it[KEY_COMPACT_TOOLS] ?: true }
    /** Send marked screenshots to the model (vision-capable models only). */
    val vision: Flow<Boolean> = data.map { it[KEY_VISION] ?: false }
    /** Let the agent propose skills (saved as drafts for review). */
    val learnSkills: Flow<Boolean> = data.map { it[KEY_LEARN_SKILLS] ?: true }
    /** Built-in skill names seeded so far (deleted ones are not re-added). */
    val seededSkills: Flow<Set<String>> = data.map { it[KEY_SEEDED_SKILLS] ?: emptySet() }

    /** User-saved provider presets (name/baseUrl/model) as a JSON array. */
    val customProviders: Flow<List<LlmProfile>> = data.map { p ->
        runCatching {
            val arr = org.json.JSONArray(p[KEY_CUSTOM_PROVIDERS] ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LlmProfile(
                    name = o.optString("name"),
                    baseUrl = o.optString("base_url"),
                    model = o.optString("model"),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveCustomProvider(p: LlmProfile) = context.prefs.edit { prefs ->
        val arr = org.json.JSONArray(prefs[KEY_CUSTOM_PROVIDERS] ?: "[]")
        val out = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("name") != p.name) out.put(o)
        }
        out.put(
            org.json.JSONObject()
                .put("name", p.name)
                .put("base_url", p.baseUrl)
                .put("model", p.model)
        )
        prefs[KEY_CUSTOM_PROVIDERS] = out.toString()
    }

    suspend fun removeCustomProvider(name: String) = context.prefs.edit { prefs ->
        val arr = org.json.JSONArray(prefs[KEY_CUSTOM_PROVIDERS] ?: "[]")
        val out = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("name") != name) out.put(o)
        }
        prefs[KEY_CUSTOM_PROVIDERS] = out.toString()
    }

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
    suspend fun setCompactTools(v: Boolean) =
        context.prefs.edit { it[KEY_COMPACT_TOOLS] = v }
    suspend fun setVision(v: Boolean) = context.prefs.edit { it[KEY_VISION] = v }
    suspend fun setLearnSkills(v: Boolean) = context.prefs.edit { it[KEY_LEARN_SKILLS] = v }
    suspend fun setSeededSkills(v: Set<String>) = context.prefs.edit { it[KEY_SEEDED_SKILLS] = v }
}
