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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
import com.boxagent.app.agent.ToolCatalog
import com.boxagent.app.agent.ToolSpec
import com.boxagent.app.ui.Hairline
import com.boxagent.app.ui.components.MonoText
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.StatusPill
import com.boxagent.app.ui.theme.BwShape
import kotlinx.coroutines.launch

@Composable
fun ToolsScreen(app: BoxAgentApp) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ToolSpec?>(null) }
    val tools = remember { ToolCatalog.all() }

    Column(Modifier.fillMaxSize()) {
        Surface(
            shape = BwShape.Pill,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(stringResource(R.string.search_tools), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val filtered = tools.filter {
            query.isEmpty() || it.name.contains(query, true) || it.summary.contains(query, true)
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            items(filtered, key = { it.name }) { tool ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = tool }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                " · ${tool.backend}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            tool.summary,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RiskBadge(tool.risk)
                }
                Hairline()
            }
        }
    }

    selected?.let { tool ->
        ToolDialog(app, tool, onDismiss = { selected = null })
    }
}

@Composable
private fun RiskBadge(risk: String) {
    Surface(
        shape = BwShape.Pill,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = if (risk == "destructive") MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
    ) {
        Text(
            riskLabel(risk),
            style = MaterialTheme.typography.labelSmall,
            color = if (risk == "destructive") MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ToolDialog(app: BoxAgentApp, tool: ToolSpec, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var args by remember { mutableStateOf(defaultArgs(tool)) }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val pending by app.toolRunner.pending.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tool.name, style = MaterialTheme.typography.titleMedium)
                RiskBadge(tool.risk)
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tool.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                MonoText(prettySchema(tool, stringResource(R.string.no_params)), Modifier.fillMaxWidth())
                TextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text(stringResource(R.string.args_json)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                )
                if (pending != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(stringResource(R.string.deny), filled = false, onClick = {
                            app.toolRunner.resolveConfirm(false, false)
                        })
                        PillButton(stringResource(R.string.approve), onClick = {
                            app.toolRunner.resolveConfirm(true, false)
                        })
                    }
                }
                result?.let {
                    MonoText(it, Modifier.fillMaxWidth(), maxLines = 30)
                }
            }
        },
        confirmButton = {
            PillButton(
            if (running) stringResource(R.string.running_btn)
            else stringResource(R.string.run),
            enabled = !running, onClick = {
                running = true
                result = null
                scope.launch {
                    result = runCatching {
                        org.json.JSONObject(app.toolRunner.execute(tool.name, args)).toString(2)
                    }.getOrElse { it.message ?: "error" }
                    running = false
                }
            })
        },
        dismissButton = { PillButton(stringResource(R.string.close), filled = false, onClick = onDismiss) },
    )
}

private fun prettySchema(tool: ToolSpec, noParams: String): String {
    val props = tool.parameters.optJSONObject("properties") ?: return noParams
    val sb = StringBuilder()
    val req = tool.parameters.optJSONArray("required")
    props.keys().forEach { k ->
        val p = props.getJSONObject(k)
        val required = req?.let { (0 until it.length()).any { i -> it.getString(i) == k } } == true
        sb.append("  $k: ${p.optString("type")}${if (required) " *" else ""} — ${p.optString("description")}\n")
    }
    return sb.toString().ifEmpty { noParams }
}

private fun defaultArgs(tool: ToolSpec): String {
    val props = tool.parameters.optJSONObject("properties") ?: return "{}"
    val req = tool.parameters.optJSONArray("required")
    val out = org.json.JSONObject()
    props.keys().forEach { k ->
        val required = req?.let { (0 until it.length()).any { i -> it.getString(i) == k } } == true
        if (required) {
            val type = props.getJSONObject(k).optString("type")
            out.put(
                k,
                when (type) {
                    "integer" -> 0
                    "boolean" -> false
                    else -> ""
                },
            )
        }
    }
    return out.toString()
}

@Composable
private fun riskLabel(risk: String): String = stringResource(
    when (risk) {
        "readonly" -> R.string.risk_readonly
        "destructive" -> R.string.risk_destructive
        else -> R.string.risk_moderate
    },
)
