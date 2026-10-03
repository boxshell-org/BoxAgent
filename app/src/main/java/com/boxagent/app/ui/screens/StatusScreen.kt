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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
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
        SectionLabel(stringResource(R.string.capabilities))
        BwCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CapabilityRow(stringResource(R.string.shell_daemon), st.shell == ShellState.ONLINE,
                    when (st.shell) {
                        ShellState.ONLINE -> "uid 2000"
                        ShellState.PAIRING -> stringResource(R.string.d_pairing)
                        ShellState.CONNECTING -> stringResource(R.string.d_connecting)
                        ShellState.ERROR -> daemonDetail(st.detail)
                        else -> stringResource(R.string.d_offline)
                    })
                CapabilityRow(stringResource(R.string.accessibility), A11yService.isEnabled,
                    stringResource(if (A11yService.isEnabled) R.string.status_enabled else R.string.status_off))
                CapabilityRow(stringResource(R.string.keep_alive), true,
                    stringResource(R.string.keep_alive_detail))
            }
        }

        SectionLabel(stringResource(R.string.actions))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.reconnect), filled = false, onClick = {
                scope.launch { app.daemon.reconnect() }
            })
            PillButton(stringResource(R.string.re_pair), filled = false, onClick = { showPair = true })
            PillButton(stringResource(R.string.connect), filled = false, onClick = { showConnect = true })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.enable_a11y), filled = false, onClick = {
                ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            PillButton(stringResource(R.string.battery), filled = false, onClick = {
                ctx.startActivity(
                    Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            })
            PillButton(stringResource(R.string.probe), filled = false, onClick = {
                scope.launch {
                    probe = runCatching {
                        app.daemon.requireClient().exec("id; uname -a").stdout
                    }.getOrElse { it.message ?: ctx.getString(R.string.error_generic) }
                }
            })
        }

        st.detail.takeIf { it.isNotEmpty() }?.let {
            SectionLabel(stringResource(R.string.detail))
            MonoText(daemonDetail(it))
        }
        probe?.let {
            SectionLabel(stringResource(R.string.probe_output))
            MonoText(it)
        }

        SectionLabel(stringResource(R.string.how_it_works))
        Text(
            stringResource(R.string.how_it_works_body),
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
        title = { Text(stringResource(R.string.pair_device), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.pair_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.host)) })
                TextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.port)) })
                TextField(value = code, onValueChange = { code = it }, label = { Text(stringResource(R.string.pairing_code)) })
            }
        },
        confirmButton = {
            PillButton(stringResource(R.string.pair), onClick = { onPair(host, port, code) },
                enabled = port.isNotEmpty() && code.isNotEmpty())
        },
        dismissButton = { PillButton(stringResource(R.string.cancel), filled = false, onClick = onDismiss) },
    )
}

@Composable
fun ConnectDialog(onConnect: (host: String, port: String) -> Unit, onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connect_spawn_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.connect_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.host)) })
                TextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.port)) })
            }
        },
        confirmButton = {
            PillButton(stringResource(R.string.connect), onClick = { onConnect(host, port) }, enabled = port.isNotEmpty())
        },
        dismissButton = { PillButton(stringResource(R.string.cancel), filled = false, onClick = onDismiss) },
    )
}

/** Map "key:*" status tokens emitted by DaemonManager to localized text. */
@Composable
private fun daemonDetail(detail: String): String {
    val res = when (detail.removePrefix("key:")) {
        "pairing" -> R.string.d_pairing
        "paired_pending" -> R.string.d_paired_pending
        "connecting_adb" -> R.string.d_connecting_adb
        "daemon_up" -> R.string.d_daemon_up
        "reconnected" -> R.string.d_reconnected
        "daemon_lost" -> R.string.d_daemon_lost
        "pair_failed" -> R.string.d_pair_failed
        "spawn_failed" -> R.string.d_spawn_failed
        else -> return detail
    }
    return stringResource(res)
}
