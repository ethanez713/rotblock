package com.ezhou.rotblock.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBlockSheet(
    existing: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (packageName: String, label: String, quotaSeconds: Int?, windowSeconds: Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember { mutableStateOf<InstalledApp?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val app = selected
        if (app == null) {
            AppPicker(existing = existing, onPick = { selected = it })
        } else {
            RuleEditor(
                app = app,
                onBack = { selected = null },
                onConfirm = { quota, window -> onAdd(app.packageName, app.label, quota, window) },
            )
        }
    }
}

@Composable
private fun AppPicker(existing: Set<String>, onPick: (InstalledApp) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState(initialValue = emptyList<InstalledApp>(), context) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context.packageManager) }
    }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Column(Modifier.padding(horizontal = 20.dp).heightIn(max = 560.dp)) {
        Text("Pick an app", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                val already = app.packageName in existing
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !already) { onPick(app) }
                        .padding(vertical = 10.dp),
                ) {
                    app.icon?.let {
                        Image(bitmap = it, contentDescription = null, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        if (already) {
                            Text(
                                "Already has a policy",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Preset quotas, in seconds, plus a hard block. The 3-min-an-hour case is the default. */
private val QUOTA_PRESETS = listOf<Pair<String, Int?>>(
    "Block completely" to null,
    "3 min" to 180,
    "5 min" to 300,
    "10 min" to 600,
    "20 min" to 1_200,
)

private val WINDOW_PRESETS = listOf(
    "per hour" to 3_600,
    "per 30 min" to 1_800,
    "per day" to 86_400,
)

@Composable
private fun RuleEditor(
    app: InstalledApp,
    onBack: () -> Unit,
    onConfirm: (quotaSeconds: Int?, windowSeconds: Int) -> Unit,
) {
    var quotaSeconds by remember { mutableStateOf<Int?>(180) }
    var windowSeconds by remember { mutableStateOf(3_600) }
    var customMinutes by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            app.icon?.let {
                Image(bitmap = it, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                app.label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(20.dp))

        Text("How much?", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            QUOTA_PRESETS.take(3).forEach { (label, seconds) ->
                FilterChip(
                    selected = quotaSeconds == seconds,
                    onClick = { quotaSeconds = seconds; customMinutes = "" },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            QUOTA_PRESETS.drop(3).forEach { (label, seconds) ->
                FilterChip(
                    selected = quotaSeconds == seconds,
                    onClick = { quotaSeconds = seconds; customMinutes = "" },
                    label = { Text(label) },
                )
            }
            OutlinedTextField(
                value = customMinutes,
                onValueChange = { text ->
                    customMinutes = text.filter { it.isDigit() }.take(4)
                    quotaSeconds = customMinutes.toIntOrNull()?.times(60)
                },
                label = { Text("min") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                modifier = Modifier.width(110.dp),
            )
        }

        if (quotaSeconds != null) {
            Spacer(Modifier.height(20.dp))
            Text("Out of every", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WINDOW_PRESETS.forEach { (label, seconds) ->
                    FilterChip(
                        selected = windowSeconds == seconds,
                        onClick = { windowSeconds = seconds },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "This is a rolling window, not a clock hour. Time comes back gradually as old " +
                    "usage ages out, so there is no top-of-the-hour refill to wait around for.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Button(
                onClick = { onConfirm(quotaSeconds, windowSeconds) },
                enabled = quotaSeconds == null || (quotaSeconds ?: 0) > 0,
            ) { Text("Add block") }
        }
    }
}

private fun loadLaunchableApps(pm: PackageManager): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved
        .distinctBy { it.activityInfo.packageName }
        .mapNotNull { info ->
            runCatching {
                InstalledApp(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm).toBitmap(96, 96).asImageBitmap(),
                )
            }.getOrNull()
        }
        .sortedBy { it.label.lowercase() }
}
