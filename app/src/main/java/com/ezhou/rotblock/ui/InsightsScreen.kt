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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.block.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DAY_LABEL = SimpleDateFormat("d/M", Locale.getDefault())
private val TIME_FORMAT = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())

/**
 * The accountability view: how much rot got through, and how often you reached for an escape
 * hatch to let it.
 *
 * The two charts answer different questions and are deliberately not combined onto one pair of
 * axes -- minutes and counts share no scale, and a dual-axis chart would invite comparing two
 * lines that mean nothing to each other.
 */
@Composable
fun InsightsScreen(viewModel: RotblockViewModel) {
    val state by viewModel.insights.collectAsStateWithLifecycle()
    val events by viewModel.recentEvents.collectAsStateWithLifecycle()
    var days by remember { mutableStateOf(7) }
    var selectedDay by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(days) {
        selectedDay = null
        viewModel.loadInsights(days)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Insights",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Filters in one row above the charts.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30).forEach { option ->
                    FilterChip(
                        selected = days == option,
                        onClick = { days = option },
                        label = { Text("Last $option days") },
                    )
                }
            }
        }

        val current = state
        if (current == null) {
            item { Text("Working it out…", style = MaterialTheme.typography.bodyMedium) }
        } else {
            item { Scorecard(current) }
            item {
                UsageChartCard(
                    state = current,
                    selectedDay = selectedDay,
                    onSelect = { selectedDay = it },
                )
            }
            item { EscapeHatchCard(current) }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Everything that changed the rules",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Append-only. There is no way to edit or delete an entry, including from here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (events.isEmpty()) {
            item {
                Text(
                    "Nothing yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(events, key = { it.id }) { event -> EventRow(event) }
    }
}

/** Four numbers that do not need a chart to make their point. */
@Composable
private fun Scorecard(state: InsightsState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile("Pauses", state.pauseCount.toString(), Modifier.weight(1f))
        StatTile(
            "Emergency",
            state.emergencyCount.toString(),
            Modifier.weight(1f),
            emphasis = state.emergencyCount > 0,
        )
        StatTile("Paused", "${state.totalPauseMinutes}m", Modifier.weight(1f))
        StatTile(
            "Loosened",
            state.weakeningCount.toString(),
            Modifier.weight(1f),
            emphasis = state.weakeningCount > 0,
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, emphasis: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier,
    ) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (emphasis) ChartPalette.critical else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageChartCard(
    state: InsightsState,
    selectedDay: Int?,
    onSelect: (Int?) -> Unit,
) {
    val slots = state.targetOrder.take(ChartPalette.slotCount)
    val otherColor = ChartPalette.other()
    val colorFor: @Composable (String) -> Color = { id ->
        val index = slots.indexOf(id)
        if (index >= 0) ChartPalette.series(index) else otherColor
    }

    val bars = state.buckets.mapIndexed { index, bucket ->
        val segments = buildList {
            slots.forEach { id ->
                val ms = bucket.perTarget[id] ?: 0L
                if (ms > 0) add(Segment((ms / 60_000f), colorFor(id)))
            }
            val otherMs = bucket.perTarget.filterKeys { it !in slots }.values.sum()
            if (otherMs > 0) add(Segment(otherMs / 60_000f, otherColor))
        }
        Bar(label = DAY_LABEL.format(Date(state.dayStarts[index])), segments = segments)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Minutes on blocked things",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                selectedDay?.let { index ->
                    val bucket = state.buckets[index]
                    "${DAY_LABEL.format(Date(state.dayStarts[index]))}: " +
                        formatDuration(bucket.totalMs)
                } ?: "${formatDuration(state.totalUsageMs)} over ${state.days} days · tap a bar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            StackedBarChart(
                bars = bars,
                selectedIndex = selectedDay,
                onSelect = onSelect,
                labelEvery = if (state.days > 10) 5 else 1,
                formatGridValue = { "${it.toInt()}m" },
            )

            Spacer(Modifier.height(12.dp))
            // The breakdown doubles as the light-mode relief for the sub-3:1 series colours:
            // every value is readable as text, not only as a coloured block.
            val totals = if (selectedDay != null) {
                state.buckets[selectedDay].perTarget.toList().sortedByDescending { it.second }
            } else {
                state.totalsByTarget
            }
            if (totals.isEmpty()) {
                Text(
                    "Nothing recorded in this range.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                totals.forEach { (id, ms) ->
                    LegendRow(
                        color = colorFor(id),
                        label = state.targetLabels[id] ?: id.substringAfter(':'),
                        value = formatDuration(ms),
                    )
                }
            }
        }
    }
}

@Composable
private fun EscapeHatchCard(state: InsightsState) {
    val pauseColor = ChartPalette.series(0)
    val bars = state.dayStarts.mapIndexed { index, day ->
        val normal = (state.pauseCounts.getOrElse(index) { 0 } -
            state.emergencyCounts.getOrElse(index) { 0 }).coerceAtLeast(0)
        Bar(
            label = DAY_LABEL.format(Date(day)),
            segments = listOfNotNull(
                Segment(normal.toFloat(), pauseColor).takeIf { normal > 0 },
                Segment(state.emergencyCounts.getOrElse(index) { 0 }.toFloat(), ChartPalette.critical)
                    .takeIf { state.emergencyCounts.getOrElse(index) { 0 } > 0 },
            ),
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "How often you let something through",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${state.pauseCount} pauses, ${state.emergencyCount} of them skipping the cooldown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            StackedBarChart(
                bars = bars,
                height = 120.dp,
                labelEvery = if (state.days > 10) 5 else 1,
                gridLines = 2,
                formatGridValue = { it.toInt().toString() },
            )

            Spacer(Modifier.height(12.dp))
            LegendRow(pauseColor, "Pause after the cooldown", state.pauseCount.minus(state.emergencyCount).coerceAtLeast(0).toString())
            LegendRow(ChartPalette.critical, "Emergency skip", state.emergencyCount.toString())
        }
    }
}

/** A colour chip is never the only identifier: the label always rides alongside it. */
@Composable
private fun LegendRow(color: Color, label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventRow(event: AuditEvent) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(event.type.tint()),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    event.detail.ifBlank { event.type.readable() },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (event.type.isRelaxation()) FontWeight.SemiBold else FontWeight.Normal,
                )
                event.userReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "“$reason”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Text(
                        TIME_FORMAT.format(Date(event.tsMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (event.overBudget) {
                        Text(
                            "  ·  over budget",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** Red for the escape hatches, green for anything that tightens the rules, grey for context. */
@Composable
private fun AuditType.tint(): Color = when (this) {
    AuditType.EMERGENCY_SKIP,
    AuditType.PAUSE_STARTED,
    AuditType.WEAKENING_QUEUED,
    AuditType.WEAKENING_APPLIED,
    AuditType.POLICY_DELETED,
    AuditType.SERVICE_DISABLED,
    -> MaterialTheme.colorScheme.error

    AuditType.POLICY_CREATED,
    AuditType.POLICY_STRENGTHENED,
    AuditType.WEAKENING_CANCELLED,
    AuditType.PAUSE_ENDED_EARLY,
    AuditType.PAUSE_EXPIRED,
    AuditType.COOLDOWN_ABANDONED,
    AuditType.SERVICE_ENABLED,
    -> MaterialTheme.colorScheme.primary

    else -> MaterialTheme.colorScheme.outline
}

private fun AuditType.isRelaxation(): Boolean = when (this) {
    AuditType.EMERGENCY_SKIP,
    AuditType.PAUSE_STARTED,
    AuditType.WEAKENING_APPLIED,
    AuditType.POLICY_DELETED,
    AuditType.SERVICE_DISABLED,
    -> true

    else -> false
}

private fun AuditType.readable(): String =
    name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
