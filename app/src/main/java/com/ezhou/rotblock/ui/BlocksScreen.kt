package com.ezhou.rotblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezhou.rotblock.block.formatDuration
import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.insights.RuleEffect
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.policy.PolicySort
import com.ezhou.rotblock.policy.formatWindowSeconds
import com.ezhou.rotblock.policy.sortedFor
import com.ezhou.rotblock.setup.blocking
import com.ezhou.rotblock.workout.WorkoutMode
import kotlinx.coroutines.delay

@Composable
fun BlocksScreen(
    viewModel: RotblockViewModel,
    settings: RotblockSettings,
    onOpenSetup: () -> Unit,
) {
    val policies by viewModel.policies.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    val pending by viewModel.pendingChanges.collectAsStateWithLifecycle()
    val ruleEffects by viewModel.ruleEffects.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showWorkoutAllowlist by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Policy?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val grayscaleAvailable = remember { com.ezhou.rotblock.block.Grayscale(context).isAvailable }

    // Re-sorted on the rules, the choice, and the counts — never on the heartbeat. Strictness is a
    // property of the rules, and a block count only moves when loadRuleEffects re-reads the audit
    // log, so nothing here reshuffles just because a second passed.
    val blockCounts = remember(ruleEffects) { ruleEffects.mapValues { it.value.blocksShown } }
    val sorted = remember(policies, settings.blocksSort, blockCounts) {
        policies.sortedFor(settings.blocksSort, blockCounts)
    }

    // A list of rules that is quietly enforcing none of them is the worst screen in this app, and
    // it is indistinguishable from a working one. This is the difference.
    val broken = rememberRequirements(settings, policies).blocking

    // A one-second heartbeat so countdowns and remaining-time bars stay live while you look at
    // them. Cheap: it only runs while this screen is composed.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            viewModel.refresh()
            delay(1_000)
        }
    }

    // Reloaded on the rule list, not the heartbeat: it scans a month of audit and usage history,
    // which nothing about a passing second changes.
    LaunchedEffect(policies) { viewModel.loadRuleEffects() }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add block") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Above the pause banner, because it outranks it: a pause is you deciding nothing is
            // blocked, this is nothing being blocked without anyone deciding it.
            if (broken.isNotEmpty()) {
                item(key = "setup-banner") {
                    NotEnforcingBanner(broken = broken, onOpenSetup = onOpenSetup)
                }
            }

            item {
                PauseBanner(
                    settings = settings,
                    nowMs = now,
                    budget = budget,
                    onEndPause = viewModel::endPause,
                    onPause = {
                        context.startActivity(
                            android.content.Intent(context, PauseRequestActivity::class.java),
                        )
                    },
                )
            }

            // Directly under the pause banner, because it is the same question answered the other
            // way round: a pause is you deciding nothing is blocked, this is you handing over the
            // decision entirely for as long as a workout runs. Both belong above the rules, since
            // both override what every card below them says.
            item(key = "workout") {
                WorkoutBanner(
                    enabled = settings.workoutModeEnabled,
                    allowedCount = settings.workoutAllowedPackages.size,
                    nowMs = now,
                    onChange = viewModel::setWorkoutModeEnabled,
                    onOpenAllowlist = { showWorkoutAllowlist = true },
                )
            }

            items(pending, key = { "pending-${it.id}" }) { change ->
                PendingChangeCard(
                    change = change,
                    nowMs = now,
                    onCancel = { viewModel.cancelPending(change) },
                )
            }

            if (policies.isEmpty()) {
                item { EmptyState() }
            }

            // Only worth the row once there is an order to argue about.
            if (policies.size > 1) {
                item(key = "sort") {
                    SortRow(
                        selected = settings.blocksSort,
                        onSelect = viewModel::setBlocksSort,
                    )
                }
            }

            items(sorted, key = { it.id }) { policy ->
                PolicyCard(
                    policy = policy,
                    usage = usage[policy.id],
                    effect = ruleEffects[policy.id],
                    nowMs = now,
                    paused = settings.isPaused(now),
                    onToggle = { viewModel.setPolicyEnabled(policy, it) },
                    onDelete = { viewModel.deletePolicy(policy) },
                    onEdit = { editing = policy },
                )
            }
        }
    }

    if (showWorkoutAllowlist) {
        WorkoutAllowlistSheet(
            allowed = settings.workoutAllowedPackages,
            workoutPackage = settings.workoutAppPackage,
            onChange = viewModel::setWorkoutAllowedPackages,
            onDismiss = { showWorkoutAllowlist = false },
        )
    }

    if (showAdd || editing != null) {
        AddBlockSheet(
            existing = policies.map { it.matcher }.toSet(),
            allPolicies = policies,
            editing = editing,
            grayscaleAvailable = grayscaleAvailable,
            onDismiss = { showAdd = false; editing = null },
            onSave = { kind, matcher, label, draft ->
                viewModel.savePolicy(kind, matcher, label, draft)
                showAdd = false
                editing = null
            },
        )
    }
}

/**
 * Says, on the screen people actually look at, that the rules below are not running.
 *
 * Deliberately states the consequence first and the permission second. "Grant the accessibility
 * permission" is a sentence about Android; "none of these are being enforced" is a sentence about
 * the thing the user came here to check.
 */
@Composable
private fun NotEnforcingBanner(
    broken: List<com.ezhou.rotblock.setup.Requirement>,
    onOpenSetup: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (broken.any { it.id == com.ezhou.rotblock.setup.RequirementId.ENFORCEMENT }) {
                    "Nothing is being blocked"
                } else {
                    "Blocking is not working properly"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            // The first one only: a stack of consequences on the main screen reads as a wall of
            // red, and the Setup tab is where the whole list belongs.
            Text(
                broken.first().consequence,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onOpenSetup) {
                Text(if (broken.size > 1) "Fix ${broken.size} problems" else "Fix this")
            }
        }
    }
}

@Composable
private fun PauseBanner(
    settings: RotblockSettings,
    nowMs: Long,
    budget: com.ezhou.rotblock.pause.BudgetStatus?,
    onEndPause: () -> Unit,
    onPause: () -> Unit,
) {
    val paused = settings.isPaused(nowMs)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (paused) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (paused) {
                Text(
                    "Paused — nothing is being blocked",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Blocking comes back on its own in ${formatDuration(settings.pausedUntilMs - nowMs)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onEndPause) { Text("Resume blocking now") }
            } else {
                Text(
                    "Blocking is on",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    budget.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onPause) { Text("Pause blocking") }
            }
        }
    }
}

/**
 * Workout mode's on/off, on the screen that lists the rules it overrides.
 *
 * The switch lives here, and so does the allowlist, because both are about what the rules below
 * will do. The app to watch and the two intervals stay in Setup: they are set once and never
 * looked at again, whereas these two change what you are looking at on this screen.
 *
 * [nowMs] is the screen's own one-second heartbeat, and taking it as a parameter is what makes the
 * live state honest -- a workout starting or ending is not a settings change and emits nothing this
 * screen collects, so without recomposing on the heartbeat the banner would sit on a stale answer.
 */
@Composable
private fun WorkoutBanner(
    enabled: Boolean,
    allowedCount: Int,
    nowMs: Long,
    onChange: (Boolean) -> Unit,
    onOpenAllowlist: () -> Unit,
) {
    val liveUntilMs = WorkoutMode.activeUntilMs(nowMs)
    Card(
        colors = CardDefaults.cardColors(
            // Live gets a colour of its own, and deliberately not the pause banner's error colour:
            // that one means "nothing is being blocked", and this is the exact opposite.
            containerColor = if (liveUntilMs != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (liveUntilMs != null) "Workout in progress" else "Workout mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (liveUntilMs != null) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            liveUntilMs != null && allowedCount > 0 ->
                                "Every rule below is blocking, whatever its own allowance says — " +
                                    "outside the apps you allow."
                            liveUntilMs != null ->
                                "Every rule below is blocking, whatever its own allowance says."
                            enabled -> "Waiting for a workout to start."
                            else -> "Block everything below while you are training."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (liveUntilMs != null) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(checked = enabled, onCheckedChange = onChange)
            }
            // Only once the mode is on: an allowlist for something switched off is a setting for
            // nothing, and the empty card has one job, which is the switch.
            if (enabled) {
                TextButton(onClick = onOpenAllowlist, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        if (allowedCount == 0) {
                            "Allow some apps through"
                        } else {
                            "$allowedCount ${if (allowedCount == 1) "app" else "apps"} allowed through"
                        },
                    )
                }
            }
        }
    }
}

private fun com.ezhou.rotblock.pause.BudgetStatus?.describe(): String {
    if (this == null) return "Pause it from the block screen when you genuinely need something."
    val parts = buildList {
        pausesRemaining?.let { add("$it ${if (it == 1) "pause" else "pauses"} left today") }
        minutesRemaining?.let { add("$it min of pause time left") }
    }
    return if (parts.isEmpty()) "Pauses are unlimited today." else parts.joinToString(" · ")
}

/**
 * A loosening edit waiting out its delay. Shown above the policies because the countdown is the
 * thing you most need to see -- and because cancelling it is meant to be the easy option.
 */
@Composable
private fun PendingChangeCard(
    change: com.ezhou.rotblock.data.PendingChange,
    nowMs: Long,
    onCancel: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Waiting: ${change.description}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (change.applyAtMs > nowMs) {
                    "Takes effect in ${formatDuration(change.applyAtMs - nowMs)}. " +
                        "The current rule stays on until then."
                } else {
                    "Applying now."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) { Text("Cancel this change") }
        }
    }
}

/**
 * The order the cards are in.
 *
 * Two chips rather than a menu behind an icon: there are two answers, both worth reading, and the
 * chosen one has to be visible — a list quietly in an order you did not pick is how you end up
 * believing a rule is missing.
 */
@Composable
private fun SortRow(selected: PolicySort, onSelect: (PolicySort) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Sort",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PolicySort.entries.forEach { sort ->
            FilterChip(
                selected = sort == selected,
                onClick = { onSelect(sort) },
                label = { Text(sort.label) },
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Nothing is blocked yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Add the apps that eat your evenings. You can block them outright, or give " +
                    "yourself a few minutes out of every hour.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PolicyCard(
    policy: Policy,
    usage: PolicyUsage?,
    effect: RuleEffect?,
    nowMs: Long,
    paused: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Recomputed against the screen's own one-second clock, so a card watched across a window
    // opening changes over rather than sitting on a stale answer.
    val offHours = policy.enabled && !policy.schedule.isActiveAt(nowMs)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TargetAvatar(policy)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        policy.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (policy.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        policy.describeRule(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (policy.enabled) "Disable" else "Enable") },
                            onClick = { menuOpen = false; onToggle(!policy.enabled) },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }

            if (policy.kind == TargetKind.GROUP) {
                Spacer(Modifier.height(10.dp))
                GroupMembers(policy)
            }

            if (usage?.quotaMs != null) {
                Spacer(Modifier.height(12.dp))
                QuotaBar(usage = usage, enabled = policy.enabled && !paused && !offHours)
                Spacer(Modifier.height(6.dp))
                Text(
                    usage.statusLine(nowMs, paused || !policy.enabled || offHours),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (offHours && !paused) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Outside its hours — not in force right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // A rule written this morning has nothing to report, and "Blocked 0 times · paused
            // past 0 times / Not enough history yet" is two lines of nothing on every new card.
            if (effect != null && effect.hasAnythingToSay) {
                Spacer(Modifier.height(6.dp))
                Text(
                    effect.describeCounts(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    effect.describeTrend(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Blocked 34 times · paused past 6 times" -- what actually happened, no interpretation. */
private fun RuleEffect.describeCounts(): String =
    "Blocked $blocksShown ${if (blocksShown == 1) "time" else "times"} · " +
        "paused past $pausesPastIt ${if (pausesPastIt == 1) "time" else "times"}"

/**
 * A trend within the retention window, never a claim about the rule causing it -- see
 * insights/RuleEffect.kt. "Not enough history" is the common case (a fresh rule, or one that
 * hasn't seen 28 days of regular use) and is written to read as expected, not as broken.
 */
private fun RuleEffect.describeTrend(): String {
    if (!hasEnoughHistory) return "Not enough history yet"
    val first = firstHalfDailyMeanMs ?: return "Not enough history yet"
    val second = secondHalfDailyMeanMs ?: return "Not enough history yet"
    val secondStr = formatDailyMean(second)
    return when {
        second < first -> "$secondStr/day, down from ${formatDailyMean(first)}/day"
        second > first -> "$secondStr/day, up from ${formatDailyMean(first)}/day"
        else -> "$secondStr/day, steady"
    }
}

private fun formatDailyMean(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes > 0) "${minutes}m" else "<1m"
}

@Composable
private fun QuotaBar(usage: PolicyUsage, enabled: Boolean) {
    val quota = usage.quotaMs ?: return
    val fraction = (usage.usedMs.toFloat() / quota.toFloat()).coerceIn(0f, 1f)
    val fill = when {
        !enabled -> MaterialTheme.colorScheme.outline
        usage.isExhausted -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(fill),
        )
    }
}

private fun PolicyUsage.statusLine(nowMs: Long, inactive: Boolean): String {
    val quota = quotaMs ?: return ""
    if (inactive) return "${formatDuration(usedMs)} used of ${formatDuration(quota)}"
    val remaining = remainingMs ?: 0L
    return if (remaining > 0) {
        "${formatDuration(remaining)} left"
    } else {
        val release = nextReleaseMs
        if (release != null && release > nowMs) {
            "Out of time — more in ${formatDuration(release - nowMs)}"
        } else {
            "Out of time"
        }
    }
}

/**
 * What is inside a group, as the members' own icons.
 *
 * A group's card is otherwise the only one that cannot say what it is about: every other rule has
 * a target with a name and a logo, and a group has a name someone typed. The icons are the answer
 * to "shared between what", which is the only question this card raises and none of the others do.
 */
@Composable
private fun GroupMembers(policy: Policy) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        policy.members.take(MAX_MEMBER_AVATARS).forEach { member ->
            val kind = Policy.kindOf(member) ?: TargetKind.WEBSITE
            val matcher = member.substringAfter(':')
            TargetAvatar(kind, matcher, matcher, size = 24.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            memberSummary(policy.members),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Room for a handful of icons on a phone-width card; the rest are counted instead. */
private const val MAX_MEMBER_AVATARS = 5

private fun memberSummary(members: List<String>): String {
    val names = members.map { it.substringAfter(':') }
    val overflow = names.size - MAX_MEMBER_AVATARS
    return when {
        names.isEmpty() -> "Nothing in it yet"
        overflow > 0 -> "${names.take(MAX_MEMBER_AVATARS).joinToString(", ")} +$overflow more"
        else -> names.joinToString(", ")
    }
}

private fun Policy.describeRule(): String {
    val allowance = quotaSeconds?.let { quota ->
        val minutes = quota / 60
        val seconds = quota % 60
        val amount = if (seconds == 0) "$minutes min" else "$minutes min ${seconds}s"
        "$amount in any ${formatWindowSeconds(windowSeconds)}"
    } ?: "Blocked completely"
    val base = schedule.describe()?.let { "$allowance · $it" } ?: allowance
    return if (enabled) base else "$base (off)"
}
