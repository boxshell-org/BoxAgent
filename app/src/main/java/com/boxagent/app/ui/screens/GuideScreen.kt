package com.boxagent.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
import com.boxagent.app.daemon.ShellState
import com.boxagent.app.service.A11yService
import com.boxagent.app.ui.components.BwCard
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.SectionLabel
import com.boxagent.app.ui.components.StatusDot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun notifGranted(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun batteryExempt(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(ctx.packageName)

/**
 * Complete permission guide: every capability the app needs, its live
 * status, why it exists, step-by-step instructions and a deep link into
 * the relevant system surface. Statuses re-poll every second so toggling
 * something in system settings flips the dot as soon as you return.
 */
@Composable
fun PermissionsGuideScreen(app: BoxAgentApp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by app.daemon.status.collectAsState()

    var a11yOn by remember { mutableStateOf(A11yService.isGranted(ctx)) }
    var notifOn by remember { mutableStateOf(notifGranted(ctx)) }
    var battOn by remember { mutableStateOf(batteryExempt(ctx)) }
    var showPair by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            a11yOn = A11yService.isGranted(ctx)
            notifOn = notifGranted(ctx)
            battOn = batteryExempt(ctx)
            delay(1000)
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notifOn = it }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.back),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { onBack() }
                .padding(vertical = 4.dp),
        )
        Text(
            stringResource(R.string.guide_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel(stringResource(R.string.guide_required))

        GuideItem(
            name = stringResource(R.string.perm_notif),
            granted = notifOn,
            desc = stringResource(R.string.perm_notif_desc),
            steps = stringResource(R.string.perm_notif_steps),
        ) {
            PillButton(stringResource(R.string.grant), filled = false, onClick = {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            })
        }

        GuideItem(
            name = stringResource(R.string.perm_adb),
            granted = st.shell == ShellState.ONLINE,
            desc = stringResource(R.string.perm_adb_desc),
            steps = stringResource(R.string.perm_adb_steps),
        ) {
            PillButton(stringResource(R.string.open_dev_settings), filled = false, onClick = {
                ctx.startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            })
            PillButton(stringResource(R.string.pair), filled = false, onClick = {
                showPair = true
            })
        }

        GuideItem(
            name = stringResource(R.string.perm_a11y),
            granted = a11yOn,
            desc = stringResource(R.string.perm_a11y_desc),
            steps = stringResource(R.string.perm_a11y_steps),
        ) {
            PillButton(stringResource(R.string.grant), filled = false, onClick = {
                ctx.startActivity(
                    Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            })
        }

        GuideItem(
            name = stringResource(R.string.perm_battery),
            granted = battOn,
            desc = stringResource(R.string.perm_battery_desc),
            steps = stringResource(R.string.perm_battery_steps),
        ) {
            PillButton(stringResource(R.string.grant), filled = false, onClick = {
                ctx.startActivity(
                    Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            })
        }

        SectionLabel(stringResource(R.string.guide_auto))
        BwCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AutoRow(stringResource(R.string.auto_net), stringResource(R.string.auto_net_desc))
                AutoRow(stringResource(R.string.auto_multicast), stringResource(R.string.auto_multicast_desc))
                AutoRow(stringResource(R.string.auto_packages), stringResource(R.string.auto_packages_desc))
                AutoRow(stringResource(R.string.auto_fgs), stringResource(R.string.auto_fgs_desc))
                AutoRow(stringResource(R.string.auto_boot), stringResource(R.string.auto_boot_desc))
                AutoRow(stringResource(R.string.auto_wakelock), stringResource(R.string.auto_wakelock_desc))
            }
        }
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
private fun GuideItem(
    name: String,
    granted: Boolean,
    desc: String,
    steps: String,
    actions: @Composable () -> Unit,
) {
    BwCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(granted)
                    Text(name, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 10.dp))
                }
                Text(
                    stringResource(
                        if (granted) R.string.perm_granted else R.string.perm_missing,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!granted) {
                Text(
                    steps,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions()
                }
            }
        }
    }
}

@Composable
private fun AutoRow(name: String, desc: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(true)
        Column(Modifier.padding(start = 10.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
