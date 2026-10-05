package com.boxagent.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as OsSettings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
import com.boxagent.app.bridge.Core
import com.boxagent.app.data.ConfirmPolicy
import com.boxagent.app.data.LlmProfile
import com.boxagent.app.data.Settings
import com.boxagent.app.llm.ModelFetcher
import com.boxagent.app.llm.ModelsDev
import com.boxagent.app.llm.CatalogModel
import com.boxagent.app.llm.CatalogProvider
import com.boxagent.app.ui.components.BwCard
import com.boxagent.app.ui.components.BwSwitch
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.SectionLabel
import com.boxagent.app.ui.components.bwTextFieldColors
import com.boxagent.app.ui.components.MonoText
import com.boxagent.app.util.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val LOCAL_URL = "http://127.0.0.1:8080/v1"

@Composable
fun SettingsScreen(app: BoxAgentApp) {
    val scope = rememberCoroutineScope()
    val s = app.settings

    val policy by s.confirmPolicy.collectAsState(initial = ConfirmPolicy.BALANCED)
    val keepWatchdog by s.keepWatchdog.collectAsState(initial = true)
    val theme by s.theme.collectAsState(initial = "system")

    var apiKey by remember { mutableStateOf(app.secrets.apiKey) }
    var url by remember { mutableStateOf("") }
    var mdl by remember { mutableStateOf("") }
    var tempStr by remember { mutableStateOf("") }
    var tokStr by remember { mutableStateOf("") }
    var sysPrompt by remember { mutableStateOf("") }
    var stepsStr by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }

    // Draft lifecycle: fill fields once the first DataStore frame arrives.
    // `loaded` gates the dispose-commit — a still-empty early draft must
    // never overwrite persisted settings on a quick tab switch.
    var loaded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        url = s.baseUrl.first()
        mdl = s.model.first()
        tempStr = s.temperature.first().toString()
        tokStr = s.maxTokens.first().toString()
        sysPrompt = s.systemPrompt.first()
        stepsStr = s.maxSteps.first().toString()
        loaded = true
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (!loaded) return@onDispose
            val u = url
            val m = mdl
            val t = tempStr.toDoubleOrNull() ?: 0.2
            val tok = tokStr.toIntOrNull() ?: 4096
            val prompt = sysPrompt.trim()
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                s.setLlm(u, m, t, tok)
                s.setSystemPrompt(prompt)
            }
        }
    }
    var showPresetDialog by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsErr by remember { mutableStateOf<String?>(null) }
    var showModelDialog by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var modelInfo by remember { mutableStateOf<Map<String, CatalogModel>>(emptyMap()) }
    var showCatalog by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var catalogProviders by remember { mutableStateOf<List<CatalogProvider>?>(null) }
    var catalogErr by remember { mutableStateOf<String?>(null) }
    val customs by s.customProviders.collectAsState(initial = emptyList())

    // Auto-fetch the model list whenever the endpoint or key changes; fall
    // back to the models.dev catalog when the endpoint has no /models.
    androidx.compose.runtime.LaunchedEffect(url, apiKey, refreshTick) {
        modelsErr = null
        if (url.isBlank()) {
            models = emptyList(); modelInfo = emptyMap()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(800) // debounce typing
        modelsLoading = true
        runCatching { ModelFetcher.list(url, apiKey) }
            .onSuccess {
                models = it
                modelInfo = ModelsDev.peekProviderForUrl(url)
                    ?.models?.associateBy { m -> m.id } ?: emptyMap()
            }
            .onFailure { e ->
                val cp = runCatching { ModelsDev.providerForUrl(app, url) }.getOrNull()
                if (cp != null) {
                    models = cp.models.map { it.id }
                    modelInfo = cp.models.associateBy { it.id }
                } else {
                    modelsErr = e.message
                    modelInfo = emptyMap()
                }
            }
        modelsLoading = false
    }

    if (showGuide) {
        PermissionsGuideScreen(app, onBack = { showGuide = false })
        return
    }

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
                    PillButton(stringResource(R.string.browse_providers),
                        filled = false, onClick = {
                            showCatalog = true
                            if (catalogProviders == null) {
                                scope.launch {
                                    catalogErr = null
                                    runCatching { ModelsDev.catalog(app) }
                                        .onSuccess { catalogProviders = it }
                                        .onFailure { catalogErr = it.message }
                                }
                            }
                        })
                    PillButton("Local", filled = false, onClick = {
                        url = LOCAL_URL; mdl = "local-model"
                        scope.launch {
                            s.setLlm(LOCAL_URL, "local-model",
                                tempStr.toDoubleOrNull() ?: 0.2,
                                tokStr.toIntOrNull() ?: 4096)
                        }
                    })
                    PillButton("+ " + stringResource(R.string.preset_label),
                        filled = false, onClick = { showPresetDialog = true })
                }
                if (customs.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        customs.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PillButton(p.name, filled = false, onClick = {
                                    url = p.baseUrl; mdl = p.model
                                    scope.launch {
                                        s.setLlm(p.baseUrl, p.model,
                                            tempStr.toDoubleOrNull() ?: 0.2,
                                            tokStr.toIntOrNull() ?: 4096)
                                    }
                                })
                                Text(
                                    "×",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable {
                                            scope.launch { s.removeCustomProvider(p.name) }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                SettingField(stringResource(R.string.api_key), apiKey, {
                    apiKey = it
                    // Write-through: the agent reads secrets.apiKey when a
                    // run starts, so the key must survive a tab switch even
                    // without an explicit Save.
                    app.secrets.apiKey = it
                }, secret = true)
                SettingField(stringResource(R.string.base_url), url, { url = it })
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingField(stringResource(R.string.model), mdl, { mdl = it },
                        Modifier.weight(1f))
                    PillButton(
                        when {
                            modelsLoading -> "…"
                            models.isNotEmpty() ->
                                stringResource(R.string.models_count, models.size) + " ▾"
                            else -> stringResource(R.string.models)
                        },
                        filled = false,
                        onClick = { showModelDialog = true },
                    )
                }
                modelsErr?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingField(stringResource(R.string.temperature), tempStr, { tempStr = it },
                        Modifier.weight(1f))
                    SettingField(stringResource(R.string.max_tokens), tokStr, { tokStr = it },
                        Modifier.weight(1f))
                }
                SettingField(stringResource(R.string.system_prompt), sysPrompt, { sysPrompt = it }, lines = 4)
                Text(
                    stringResource(R.string.system_prompt_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val compactTools by s.compactTools.collectAsState(initial = true)
                ToggleRow(stringResource(R.string.compact_tools), compactTools) {
                    scope.launch { s.setCompactTools(it) }
                }
                Text(
                    stringResource(R.string.compact_tools_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val vision by s.vision.collectAsState(initial = false)
                ToggleRow(stringResource(R.string.vision), vision) {
                    scope.launch { s.setVision(it) }
                }
                Text(
                    stringResource(R.string.vision_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val learn by s.learnSkills.collectAsState(initial = true)
                ToggleRow(stringResource(R.string.learn_skills), learn) {
                    scope.launch { s.setLearnSkills(it) }
                }
                Text(
                    stringResource(R.string.learn_skills_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.save), onClick = {
                        app.secrets.apiKey = apiKey
                        scope.launch {
                            s.setLlm(url, mdl,
                                tempStr.toDoubleOrNull() ?: 0.2,
                                tokStr.toIntOrNull() ?: 4096)
                            s.setSystemPrompt(sysPrompt.trim())
                        }
                    })
                    PillButton(stringResource(R.string.test), filled = false, onClick = {
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

        SectionLabel(stringResource(R.string.safety))
        BwCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.confirm_policy),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfirmPolicy.entries.forEach { p ->
                        PillButton(
                            policyName(p),
                            filled = policy == p,
                            onClick = { scope.launch { s.setConfirmPolicy(p) } },
                        )
                    }
                }
                Text(
                    stringResource(
                        when (policy) {
                            ConfirmPolicy.STRICT -> R.string.policy_strict_desc
                            ConfirmPolicy.BALANCED -> R.string.policy_balanced_desc
                            ConfirmPolicy.AUTONOMOUS -> R.string.policy_autonomous_desc
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingField(stringResource(R.string.step_cap), stepsStr, { stepsStr = it })
                PillButton(stringResource(R.string.save_limits), filled = false, onClick = {
                    scope.launch { s.setMaxSteps(stepsStr.toIntOrNull() ?: 40) }
                })
            }
        }

        SectionLabel(stringResource(R.string.runtime))
        BwCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleRow(stringResource(R.string.daemon_watchdog), keepWatchdog) {
                    scope.launch { s.setKeepWatchdog(it) }
                }
                Text(
                    stringResource(R.string.watchdog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val ctx = LocalContext.current
                val floatStop by s.floatStop.collectAsState(initial = true)
                var overlayOk by remember {
                    mutableStateOf(OsSettings.canDrawOverlays(ctx))
                }
                // Grant happens in system settings — refresh on return.
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val obs = LifecycleEventObserver { _, e ->
                        if (e == Lifecycle.Event.ON_RESUME) {
                            overlayOk = OsSettings.canDrawOverlays(ctx)
                        }
                    }
                    owner.lifecycle.addObserver(obs)
                    onDispose { owner.lifecycle.removeObserver(obs) }
                }
                val openOverlayPerm = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                OsSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + ctx.packageName),
                            ),
                        )
                    }
                    Unit
                }
                ToggleRow(stringResource(R.string.float_stop), floatStop) { want ->
                    scope.launch { s.setFloatStop(want) }
                    if (want && !overlayOk) openOverlayPerm()
                }
                Text(
                    stringResource(R.string.float_stop_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (floatStop && !overlayOk) {
                    Text(
                        stringResource(R.string.float_stop_perm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = openOverlayPerm),
                    )
                }
                val vsEnabled by s.vscreen.collectAsState(initial = false)
                val vsStatus by app.vscreen.status.collectAsState()
                ToggleRow(stringResource(R.string.vscreen), vsEnabled) { want ->
                    scope.launch { app.vscreen.setEnabled(want) }
                }
                Text(
                    stringResource(R.string.vscreen_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (vsEnabled) {
                    var wStr by remember { mutableStateOf("") }
                    var hStr by remember { mutableStateOf("") }
                    var dpiStr by remember { mutableStateOf("") }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        wStr = s.vscreenW.first().takeIf { it > 0 }?.toString() ?: ""
                        hStr = s.vscreenH.first().takeIf { it > 0 }?.toString() ?: ""
                        dpiStr = s.vscreenDpi.first().takeIf { it > 0 }?.toString() ?: ""
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingField(
                            stringResource(R.string.vscreen_w), wStr, { wStr = it },
                            Modifier.weight(1f),
                        )
                        SettingField(
                            stringResource(R.string.vscreen_h), hStr, { hStr = it },
                            Modifier.weight(1f),
                        )
                        SettingField(
                            stringResource(R.string.vscreen_dpi), dpiStr, { dpiStr = it },
                            Modifier.weight(1f),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillButton(stringResource(R.string.vscreen_apply), filled = false, onClick = {
                            scope.launch {
                                s.setVscreenSize(
                                    wStr.toIntOrNull() ?: 0,
                                    hStr.toIntOrNull() ?: 0,
                                    dpiStr.toIntOrNull() ?: 0,
                                )
                                // The host resizes the live display in place —
                                // the agent's open apps survive.
                                app.vscreen.ready()
                            }
                        })
                        Text(
                            stringResource(
                                when (vsStatus.state) {
                                    com.boxagent.app.vscreen.VState.OFF ->
                                        R.string.vscreen_state_off
                                    com.boxagent.app.vscreen.VState.STARTING ->
                                        R.string.vscreen_state_starting
                                    com.boxagent.app.vscreen.VState.READY ->
                                        R.string.vscreen_state_ready
                                    com.boxagent.app.vscreen.VState.ERROR ->
                                        R.string.vscreen_state_error
                                },
                            ) + if (vsStatus.detail.isEmpty()) "" else " · " + vsStatus.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (vsStatus.state == com.boxagent.app.vscreen.VState.ERROR)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.appearance))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "system" to R.string.theme_system,
                "light" to R.string.theme_light,
                "dark" to R.string.theme_dark,
            ).forEach { (t, label) ->
                PillButton(stringResource(label), filled = theme == t, onClick = {
                    scope.launch { s.setTheme(t) }
                })
            }
        }

        SectionLabel(stringResource(R.string.language))
        run {
            val ctx = LocalContext.current
            val lang by LocaleHelper.language.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocaleHelper.SUPPORTED.forEach { tag ->
                    PillButton(
                        when (tag) {
                            "en" -> "English"
                            "zh" -> "中文"
                            else -> stringResource(R.string.theme_system)
                        },
                        filled = lang == tag,
                        onClick = {
                            LocaleHelper.setLanguage(ctx, tag)
                            if (Build.VERSION.SDK_INT < 33) (ctx as? Activity)?.recreate()
                        },
                    )
                }
            }
        }

        SectionLabel(stringResource(R.string.onboarding))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.redo_setup), filled = false, onClick = {
                scope.launch { s.setOnboarded(false) }
            })
            PillButton(stringResource(R.string.guide_title), filled = false, onClick = {
                showGuide = true
            })
        }
    }

    if (showPresetDialog) {
        PresetDialog(
            onSave = { name ->
                scope.launch {
                    s.saveCustomProvider(LlmProfile(name = name, baseUrl = url, model = mdl))
                }
                showPresetDialog = false
            },
            onDismiss = { showPresetDialog = false },
        )
    }

    if (showCatalog) {
        ProviderDialog(
            providers = catalogProviders,
            error = catalogErr,
            onPick = { p ->
                url = p.api
                models = p.models.map { it.id }
                modelInfo = p.models.associateBy { it.id }
                modelsErr = null
                showCatalog = false
            },
            onRetry = {
                scope.launch {
                    catalogErr = null
                    runCatching { ModelsDev.catalog(app) }
                        .onSuccess { catalogProviders = it }
                        .onFailure { catalogErr = it.message }
                }
            },
            onDismiss = { showCatalog = false },
        )
    }

    if (showModelDialog) {
        ModelPickerDialog(
            models = models,
            info = modelInfo,
            loading = modelsLoading,
            error = modelsErr,
            onPick = { mdl = it; showModelDialog = false },
            onRetry = { refreshTick++ },
            onDismiss = { showModelDialog = false },
        )
    }
}

@Composable
private fun modelMeta(m: CatalogModel): String = buildString {
    if (m.name.isNotBlank() && m.name != m.id) append(m.name)
    if (m.context > 0) {
        if (isNotEmpty()) append(" · ")
        append(
            if (m.context >= 1_000_000) "${m.context / 1_000_000}M ctx"
            else if (m.context >= 1_000) "${m.context / 1_000}k ctx"
            else "${m.context} ctx"
        )
    }
    if (m.toolCall) {
        if (isNotEmpty()) append(" · ")
        append(stringResource(R.string.cap_tools))
    }
    if (m.reasoning) {
        if (isNotEmpty()) append(" · ")
        append(stringResource(R.string.cap_reasoning))
    }
}

private fun hostOf(api: String): String =
    runCatching { java.net.URI(api).host }.getOrNull() ?: api

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
        colors = bwTextFieldColors(),
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
        BwSwitch(checked = on, onToggle = onToggle)
    }
}

@Composable
private fun policyName(p: ConfirmPolicy): String = stringResource(
    when (p) {
        ConfirmPolicy.STRICT -> R.string.policy_strict
        ConfirmPolicy.BALANCED -> R.string.policy_balanced
        ConfirmPolicy.AUTONOMOUS -> R.string.policy_autonomous
    },
)

@Composable
private fun PresetDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_preset), style = MaterialTheme.typography.titleMedium) },
        text = {
            SettingField(stringResource(R.string.preset_name), name, { name = it })
        },
        confirmButton = {
            PillButton(stringResource(R.string.save), onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank())
        },
        dismissButton = {
            PillButton(stringResource(R.string.cancel), filled = false, onClick = onDismiss)
        },
    )
}

/**
 * Search rank for one query term against candidate fields:
 * 0 exact, 1 prefix, 2 substring, MAX_VALUE no match. Ranked results put
 * the obvious hit ("openai" -> OpenAI) above incidental substring matches.
 */
private fun matchScore(q: String, vararg fields: String): Int {
    var best = Int.MAX_VALUE
    for (f in fields) {
        val l = f.lowercase()
        best = minOf(best, when {
            l == q -> 0
            l.startsWith(q) -> 1
            l.contains(q) -> 2
            else -> Int.MAX_VALUE
        })
    }
    return best
}

/**
 * Rank providers for a query: exact/prefix/substring hits on id, name or api
 * host first, then providers merely carrying a matching model id.
 */
internal fun providerFilter(
    providers: List<CatalogProvider>,
    query: String,
): List<CatalogProvider> {
    val f = query.trim().lowercase()
    if (f.isEmpty()) return providers
    return providers.mapNotNull { p ->
        val s = matchScore(f, p.id, p.name, hostOf(p.api))
        val rank = when {
            s != Int.MAX_VALUE -> s
            p.models.any { it.id.lowercase().contains(f) } -> 3
            else -> return@mapNotNull null
        }
        p to rank
    }.sortedWith(compareBy({ it.second }, { it.first.name.lowercase() }))
        .map { it.first }
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    info: Map<String, CatalogModel>,
    loading: Boolean,
    error: String?,
    onPick: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.choose_model),
                style = MaterialTheme.typography.titleMedium)
        },
        text = {
            // Same IME fix as ProviderDialog: the search field scrolls with
            // the list so the top hit is never hidden under the field.
            val list = remember(models, info, q) {
                val f = q.trim().lowercase()
                if (f.isEmpty()) models
                else models.mapNotNull { id ->
                    val s = matchScore(f, id, info[id]?.name.orEmpty())
                    if (s == Int.MAX_VALUE) null else id to s
                }.sortedWith(compareBy({ it.second }, { it.first }))
                    .map { it.first }
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
            ) {
                item(key = "search") {
                    SettingField(stringResource(R.string.search), q, { q = it })
                }
                when {
                    loading && models.isEmpty() -> item {
                        Text(
                            "…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    models.isEmpty() && error != null -> {
                        item {
                            Text(
                                stringResource(R.string.models_error) + ": " + error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item {
                            PillButton(stringResource(R.string.retry),
                                filled = false, onClick = onRetry)
                        }
                    }
                    else -> {
                        if (list.isEmpty()) {
                            item {
                                Text(stringResource(R.string.models_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(list) { id ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(id) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(id, style = MaterialTheme.typography.titleMedium)
                                info[id]?.let {
                                    Text(modelMeta(it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            PillButton(stringResource(R.string.cancel), filled = false, onClick = onDismiss)
        },
    )
}

@Composable
private fun ProviderDialog(
    providers: List<CatalogProvider>?,
    error: String?,
    onPick: (CatalogProvider) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.choose_provider),
                style = MaterialTheme.typography.titleMedium)
        },
        text = {
            // The field lives inside the LazyColumn so the IME resize keeps it
            // visible without clipping the top-ranked result (an AlertDialog
            // scrolls its whole `text` block to bring a focused field into
            // view, which used to hide item 0 under the field).
            val list = remember(providers, q) {
                providers?.let { providerFilter(it, q) } ?: emptyList()
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
            ) {
                item(key = "search") {
                    SettingField(stringResource(R.string.search), q, { q = it })
                }
                when {
                    providers != null -> {
                        if (list.isEmpty()) {
                            item {
                                Text(stringResource(R.string.models_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(list, key = { it.id }) { p ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(p) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(p.name,
                                    style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.models_count, p.models.size) +
                                        " · " + hostOf(p.api),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    error != null -> {
                        item {
                            Text(
                                stringResource(R.string.models_error) + ": " + error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item {
                            PillButton(stringResource(R.string.retry),
                                filled = false, onClick = onRetry)
                        }
                    }
                    else -> item {
                        Text(
                            "…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            PillButton(stringResource(R.string.cancel), filled = false, onClick = onDismiss)
        },
    )
}
