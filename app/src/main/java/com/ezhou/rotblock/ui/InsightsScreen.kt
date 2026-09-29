package com.ezhou.rotblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.block.formatDuration
import com.ezhou.rotblock.insights.SegmentSlot
import com.ezhou.rotblock.insights.screenTimeSegments
import com.ezhou.rotblock.insights.seriesSlot
import com.ezhou.rotblock.insights.splitPercents
import com.ezhou.rotblock.insights.splitScreenTime
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.setup.SettingsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DAY_LABEL = SimpleDateFormat("d/M", Locale.getDefault())
private val TIME_FORMAT = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())

private const val UNBLOCKED_PREVIEW = 10

/**
 * Whole-phone screen time, split by whether a current rule covers it, plus how often an escape
 * hatch was used.
 *
 * The two charts stay on separate axes: minutes and counts share no scale, and a dual-axis chart
 * would invite comparing two marks that mean nothing to each other.
 */
@Composable
fun InsightsScreen(viewModel: RotblockViewModel) {
    // Before the log is collected. LaunchedEffect runs after the first frame, which would paint
    // the page left over from last time.
    remember { viewModel.showAuditPage(0) }
    val state by viewModel.insights.collectAsStateWithLifecycle()
    val requestedPage by viewModel.auditPageIndex.collectAsStateWithLifecycle()
    val audit by viewModel.auditLog.collectAsStateWithLifecycle()
    val shownAudit = audit?.takeIf { it.page == requestedPage }

    var days by remember { mutableStateOf(7) }
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    var historyKey by remember(days) { mutableStateOf<String?>(null) }
    var unblockedExpanded by remember(days, selected) { mutableStateOf(false) }

    LaunchedEffect(days) { viewModel.loadInsights(days) }

    val ready = state?.takeIf { it.days == days }

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

        item {
            RangeChips(days = days, onDays = { days = it })
        }

        if (ready == null) {
            item { Text("Working it out…", style = MaterialTheme.typography.bodyMedium) }
        } else {
            item { Scorecard(ready) }
            item { WhereTheTimeWent(ready) }
            item {
                ScreenTimeCard(
                    state = ready,
                    selected = selected?.takeIf { it in ready.buckets.indices },
                    onSelect = { selected = it },
                    historyKey = historyKey,
                    onHistoryKey = { key -> historyKey = if (historyKey == key) null else key },
                    unblockedExpanded = unblockedExpanded,
                    onUnblockedExpanded = { unblockedExpanded = it },
                )
            }
            item { EscapeHatchCard(ready) }
        }

        item { AuditLogCard(shownAudit, onPage = viewModel::showAuditPage) }
    }
}

@Composable
private fun RangeChips(days: Int, onDays: (Int) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(7, 30, 90, 365).forEach { option ->
            FilterChip(
                selected = days == option,
                onClick = { onDays(option) },
                label = { Text("$option days") },
            )
        }
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

/** Total screen time, and how it splits between rotblocked and everything else. */
@Composable
private fun WhereTheTimeWent(state: InsightsState) {
    val split = remember(state.buckets, state.rotblockedKeys) {
        splitScreenTime(state.buckets, state.rotblockedKeys)
    }
    val (rotPct, unPct) = splitPercents(split)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Where the time went",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatDuration(split.totalMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            LegendRow(
                ChartPalette.warm(0),
                "Rotblocked",
                "${formatDuration(split.rotblockedMs)} · $rotPct%",
            )
            LegendRow(
                ChartPalette.cool(0),
                "Unblocked",
                "${formatDuration(split.unblockedMs)} · $unPct%",
            )
            if (split.totalMs > 0L) {
                Spacer(Modifier.height(8.dp))
                ProportionBar(split.rotblockedMs, split.unblockedMs)
            }
        }
    }
}

@Composable
private fun ProportionBar(rotblockedMs: Long, unblockedMs: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        if (rotblockedMs > 0L) {
            Box(
                Modifier
                    .weight(rotblockedMs.toFloat())
                    .fillMaxHeight()
                    .background(ChartPalette.warm(0)),
            )
        }
        if (rotblockedMs > 0L && unblockedMs > 0L) {
            Spacer(Modifier.width(2.dp).fillMaxHeight())
        }
        if (unblockedMs > 0L) {
            Box(
                Modifier
                    .weight(unblockedMs.toFloat())
                    .fillMaxHeight()
                    .background(ChartPalette.cool(0)),
            )
        }
    }
}

@Composable
private fun ScreenTimeCard(
    state: InsightsState,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    historyKey: String?,
    onHistoryKey: (String?) -> Unit,
    unblockedExpanded: Boolean,
    onUnblockedExpanded: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val colors = segmentColors()
    val colorFor: (String) -> Color = { key ->
        colors.getValue(seriesSlot(key, state.slots, state.rotblockedKeys))
    }
    val bars = remember(state.buckets, state.slots, state.rotblockedKeys, colors) {
        state.buckets.map { bucket ->
            Bar(
                label = DAY_LABEL.format(Date(bucket.dayStartMs)),
                segments = screenTimeSegments(bucket.perTarget, state.slots, state.rotblockedKeys)
                    .map { part -> Segment(part.ms / 60_000f, colors.getValue(part.slot)) },
            )
        }
    }
    val rows = remember(state.buckets, state.rotblockedKeys, selected) {
        val source = if (selected != null) {
            state.buckets[selected].perTarget.map { it.key to it.value }
        } else {
            state.totalsByTarget
        }
        source.filter { it.second > 0L }.sortedWith(
            compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first },
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Screen time",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                screenTimeHeader(state, selected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.usageAccessGranted) {
                Text(
                    "Insights can only show rotblocked time. Open usage access settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { openSettings(context, SettingsScreen.USAGE_ACCESS) },
                )
            }
            Spacer(Modifier.height(12.dp))

            StackedBarChart(
                bars = bars,
                selectedIndex = selected,
                onSelect = onSelect,
                labelEvery = axisLabelEvery(bars.size),
                formatGridValue = ::formatAxisMinutes,
            )

            if (historyKey != null) {
                Spacer(Modifier.height(12.dp))
                HistoryCard(
                    state = state,
                    targetKey = historyKey,
                    color = colorFor(historyKey),
                    onClose = { onHistoryKey(null) },
                )
            }

            Spacer(Modifier.height(12.dp))
            // The breakdown is the light-mode relief for the sub-3:1 series colours: every value
            // is readable as text, not only as a coloured block.
            if (rows.isEmpty()) {
                Text(
                    "Nothing recorded in this range.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TimeSection(
                    title = "Rotblocked",
                    rows = rows.filter { it.first in state.rotblockedKeys },
                    state = state,
                    colorFor = colorFor,
                    historyKey = historyKey,
                    onRow = onHistoryKey,
                )
                Spacer(Modifier.height(8.dp))
                TimeSection(
                    title = "Unblocked",
                    rows = rows.filter { it.first !in state.rotblockedKeys },
                    state = state,
                    colorFor = colorFor,
                    historyKey = historyKey,
                    onRow = onHistoryKey,
                    collapsible = true,
                    expanded = unblockedExpanded,
                    onToggleExpanded = { onUnblockedExpanded(!unblockedExpanded) },
                )
            }
        }
    }
}

@Composable
private fun segmentColors(): Map<SegmentSlot, Color> = mapOf(
    SegmentSlot.WARM_0 to ChartPalette.warm(0),
    SegmentSlot.WARM_1 to ChartPalette.warm(1),
    SegmentSlot.WARM_2 to ChartPalette.warm(2),
    SegmentSlot.OTHER_WARM to ChartPalette.otherWarm(),
    SegmentSlot.COOL_0 to ChartPalette.cool(0),
    SegmentSlot.COOL_1 to ChartPalette.cool(1),
    SegmentSlot.COOL_2 to ChartPalette.cool(2),
    SegmentSlot.OTHER_COOL to ChartPalette.otherCool(),
)

private fun screenTimeHeader(state: InsightsState, selected: Int?): String {
    if (selected == null) {
        return "${formatDuration(state.totalUsageMs)} over ${state.days} days · tap a bar"
    }
    val bucket = state.buckets[selected]
    val split = splitScreenTime(bucket.perTarget, state.rotblockedKeys)
    val (rotPct, unPct) = splitPercents(split)
    val whenLabel = bucketLabel(state.weekly, bucket.dayStartMs)
    return "$whenLabel: ${formatDuration(split.totalMs)} · " +
        "${formatDuration(split.rotblockedMs)} rotblocked ($rotPct%), " +
        "${formatDuration(split.unblockedMs)} unblocked ($unPct%)"
}

private fun bucketLabel(weekly: Boolean, dayStartMs: Long): String {
    val day = DAY_LABEL.format(Date(dayStartMs))
    return if (weekly) "Week of $day" else day
}

@Composable
private fun HistoryCard(
    state: InsightsState,
    targetKey: String,
    color: Color,
    onClose: () -> Unit,
) {
    val total = remember(state.buckets, targetKey) {
        state.buckets.sumOf { it.perTarget[targetKey] ?: 0L }
    }
    val average = if (state.days <= 0) 0L else total / state.days
    val bars = remember(state.buckets, targetKey, color) {
        state.buckets.map { bucket ->
            val ms = bucket.perTarget[targetKey] ?: 0L
            Bar(
                label = DAY_LABEL.format(Date(bucket.dayStartMs)),
                segments = if (ms > 0L) listOf(Segment(ms / 60_000f, color)) else emptyList(),
            )
        }
    }
    val label = state.targetLabels[targetKey] ?: targetKey.substringAfter(':', targetKey)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${formatDuration(total)} · ${formatDuration(average)} a day",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            Spacer(Modifier.height(8.dp))
            StackedBarChart(
                bars = bars,
                height = 120.dp,
                labelEvery = axisLabelEvery(bars.size),
                gridLines = 2,
                formatGridValue = ::formatAxisMinutes,
            )
        }
    }
}

@Composable
private fun TimeSection(
    title: String,
    rows: List<Pair<String, Long>>,
    state: InsightsState,
    colorFor: (String) -> Color,
    historyKey: String?,
    onRow: (String) -> Unit,
    collapsible: Boolean = false,
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
) {
    val subtotal = rows.sumOf { it.second }
    val visible = if (collapsible && !expanded) rows.take(UNBLOCKED_PREVIEW) else rows
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDuration(subtotal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        visible.forEach { (key, ms) ->
            BreakdownRow(
                label = state.targetLabels[key] ?: key.substringAfter(':', key),
                value = formatDuration(ms),
                color = colorFor(key),
                targetKey = key,
                selected = key == historyKey,
                onClick = { onRow(key) },
            )
        }
        if (collapsible && rows.size > UNBLOCKED_PREVIEW) {
            TextButton(onClick = onToggleExpanded, contentPadding = PaddingValues(0.dp)) {
                Text(
                    if (expanded) "Show fewer" else moreAppsLabel(rows.size - UNBLOCKED_PREVIEW),
                )
            }
        }
    }
}

private fun moreAppsLabel(count: Int): String =
    if (count == 1) "1 more app" else "$count more apps"

@Composable
private fun BreakdownRow(
    label: String,
    value: String,
    color: Color,
    targetKey: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        val kind = Policy.kindOf(targetKey)
        if (kind != null) {
            TargetAvatar(
                kind = kind,
                matcher = targetKey.substringAfter(':'),
                label = label,
                size = 28.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
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
                buildString {
                    append(state.pauseCount)
                    append(if (state.pauseCount == 1) " pause" else " pauses")
                    if (state.emergencyCount > 0) {
                        append(", ${state.emergencyCount} of them skipping the cooldown")
                    }
                    append('.')
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            StackedBarChart(
                bars = bars,
                height = 120.dp,
                labelEvery = axisLabelEvery(bars.size),
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
private fun AuditLogCard(page: AuditPage?, onPage: (Int) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
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
            LaunchedEffect(page?.page, page?.count, page?.events?.size) {
                val shown = page ?: return@LaunchedEffect
                if (shown.page > 0 && shown.events.isEmpty() && shown.count > 0) {
                    onPage(((shown.count - 1) / AUDIT_PAGE_SIZE).coerceAtLeast(0))
                }
            }
            if (page == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Working it out…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (page.count == 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Nothing yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                page.events.forEachIndexed { index, event ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                    EventEntry(event)
                }
                Box(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { onPage(page.page - 1) },
                        enabled = page.hasNewer,
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) { Text("Newer") }
                    Text(
                        page.position,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    TextButton(
                        onClick = { onPage(page.page + 1) },
                        enabled = page.hasOlder,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) { Text("Older") }
                }
            }
        }
    }
}

@Composable
private fun EventEntry(event: AuditEvent) {
    Row(Modifier.padding(vertical = 10.dp)) {
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

private fun axisLabelEvery(bucketCount: Int): Int = when {
    bucketCount <= 10 -> 1
    bucketCount <= 20 -> 2
    else -> (bucketCount / 6).coerceAtLeast(1)
}

/** Ticks are whole minutes. Past an hour they read as hours so a week-scale axis still fits. */
private fun formatAxisMinutes(minutes: Float): String {
    val whole = minutes.toInt()
    if (whole < 60) return "${whole}m"
    val hours = whole / 60
    val rest = whole % 60
    if (rest == 0) return "${hours}h"
    val tenths = (rest * 10) / 60
    return if (tenths == 0) "${hours}h" else "$hours.${tenths}h"
}