package com.boxagent.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.daemon.ShellState
import com.boxagent.app.service.A11yService
import com.boxagent.app.ui.components.BwCard
import com.boxagent.app.ui.components.MonoText
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.SectionLabel
import com.boxagent.app.ui.components.StatusPill
import kotlinx.coroutines.launch

@Composable
fun StatusScreen(app: BoxAgentApp) {
    val st by app.daemon.status.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showPair by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionLabel("Capabilities")
        BwCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CapabilityRow("Shell daemon", st.shell == ShellState.ONLINE,
                    when (st.shell) {
                        ShellState.ONLINE -> "uid 2000"
                        ShellState.PAIRING -> "pairing…"
                        ShellState.CONNECTING -> "connecting…"
                        ShellState.ERROR -> st.detail
                        else -> "offline"
                    })
                CapabilityRow("Accessibility", A11yService.isEnabled,
                    if (A11yService.isEnabled) "enabled" else "off")
                CapabilityRow("Keep-alive", true, "foreground + watchdog")
            }
        }

        SectionLabel("Actions")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Reconnect", filled = false, onClick = {
                scope.launch { app.daemon.reconnect() }
            })
            PillButton("Re-pair", filled = false, onClick = { showPair = true })
            PillButton("Connect", filled = false, onClick = { showConnect = true })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Enable a11y", filled = false, onClick = {
                ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            PillButton("Battery", filled = false, onClick = {
                ctx.startActivity(
                    Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            })
            PillButton("Probe", filled = false, onClick = {
                scope.launch {
                    probe = runCatching {
                        app.daemon.requireClient().exec("id; uname -a").stdout
                    }.getOrElse { it.message ?: "error" }
                }
            })
        }

        st.detail.takeIf { it.isNotEmpty() }?.let {
            SectionLabel("Detail")
            MonoText(it)
        }
        probe?.let {
            SectionLabel("Probe output")
            MonoText(it)
        }

        SectionLabel("How it works")
        Text(
            "BoxAgent pairs with wireless debugging (Developer Options), pushes a " +
            "Rust daemon that runs with shell permissions, and drives UI through " +
            "accessibility. The assistant calls atomic tools through that stack.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showPair) {
        PairDialog(
            onPair = { host, port, code ->
                scope.launch {
                    app.daemon.pair(host, port.toIntOrNull() ?: 0, code)
                    showPair = false
                    showConnect = true
                }
            },
            onDismiss = { showPair = false },
        )
    }
    if (showConnect) {
        ConnectDialog(
            onConnect = { host, port ->
                scope.launch {
                    app.daemon.connectAndSpawn(host, port.toIntOrNull() ?: 0)
                    showConnect = false
                }
            },
            onDismiss = { showConnect = false },
        )
    }
}

@Composable
private fun CapabilityRow(name: String, on: Boolean, detail: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.boxagent.app.ui.components.StatusDot(on)
            Text(name, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 10.dp))
        }
        Text(detail, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PairDialog(onPair: (host: String, port: String, code: String) -> Unit, onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair device", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Developer Options → Wireless Debugging → “Pair with pairing code”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(value = host, onValueChange = { host = it }, label = { Text("Host") })
                TextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
                TextField(value = code, onValueChange = { code = it }, label = { Text("Pairing code") })
            }
        },
        confirmButton = {
            PillButton("Pair", onClick = { onPair(host, port, code) },
                enabled = port.isNotEmpty() && code.isNotEmpty())
        },
        dismissButton = { PillButton("Cancel", filled = false, onClick = onDismiss) },
    )
}

@Composable
fun ConnectDialog(onConnect: (host: String, port: String) -> Unit, onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect & spawn daemon", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Wireless Debugging main screen (not the pairing dialog) shows the connect port.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(value = host, onValueChange = { host = it }, label = { Text("Host") })
                TextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
            }
        },
        confirmButton = {
            PillButton("Connect", onClick = { onConnect(host, port) }, enabled = port.isNotEmpty())
        },
        dismissButton = { PillButton("Cancel", filled = false, onClick = onDismiss) },
    )
}
