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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.policy.TargetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

private enum class BlockKind(val label: String) { App("App"), Website("Website"), Feed("Feed") }

/** What is being configured once a target has been chosen. */
private data class Chosen(
    val kind: TargetKind,
    val matcher: String,
    val label: String,
    val icon: ImageBitmap? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBlockSheet(
    existing: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (kind: TargetKind, matcher: String, label: String, quotaSeconds: Int?, windowSeconds: Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var kind by remember { mutableStateOf(BlockKind.App) }
    var chosen by remember { mutableStateOf<Chosen?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val target = chosen
        if (target != null) {
            RuleEditor(
                chosen = target,
                onBack = { chosen = null },
                onConfirm = { quota, window ->
                    onAdd(target.kind, target.matcher, target.label, quota, window)
                },
            )
            return@ModalBottomSheet
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                BlockKind.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = kind == entry,
                        onClick = { kind = entry },
                        shape = SegmentedButtonDefaults.itemShape(index, BlockKind.entries.size),
                        label = { Text(entry.label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        when (kind) {
            BlockKind.App -> AppPicker(existing) { chosen = it }
            BlockKind.Website -> WebsitePicker { chosen = it }
            BlockKind.Feed -> FeedPicker(existing) { chosen = it }
        }
    }
}

@Composable
private fun AppPicker(existing: Set<String>, onPick: (Chosen) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState(initialValue = emptyList<InstalledApp>(), context) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context.packageManager) }
    }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Column(Modifier.padding(horizontal = 20.dp).heightIn(max = 520.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
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
                        .clickable(enabled = !already) {
                            onPick(Chosen(TargetKind.APP, app.packageName, app.label, app.icon))
                        }
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

private val WEBSITE_SUGGESTIONS = listOf(
    "reddit.com" to "Reddit",
    "youtube.com/shorts" to "YouTube Shorts (web)",
    "x.com" to "X",
    "news.ycombinator.com" to "Hacker News",
    "instagram.com" to "Instagram (web)",
)

@Composable
private fun WebsitePicker(onPick: (Chosen) -> Unit) {
    var text by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Domain, path, or kw:word") },
            placeholder = { Text("youtube.com/shorts") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "reddit.com blocks the site and its subdomains. Adding a path like " +
                "youtube.com/shorts blocks just that section. kw:word matches anywhere in the URL.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Read from the browser's address bar, so it works regardless of DNS settings — but " +
                "only in browsers, not in web views inside other apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onPick(Chosen(TargetKind.WEBSITE, text.trim(), text.trim())) },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }

        Spacer(Modifier.height(20.dp))
        Text("Common ones", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        WEBSITE_SUGGESTIONS.forEach { (matcher, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(Chosen(TargetKind.WEBSITE, matcher, label)) }
                    .padding(vertical = 10.dp),
            ) {
                Column {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        matcher,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedPicker(existing: Set<String>, onPick: (Chosen) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Text(
            "Block the short-form feed inside an app while leaving the rest of it usable.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        FeedRegistry.specs.forEach { spec ->
            val already = spec.key in existing
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !already) {
                        onPick(Chosen(TargetKind.FEED, spec.key, spec.label))
                    }
                    .padding(vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(spec.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (already) "Already has a policy" else spec.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Feed detection reads the app's on-screen layout, which app updates can change. If a " +
                "feed stops being caught, the block fails open rather than breaking the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    chosen: Chosen,
    onBack: () -> Unit,
    onConfirm: (quotaSeconds: Int?, windowSeconds: Int) -> Unit,
) {
    var quotaSeconds by remember { mutableStateOf<Int?>(180) }
    var windowSeconds by remember { mutableStateOf(3_600) }
    var customMinutes by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            chosen.icon?.let {
                Image(bitmap = it, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                chosen.label,
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
