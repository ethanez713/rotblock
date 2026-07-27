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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.ezhou.rotblock.policy.Policy
import kotlinx.coroutines.delay

@Composable
fun BlocksScreen(viewModel: RotblockViewModel, settings: RotblockSettings) {
    val policies by viewModel.policies.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    val pending by viewModel.pendingChanges.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Policy?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val grayscaleAvailable = remember { com.ezhou.rotblock.block.Grayscale(context).isAvailable }

    // A one-second heartbeat so countdowns and remaining-time bars stay live while you look at
    // them. Cheap: it only runs while this screen is composed.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            viewModel.refresh()
            delay(1_000)
        }
    }

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

            items(policies, key = { it.id }) { policy ->
                PolicyCard(
                    policy = policy,
                    usage = usage[policy.id],
                    nowMs = now,
                    paused = settings.isPaused(now),
                    onToggle = { viewModel.setPolicyEnabled(policy, it) },
                    onDelete = { viewModel.deletePolicy(policy) },
                    onEdit = { editing = policy },
                )
            }
        }
    }

    if (showAdd || editing != null) {
        AddBlockSheet(
            existing = policies.map { it.matcher }.toSet(),
            editing = editing,
            grayscaleAvailable = grayscaleAvailable,
            onDismiss = { showAdd = false; editing = null },
            onSave = { kind, matcher, label, quota, window, friction, gray ->
                viewModel.savePolicy(kind, matcher, label, quota, window, friction, gray)
                showAdd = false
                editing = null
            },
        )
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
    nowMs: Long,
    paused: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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

            if (usage?.quotaMs != null) {
                Spacer(Modifier.height(12.dp))
                QuotaBar(usage = usage, enabled = policy.enabled && !paused)
                Spacer(Modifier.height(6.dp))
                Text(
                    usage.statusLine(nowMs, paused || !policy.enabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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

private fun Policy.describeRule(): String {
    val quota = quotaSeconds ?: return if (enabled) "Blocked completely" else "Blocked completely (off)"
    val window = windowSeconds / 60
    val minutes = quota / 60
    val seconds = quota % 60
    val amount = if (seconds == 0) "$minutes min" else "$minutes min ${seconds}s"
    val base = "$amount in any $window min"
    return if (enabled) base else "$base (off)"
}
