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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Checkbox
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

private enum class BlockKind(val label: String) {
    App("App"), Website("Website"), Feed("Feed"), Group("Group")
}

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
    /** Target ids a group rule covers; empty for every other kind. See [Policy.members]. */
    val members: List<String> = emptyList(),
)

/** What is being configured once a target has been chosen. */
private data class Chosen(
    val kind: TargetKind,
    val matcher: String,
    val label: String,
    val members: List<String> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBlockSheet(
    existing: Set<String>,
    /** Every rule that exists, so a group can be built out of the targets already written down. */
    allPolicies: List<Policy>,
    onDismiss: () -> Unit,
    onSave: (kind: TargetKind, matcher: String, label: String, draft: RuleDraft) -> Unit,
    /** Non-null to skip the picker and edit an existing rule in place. */
    editing: Policy? = null,
    grayscaleAvailable: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var kind by remember { mutableStateOf(BlockKind.App) }
    var chosen by remember {
        // A group opens on its own picker even when editing, because its members are part of what
        // is being edited and the allowance editor has no room to hold them.
        mutableStateOf(
            editing?.takeIf { it.kind != TargetKind.GROUP }
                ?.let { Chosen(it.kind, it.matcher, it.label) },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val target = chosen
        if (target != null) {
            RuleEditor(
                chosen = target,
                existing = editing,
                grayscaleAvailable = grayscaleAvailable,
                onBack = if (editing != null && target.kind != TargetKind.GROUP) {
                    null
                } else {
                    ({ chosen = null })
                },
                onConfirm = { draft ->
                    onSave(
                        target.kind,
                        target.matcher,
                        target.label,
                        draft.copy(members = target.members),
                    )
                },
            )
            return@ModalBottomSheet
        }

        if (editing?.kind == TargetKind.GROUP) {
            GroupPicker(allPolicies, editing) { chosen = it }
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
            BlockKind.Group -> GroupPicker(allPolicies, null) { chosen = it }
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

/**
 * Names a group and chooses what is in it.
 *
 * Runs before the allowance editor rather than inside it, and runs again when a group is edited,
 * because membership is part of what a group *is* -- unlike a quota, which is something a rule
 * merely has. Members are picked out of the rules that already exist, since a group is written to
 * answer a complaint about rules that already exist: each one is satisfied on its own while the
 * hour they add up to is not. A website that has no rule of its own can be typed in, so a group
 * does not require a per-site rule it was invented to make unnecessary.
 */
@Composable
private fun GroupPicker(
    allPolicies: List<Policy>,
    editing: Policy?,
    onPick: (Chosen) -> Unit,
) {
    val candidates = remember(allPolicies) {
        allPolicies.filter { it.kind != TargetKind.GROUP }.sortedBy { it.label.lowercase() }
    }
    var name by remember { mutableStateOf(editing?.label ?: "") }
    var selected by remember { mutableStateOf(editing?.members.orEmpty().toSet()) }
    var extraSite by remember { mutableStateOf("") }
    // Members that came from a typed site or from a rule since deleted: without this they vanish
    // out of a group the moment their own rule goes, which is a silent weakening nobody asked for.
    var extras by remember {
        mutableStateOf(
            editing?.members.orEmpty().filterNot { m -> candidates.any { it.id == m } },
        )
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            placeholder = { Text("Brainrot") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Hint("One allowance shared across everything in here. Time on any of them spends all of it.")

        Spacer(Modifier.height(20.dp))
        Text("What's in it", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Hint("Each of these keeps its own rule as well — both are charged for the same minute.")
        Spacer(Modifier.height(8.dp))

        if (candidates.isEmpty()) {
            Text(
                "No blocks yet. Add one first, or type a site below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        candidates.forEach { policy ->
            MemberRow(
                kind = policy.kind,
                matcher = policy.matcher,
                label = policy.label,
                checked = policy.id in selected,
                onToggle = {
                    selected = if (policy.id in selected) selected - policy.id else selected + policy.id
                },
            )
        }
        extras.forEach { targetId ->
            val matcher = targetId.substringAfter(':')
            MemberRow(
                kind = Policy.kindOf(targetId) ?: TargetKind.WEBSITE,
                matcher = matcher,
                label = matcher,
                checked = targetId in selected,
                onToggle = {
                    selected = if (targetId in selected) selected - targetId else selected + targetId
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = extraSite,
                onValueChange = { extraSite = it },
                label = { Text("Add a site with no rule") },
                placeholder = { Text("tiktok.com") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                enabled = extraSite.isNotBlank(),
                onClick = {
                    val id = Policy.targetId(TargetKind.WEBSITE, extraSite.trim())
                    if (id !in extras && candidates.none { it.id == id }) extras = extras + id
                    selected = selected + id
                    extraSite = ""
                },
            ) { Text("Add") }
        }

        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                enabled = name.isNotBlank() && selected.isNotEmpty(),
                onClick = {
                    onPick(
                        Chosen(
                            kind = TargetKind.GROUP,
                            // Kept as it was when editing, so renaming a group does not become
                            // deleting one and adding another -- the id is what the audit log,
                            // the pending queue and every recorded block already point at.
                            matcher = editing?.matcher
                                ?: groupSlug(name, allPolicies.map { it.matcher }.toSet()),
                            label = name.trim(),
                            // Ordered, so a reordering of the checkboxes is not an edit.
                            members = selected.sorted(),
                        ),
                    )
                },
            ) { Text("Continue") }
        }
    }
}

@Composable
private fun MemberRow(
    kind: TargetKind,
    matcher: String,
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        TargetAvatar(kind, matcher, label, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * A readable, stable id for a new group.
 *
 * Derived from the name so an audit entry reads `group:brainrot` rather than a random string, but
 * only ever at creation: a rename keeps the id it was given, because every block already recorded
 * against this group points at it.
 */
private fun groupSlug(name: String, taken: Set<String>): String {
    val base = name.trim().lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .ifBlank { "group" }
    if (base !in taken) return base
    var n = 2
    while ("$base-$n" in taken) n++
    return "$base-$n"
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

private val WINDOW_PRESETS = listOf<Pair<String, Int?>>(
    "per hour" to 3_600,
    "per 30 min" to 1_800,
    "per day" to 86_400,
)

private val FRICTION_PRESETS =
    listOf<Pair<String, Int?>>("None" to 0, "5s" to 5, "10s" to 10, "20s" to 20)

private const val SECONDS_PER_MINUTE = 60

private const val MAX_QUOTA_MINUTES = 24 * 60

/**
 * A rolling window can only be as long as the usage still on disk. Past
 * [com.ezhou.rotblock.quota.USAGE_RETENTION_MS] the old sessions are pruned, so a window reaching
 * further back would find nothing there and hand the time back rather than count it — a quota that
 * quietly stops running out. A week is well inside it.
 */
private const val MAX_WINDOW_MINUTES = 7 * 24 * 60

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
    // Seeded rather than started empty: a rule already carrying a typed value has to come back into
    // the field it was typed in, or the editor opens with no chip lit at all and reads as if the
    // value were unset while the rule still holds it.
    var quotaCustom by remember {
        mutableStateOf(customTextFor(quotaSeconds, QUOTA_PRESETS, SECONDS_PER_MINUTE))
    }
    var windowCustom by remember {
        mutableStateOf(customTextFor(windowSeconds, WINDOW_PRESETS, SECONDS_PER_MINUTE))
    }
    var frictionCustom by remember {
        mutableStateOf(customTextFor(frictionSeconds, FRICTION_PRESETS, 1))
    }

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
        DurationChoices(
            presets = QUOTA_PRESETS,
            value = quotaSeconds,
            custom = quotaCustom,
            unit = "min",
            unitSeconds = SECONDS_PER_MINUTE,
            maxUnits = MAX_QUOTA_MINUTES,
            onChange = { seconds, text -> quotaSeconds = seconds; quotaCustom = text },
        )

        if (quotaSeconds != null) {
            Spacer(Modifier.height(20.dp))
            Text("Out of every", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            DurationChoices(
                presets = WINDOW_PRESETS,
                value = windowSeconds,
                custom = windowCustom,
                unit = "min",
                unitSeconds = SECONDS_PER_MINUTE,
                maxUnits = MAX_WINDOW_MINUTES,
                onChange = { seconds, text -> windowSeconds = seconds ?: 0; windowCustom = text },
            )
            Spacer(Modifier.height(8.dp))
            Hint("Rolling window — time comes back gradually, with no top-of-the-hour refill.")
        }

        Spacer(Modifier.height(20.dp))
        ScheduleSection(schedule = schedule, onChange = { schedule = it })

        Spacer(Modifier.height(20.dp))
        Text("Before it opens", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        DurationChoices(
            presets = FRICTION_PRESETS,
            value = frictionSeconds,
            custom = frictionCustom,
            unit = "s",
            unitSeconds = 1,
            maxUnits = FrictionActivity.MAX_SECONDS,
            onChange = { seconds, text -> frictionSeconds = seconds ?: 0; frictionCustom = text },
        )
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

        // Said out loud rather than left to a greyed-out button, because two of the three ways to
        // reach it look like perfectly ordinary numbers.
        val refusal = refuseToSave(quotaSeconds, windowSeconds)
        refusal?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
                enabled = refusal == null,
            ) { Text(if (existing != null) "Save changes" else "Add block") }
        }
    }
}

/**
 * Why this draft cannot be saved, or null.
 *
 * The last case is the one worth refusing rather than warning about: an allowance at least as long
 * as the window it is measured over can never run out, so the rule would sit in the list looking
 * like protection while blocking nothing. Typed numbers make it reachable in a way three chips
 * never did.
 */
private fun refuseToSave(quotaSeconds: Int?, windowSeconds: Int): String? = when {
    quotaSeconds == null -> null
    quotaSeconds <= 0 -> "Choose how long, or block completely."
    windowSeconds <= 0 -> "Give the window a length."
    quotaSeconds >= windowSeconds -> "That is the whole window, so this would never block."
    else -> null
}

/**
 * A row of preset durations plus a field for anything else.
 *
 * [custom] rather than the value is what decides which chip looks selected, so typing a number that
 * happens to equal a preset does not light two chips at once — and clearing the field leaves the
 * value at zero, which [refuseToSave] catches, rather than silently falling back to whichever chip
 * the number used to match.
 *
 * That pairing is why [onChange] reports both halves rather than one callback per control: the two
 * are only ever right together, and a preset that set the value without clearing the text left the
 * typed chip lit over a number the rule no longer carried.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationChoices(
    presets: List<Pair<String, Int?>>,
    value: Int?,
    custom: String,
    unit: String,
    unitSeconds: Int,
    maxUnits: Int,
    onChange: (seconds: Int?, custom: String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        presets.forEach { (label, seconds) ->
            FilterChip(
                selected = custom.isEmpty() && value == seconds,
                onClick = { onChange(seconds, "") },
                label = { Text(label) },
            )
        }
        CustomDurationChip(
            value = custom,
            unit = unit,
            selected = custom.isNotEmpty(),
            onValueChange = { typed ->
                acceptDigits(typed, maxUnits)?.let { digits ->
                    onChange((digits.toIntOrNull() ?: 0) * unitSeconds, digits)
                }
            },
        )
    }
}

/**
 * The digits of an edit to a custom duration field, or null to refuse the edit outright.
 *
 * Refusing is what keeps the number in the field and the number the rule carries the same thing: a
 * clamped entry would sit there reading 300 while the rule meant 120, and every readout in this app
 * is only worth having if it cannot lie about time.
 */
private fun acceptDigits(typed: String, maxUnits: Int): String? {
    val digits = typed.filter { it.isDigit() }
    if (digits.isEmpty()) return ""
    val units = digits.toIntOrNull() ?: return null
    return if (units <= maxUnits) digits else null
}

/**
 * What the custom field should start out holding for a value a rule already carries: nothing when a
 * preset covers it, so the preset is what lights up, and nothing for a value this field could not
 * express exactly, which the rule keeps until something is typed over it.
 */
private fun customTextFor(
    seconds: Int?,
    presets: List<Pair<String, Int?>>,
    unitSeconds: Int,
): String =
    when {
        seconds == null || seconds <= 0 -> ""
        presets.any { it.second == seconds } -> ""
        seconds % unitSeconds != 0 -> ""
        else -> (seconds / unitSeconds).toString()
    }

/**
 * The "any other number" option, dressed as the chip it sits beside.
 *
 * A chip-shaped field, so it reads as one more choice in the row rather than a form control that
 * wandered in. An OutlinedTextField cannot be made this short without clipping its own label, so
 * this carries [unit] as a suffix instead.
 */
@Composable
private fun CustomDurationChip(
    value: String,
    unit: String,
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
        Text(" $unit", style = style, color = content)
    }
}

/**
 * [exclude] is rotblock itself. The service ignores its own package, so such a policy would never
 * fire — it would just sit in the list looking like protection that isn't there, and offering to
 * block the thing holding the unblock button is a confusing thing to offer at all.
 */
internal fun loadLaunchableApps(pm: PackageManager, exclude: String): List<InstalledApp> {
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
