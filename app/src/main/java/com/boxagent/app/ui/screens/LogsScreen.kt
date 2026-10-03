package com.boxagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
import com.boxagent.app.ui.Hairline
import com.boxagent.app.ui.components.MonoText
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.StatusDot
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(app: BoxAgentApp) {
    val entries by app.db.audit().latest().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.US) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton(stringResource(R.string.export), filled = false, onClick = {
                scope.launch {
                    val all = app.db.audit().exportAll()
                    val json = org.json.JSONArray()
                    all.forEach { e ->
                        json.put(org.json.JSONObject()
                            .put("ts", e.createdAt)
                            .put("kind", e.kind)
                            .put("ok", e.ok)
                            .put("detail", e.detail))
                    }
                    val file = java.io.File(ctx.cacheDir, "boxagent-audit.json")
                    file.writeText(json.toString(2))
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", file,
                    )
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("application/json")
                            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            })
            PillButton(stringResource(R.string.clear), filled = false, onClick = {
                scope.launch { app.db.audit().clear() }
            })
            Text(
                pluralStringResource(R.plurals.logs_entry_count, entries.size, entries.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        Hairline()
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        ) {
            items(entries, key = { it.id }) { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(e.ok, Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.kind, style = MaterialTheme.typography.labelLarge)
                        MonoText(e.detail.take(160), maxLines = 3)
                    }
                    Text(
                        fmt.format(Date(e.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Hairline()
            }
        }
    }
}
