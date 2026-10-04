package com.boxagent.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import com.boxagent.app.ui.components.CircleIconButton
import com.boxagent.app.ui.components.ListGroup
import com.boxagent.app.ui.components.ListRow
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
import com.boxagent.app.daemon.AutoOutcome
import com.boxagent.app.daemon.ShellState
import com.boxagent.app.service.A11yService
import com.boxagent.app.ui.components.BwCard
import com.boxagent.app.ui.components.MonoText
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.SectionLabel
import com.boxagent.app.ui.components.StatusPill
import com.boxagent.app.ui.components.bwTextFieldColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun StatusScreen(app: BoxAgentApp) {
    val st by app.daemon.status.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showPair by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var showAuto by remember { mutableStateOf(false) }
    var autoStep by remember { mutableStateOf("") }
    var autoJob by remember { mutableStateOf<Job?>(null) }
    var pairHost by remember { mutableStateOf("127.0.0.1") }
    var pairPort by remember { mutableStateOf("") }
    var connectHint by remember { mutableStateOf<String?>(null) }
    var showGuide by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<String?>(null) }

    /** Shared ladder runner: drives the auto dialog and routes its
     *  outcome — online → done, needs-pair → prefilled pair dialog,
     *  nothing found → manual entry. */
    fun runLadder(afterPair: Boolean) {
        showAuto = true
        autoStep = ctx.getString(R.string.auto_step_reconnect)
        autoJob = scope.launch {
            val sink = com.boxagent.app.daemon.AutoStepSink { k, arg ->
                autoStep = when (k) {
                    "try" -> ctx.getString(R.string.auto_step_try, arg)
                    "scan" -> ctx.getString(R.string.auto_step_scan)
                    else -> ctx.getString(R.string.auto_step_reconnect)
                }
            }
            val out = if (afterPair) app.daemon.connectAfterPair(sink)
            else app.daemon.autoConnect(sink)
            showAuto = false
            when (out) {
                AutoOutcome.Online -> {}
                is AutoOutcome.NeedsPair -> {
                    pairHost = out.host
                    pairPort = out.port.toString()
                    showPair = true
                }
                AutoOutcome.Manual -> {
                    connectHint = ctx.getString(R.string.auto_none)
                    showConnect = true
                }
            }
        }
    }

    if (showGuide) {
        PermissionsGuideScreen(app, onBack = { showGuide = false })
        return
    }
    if (showLogs) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), { showLogs = false })
                Text(stringResource(R.string.audit_log), style = MaterialTheme.typography.titleLarge)
            }
            Box(Modifier.weight(1f)) { LogsScreen(app) }
        }
        return
    }

    val a11y = A11yService.isGranted(ctx)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ListGroup(header = stringResource(R.string.capabilities)) {
            CapabilityRow(
                Icons.Rounded.Terminal, stringResource(R.string.shell_daemon), st.shell == ShellState.ONLINE,
                when (st.shell) {
                    ShellState.ONLINE ->
                        if (st.daemonVersion.isEmpty()) "uid 2000"
                        else "uid 2000 · v${st.daemonVersion}"
                    ShellState.PAIRING -> stringResource(R.string.d_pairing)
                    ShellState.CONNECTING -> stringResource(R.string.d_connecting)
                    ShellState.ERROR -> daemonDetail(st.detail)
                    else -> stringResource(R.string.d_offline)
                },
                divider = true,
            )
            CapabilityRow(
                Icons.Rounded.Accessibility, stringResource(R.string.accessibility), a11y,
                stringResource(if (a11y) R.string.status_enabled else R.string.status_off),
                divider = true,
            )
            CapabilityRow(
                Icons.Rounded.Favorite, stringResource(R.string.keep_alive), true,
                stringResource(R.string.keep_alive_detail),
            )
        }

        ListGroup(header = stringResource(R.string.actions)) {
            ListRow(stringResource(R.string.reconnect), icon = Icons.Rounded.Sync, divider = true,
                onClick = { scope.launch { app.daemon.reconnect() } })
            ListRow(stringResource(R.string.re_pair), icon = Icons.Rounded.Link, divider = true,
                onClick = { showPair = true })
            ListRow(stringResource(R.string.connect), icon = Icons.Rounded.PlayArrow, divider = true,
                onClick = { if (autoJob?.isActive != true) runLadder(afterPair = false) })
            ListRow(stringResource(R.string.enable_a11y), icon = Icons.Rounded.Accessibility, divider = true,
                onClick = {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            ListRow(stringResource(R.string.battery), icon = Icons.Rounded.BatteryChargingFull, divider = true,
                onClick = {
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                })
            ListRow(stringResource(R.string.probe), icon = Icons.Rounded.Terminal, divider = true,
                onClick = {
                    scope.launch {
                        probe = runCatching {
                            app.daemon.requireClient().exec("id; uname -a").stdout
                        }.getOrElse { it.message ?: ctx.getString(R.string.error_generic) }
                    }
                })
            ListRow(stringResource(R.string.guide_title), icon = Icons.AutoMirrored.Rounded.MenuBook,
                onClick = { showGuide = true })
        }

        ListGroup {
            ListRow(
                stringResource(R.string.audit_log),
                subtitle = stringResource(R.string.audit_log_desc),
                icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                onClick = { showLogs = true },
            ) { Chevron() }
        }

        st.detail.takeIf { it.isNotEmpty() }?.let {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel(stringResource(R.string.detail))
                MonoText(daemonDetail(it))
            }
        }
        probe?.let {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel(stringResource(R.string.probe_output))
                MonoText(it)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(stringResource(R.string.how_it_works))
            Text(
                stringResource(R.string.how_it_works_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.height(12.dp))
    }

    if (showAuto) {
        AlertDialog(
            onDismissRequest = {
                autoJob?.cancel()
                showAuto = false
            },
            title = { Text(stringResource(R.string.auto_title)) },
            text = { Text(autoStep, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {},
            dismissButton = {
                PillButton(stringResource(R.string.cancel), filled = false, onClick = {
                    autoJob?.cancel()
                    showAuto = false
                })
            },
        )
    }
    if (showPair) {
        PairDialog(
            initialHost = pairHost,
            initialPort = pairPort,
            onPair = { host, port, code ->
                scope.launch {
                    val r = app.daemon.pair(
                        host.trim(), port.trim().toIntOrNull() ?: 0, code.filter { it.isDigit() },
                    )
                    showPair = false
                    // Pairing flips to a connect endpoint — climb the rest
                    // of the ladder automatically.
                    if (r.isSuccess) runLadder(afterPair = true)
                }
            },
            onDismiss = { showPair = false },
        )
    }
    if (showConnect) {
        ConnectDialog(
            hint = connectHint,
            onConnect = { host, port ->
                scope.launch {
                    app.daemon.connectAndSpawn(host.trim(), port.trim().toIntOrNull() ?: 0)
                    showConnect = false
                }
            },
            onDismiss = { showConnect = false },
        )
    }
}

@Composable
private fun CapabilityRow(icon: ImageVector, name: String, on: Boolean, detail: String, divider: Boolean = false) {
    ListRow(name, subtitle = detail, icon = icon, divider = divider) {
        com.boxagent.app.ui.components.StatusDot(on, size = 10.dp)
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.Rounded.ChevronRight, null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
fun PairDialog(
    initialHost: String = "127.0.0.1",
    initialPort: String = "",
    onPair: (host: String, port: String, code: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
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
                PillButton(stringResource(R.string.open_wireless_debugging), filled = false, onClick = {
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                })
                TextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.host)) }, colors = bwTextFieldColors())
                TextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.port)) }, colors = bwTextFieldColors())
                TextField(value = code, onValueChange = { code = it }, label = { Text(stringResource(R.string.pairing_code)) }, colors = bwTextFieldColors())
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
fun ConnectDialog(
    hint: String? = null,
    onConnect: (host: String, port: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connect_spawn_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Text(
                    stringResource(R.string.connect_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.host)) }, colors = bwTextFieldColors())
                TextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.port)) }, colors = bwTextFieldColors())
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
        "daemon_upgrade" -> R.string.d_daemon_upgrade
        "pair_failed" -> R.string.d_pair_failed
        "spawn_failed" -> R.string.d_spawn_failed
        else -> return detail
    }
    return stringResource(res)
}
