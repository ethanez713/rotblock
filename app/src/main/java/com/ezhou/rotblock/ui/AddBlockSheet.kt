package com.ezhou.rotblock.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.Schedule
import com.ezhou.rotblock.policy.TargetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

private enum class BlockKind(val label: String) { App("App"), Website("Website"), Feed("Feed") }

/**
 * Everything the rule editor decides about a target, as one value.
 *
 * A single object rather than a longer callback: five of these are ints and booleans, and a
 * positional list that long is one careless reorder away from swapping a quota for a countdown.
 */
data class RuleDraft(
    val quotaSeconds: Int?,
    val windowSeconds: Int,
    val frictionSeconds: Int,
    val grayscale: Boolean,
    val schedule: Schedule,
)

/** What is being configured once a target has been chosen. */
private data class Chosen(
    val kind: TargetKind,
    val matcher: String,
    val label: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBlockSheet(
    existing: Set<String>,
    onDismiss: () -> Unit,
    onSave: (kind: TargetKind, matcher: String, label: String, draft: RuleDraft) -> Unit,
    /** Non-null to skip the picker and edit an existing rule in place. */
    editing: Policy? = null,
    grayscaleAvailable: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var kind by remember { mutableStateOf(BlockKind.App) }
    var chosen by remember {
        mutableStateOf(editing?.let { Chosen(it.kind, it.matcher, it.label) })
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val target = chosen
        if (target != null) {
            RuleEditor(
                chosen = target,
                existing = editing,
                grayscaleAvailable = grayscaleAvailable,
                onBack = if (editing != null) null else ({ chosen = null }),
                onConfirm = { draft -> onSave(target.kind, target.matcher, target.label, draft) },
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
        value = withContext(Dispatchers.IO) {
            loadLaunchableApps(context.packageManager, exclude = context.packageName)
        }
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
                            onPick(Chosen(TargetKind.APP, app.packageName, app.label))
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
        Hint("A domain covers its subdomains; a path narrows it. Browsers only, not in-app webviews.")

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { onPick(Chosen(TargetKind.WEBSITE, text.trim(), text.trim())) },
                enabled = text.isNotBlank(),
            ) { Text("Continue") }
        }

        Spacer(Modifier.height(20.dp))
        Text("Common ones", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        WEBSITE_SUGGESTIONS.forEach { (matcher, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(Chosen(TargetKind.WEBSITE, matcher, label)) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TargetAvatar(TargetKind.WEBSITE, matcher, label, size = 36.dp)
                Spacer(Modifier.width(14.dp))
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
            "Block the short-form feed inside an app, leaving the rest usable.",
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TargetAvatar(TargetKind.FEED, spec.key, spec.label, size = 36.dp)
                Spacer(Modifier.width(14.dp))
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
        Hint("Detection reads the app's layout, so an app update can break it — it fails open.")
    }
}

/** The one-line explanations under a control. Small, muted, and kept to a single sentence. */
@Composable
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

private val FRICTION_PRESETS = listOf("None" to 0, "5s" to 5, "10s" to 10, "20s" to 20)

@Composable
private fun RuleEditor(
    chosen: Chosen,
    existing: Policy?,
    grayscaleAvailable: Boolean,
    onBack: (() -> Unit)?,
    onConfirm: (RuleDraft) -> Unit,
) {
    // Not `existing?.quotaSeconds ?: 180`: a null quota means "block completely", so that elvis
    // would quietly turn an edited hard block into a 3-minute allowance.
    var quotaSeconds by remember {
        mutableStateOf<Int?>(if (existing != null) existing.quotaSeconds else 180)
    }
    var windowSeconds by remember { mutableStateOf(existing?.windowSeconds ?: 3_600) }
    var frictionSeconds by remember { mutableStateOf(existing?.frictionSeconds ?: 0) }
    var grayscale by remember { mutableStateOf(existing?.grayscale ?: false) }
    var schedule by remember { mutableStateOf(existing?.schedule ?: Schedule.ALWAYS) }
    var customMinutes by remember { mutableStateOf("") }

    // Scrollable since the schedule rows landed: with two of them this is taller than a phone, and
    // the confirm button is the part that goes off the bottom.
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TargetAvatar(chosen.kind, chosen.matcher, chosen.label, size = 44.dp)
            Spacer(Modifier.width(12.dp))
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            QUOTA_PRESETS.drop(3).forEach { (label, seconds) ->
                FilterChip(
                    selected = quotaSeconds == seconds,
                    onClick = { quotaSeconds = seconds; customMinutes = "" },
                    label = { Text(label) },
                )
            }
            // A chip-shaped field, so the custom option reads as one more choice in the row rather
            // than a form control that wandered in. An OutlinedTextField cannot be made this short
            // without clipping its own label, so it carries a suffix instead.
            CustomMinutesChip(
                value = customMinutes,
                selected = customMinutes.isNotEmpty() && quotaSeconds == customMinutes.toIntOrNull()?.times(60),
                onValueChange = { text ->
                    customMinutes = text.filter { it.isDigit() }.take(4)
                    quotaSeconds = customMinutes.toIntOrNull()?.times(60)
                },
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
            Spacer(Modifier.height(8.dp))
            Hint("Rolling window — time comes back gradually, with no top-of-the-hour refill.")
        }

        Spacer(Modifier.height(20.dp))
        ScheduleSection(schedule = schedule, onChange = { schedule = it })

        Spacer(Modifier.height(20.dp))
        Text("Before it opens", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FRICTION_PRESETS.forEach { (label, seconds) ->
                FilterChip(
                    selected = frictionSeconds == seconds,
                    onClick = { frictionSeconds = seconds },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Hint("A blank countdown first. Always lets you through.")

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Drain the colour", style = MaterialTheme.typography.titleSmall)
                Hint(
                    if (grayscaleAvailable) {
                        "Greyscale while this is open."
                    } else {
                        "Needs a one-time ADB grant — see Setup."
                    },
                )
            }
            Switch(
                checked = grayscale && grayscaleAvailable,
                onCheckedChange = { grayscale = it },
                enabled = grayscaleAvailable,
            )
        }

        if (existing != null) {
            Spacer(Modifier.height(20.dp))
            Hint("Loosening waits out the delay. Tightening applies now.")
        }

        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let {
                TextButton(onClick = it) { Text("Back") }
                Spacer(Modifier.width(12.dp))
            }
            Button(
                onClick = {
                    onConfirm(
                        RuleDraft(quotaSeconds, windowSeconds, frictionSeconds, grayscale, schedule),
                    )
                },
                enabled = quotaSeconds == null || (quotaSeconds ?: 0) > 0,
            ) { Text(if (existing != null) "Save changes" else "Add block") }
        }
    }
}

/**
 * The "any number of minutes" option, dressed as the chip it sits beside.
 *
 * Its own selected state is derived from the quota rather than stored: typing here sets the quota,
 * and picking a preset clears the text, so there is nothing to keep in sync.
 */
@Composable
private fun CustomMinutesChip(
    value: String,
    selected: Boolean,
    onValueChange: (String) -> Unit,
) {
    val shape = FilterChipDefaults.shape
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(FilterChipDefaults.Height)
            .widthIn(min = 96.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                shape,
            )
            .border(
                1.dp,
                if (selected) Color.Transparent else MaterialTheme.colorScheme.outline,
                shape,
            )
            .padding(horizontal = 12.dp),
    ) {
        val style = MaterialTheme.typography.labelLarge
        Box {
            if (value.isEmpty()) {
                Text("00", style = style, color = content.copy(alpha = 0.5f))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = style.copy(color = content),
                cursorBrush = SolidColor(content),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.widthIn(min = 30.dp),
            )
        }
        Text(" min", style = style, color = content)
    }
}

/**
 * [exclude] is rotblock itself. The service ignores its own package, so such a policy would never
 * fire — it would just sit in the list looking like protection that isn't there, and offering to
 * block the thing holding the unblock button is a confusing thing to offer at all.
 */
private fun loadLaunchableApps(pm: PackageManager, exclude: String): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved
        .filterNot { it.activityInfo.packageName == exclude }
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
