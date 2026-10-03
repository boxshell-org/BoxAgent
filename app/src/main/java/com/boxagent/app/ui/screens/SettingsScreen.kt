package com.boxagent.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.bridge.Core
import com.boxagent.app.data.ConfirmPolicy
import com.boxagent.app.data.Settings
import com.boxagent.app.ui.Hairline
import com.boxagent.app.ui.components.BwCard
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.SectionLabel
import com.boxagent.app.ui.components.MonoText
import com.boxagent.app.ui.theme.BwShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val PRESETS = listOf(
    Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    Triple("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    Triple("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    Triple("Moonshot", "https://api.moonshot.ai/v1", "kimi-k2-0905-preview"),
    Triple("Local", "http://127.0.0.1:8080/v1", "local-model"),
)

@Composable
fun SettingsScreen(app: BoxAgentApp) {
    val scope = rememberCoroutineScope()
    val s = app.settings

    val baseUrl by s.baseUrl.collectAsState(initial = "")
    val model by s.model.collectAsState(initial = "")
    val temperature by s.temperature.collectAsState(initial = 0.2)
    val maxTokens by s.maxTokens.collectAsState(initial = 4096)
    val systemPrompt by s.systemPrompt.collectAsState(initial = "")
    val policy by s.confirmPolicy.collectAsState(initial = ConfirmPolicy.BALANCED)
    val maxSteps by s.maxSteps.collectAsState(initial = 40)
    val keepWatchdog by s.keepWatchdog.collectAsState(initial = true)
    val theme by s.theme.collectAsState(initial = "system")

    var apiKey by remember { mutableStateOf(app.secrets.apiKey) }
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var mdl by remember(model) { mutableStateOf(model) }
    var tempStr by remember(temperature) { mutableStateOf(temperature.toString()) }
    var tokStr by remember(maxTokens) { mutableStateOf(maxTokens.toString()) }
    var sysPrompt by remember(systemPrompt) { mutableStateOf(systemPrompt) }
    var stepsStr by remember(maxSteps) { mutableStateOf(maxSteps.toString()) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionLabel("LLM")
        BwCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.take(3).forEach { (name, u, m) ->
                        PillButton(name, filled = false, onClick = {
                            url = u; mdl = m
                            scope.launch { s.setLlm(u, m, tempStr.toDoubleOrNull() ?: 0.2, tokStr.toIntOrNull() ?: 4096) }
                        })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.drop(3).forEach { (name, u, m) ->
                        PillButton(name, filled = false, onClick = {
                            url = u; mdl = m
                            scope.launch { s.setLlm(u, m, tempStr.toDoubleOrNull() ?: 0.2, tokStr.toIntOrNull() ?: 4096) }
                        })
                    }
                }
                SettingField("API key", apiKey, { apiKey = it }, secret = true)
                SettingField("Base URL", url, { url = it })
                SettingField("Model", mdl, { mdl = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingField("Temperature", tempStr, { tempStr = it },
                        Modifier.weight(1f))
                    SettingField("Max tokens", tokStr, { tokStr = it },
                        Modifier.weight(1f))
                }
                SettingField("System prompt", sysPrompt, { sysPrompt = it }, lines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Save", onClick = {
                        app.secrets.apiKey = apiKey
                        scope.launch {
                            s.setLlm(url, mdl,
                                tempStr.toDoubleOrNull() ?: 0.2,
                                tokStr.toIntOrNull() ?: 4096)
                            s.setSystemPrompt(sysPrompt.ifEmpty { Settings.DEFAULT_SYSTEM_PROMPT })
                        }
                    })
                    PillButton("Test", filled = false, onClick = {
                        testResult = "…"
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) {
                                val cfg = JSONObject()
                                    .put("base_url", url)
                                    .put("api_key", apiKey)
                                    .put("model", mdl)
                                    .put("max_tokens", 8)
                                val r = JSONObject(Core.nativePingLlm(cfg.toString()))
                                if (r.optBoolean("ok"))
                                    "ok: " + r.getJSONObject("data").optString("response").take(80)
                                else "error: " + r.optString("error").take(160)
                            }
                        }
                    })
                }
                testResult?.let { MonoText(it) }
            }
        }

        SectionLabel("Safety")
        BwCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Confirmation policy",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfirmPolicy.entries.forEach { p ->
                        PillButton(
                            p.name.lowercase().replaceFirstChar { it.uppercase() },
                            filled = policy == p,
                            onClick = { scope.launch { s.setConfirmPolicy(p) } },
                        )
                    }
                }
                Text(
                    when (policy) {
                        ConfirmPolicy.STRICT -> "Every non-readonly tool asks first."
                        ConfirmPolicy.BALANCED -> "Only destructive tools ask first."
                        ConfirmPolicy.AUTONOMOUS -> "Nothing asks. The agent has full run."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingField("Step cap", stepsStr, { stepsStr = it })
                PillButton("Save limits", filled = false, onClick = {
                    scope.launch { s.setMaxSteps(stepsStr.toIntOrNull() ?: 40) }
                })
            }
        }

        SectionLabel("Runtime")
        BwCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleRow("Daemon watchdog", keepWatchdog) {
                    scope.launch { s.setKeepWatchdog(it) }
                }
                Text(
                    "Restarts the shell daemon if the connection drops; runs a " +
                    "15-minute health check while the app is backgrounded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionLabel("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system", "light", "dark").forEach { t ->
                PillButton(t.replaceFirstChar { it.uppercase() }, filled = theme == t, onClick = {
                    scope.launch { s.setTheme(t) }
                })
            }
        }

        SectionLabel("Onboarding")
        PillButton("Redo setup", filled = false, onClick = {
            scope.launch { s.setOnboarded(false) }
        })
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    lines: Int = 1,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        visualTransformation = if (secret)
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!on) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.Surface(
            shape = BwShape.Pill,
            color = if (on) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(2.dp),
        ) {
            Text(
                if (on) "On" else "Off",
                style = MaterialTheme.typography.labelMedium,
                color = if (on) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}
