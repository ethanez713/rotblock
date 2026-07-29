package com.ezhou.rotblock.ui

import android.text.format.DateFormat
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ezhou.rotblock.policy.DAY_INITIALS
import com.ezhou.rotblock.policy.Schedule
import com.ezhou.rotblock.policy.TimeBlock
import com.ezhou.rotblock.policy.formatMinuteOfDay

/** What "On a schedule" starts at before it is edited: weekday working hours. */
private val DEFAULT_BLOCK = TimeBlock(days = TimeBlock.WEEKDAYS, startMinute = 9 * 60, endMinute = 17 * 60)

/**
 * The "when is this rule live" section of the rule editor.
 *
 * Days live on each row rather than above the list, because that is the only arrangement in which
 * a second row earns its keep: shared days would only ever let you split one day into two
 * stretches, while per-row days express the case people actually have — weekday hours and weekend
 * hours that are not the same hours.
 *
 * Removing the last row collapses back to "Always", which is the same statement the empty schedule
 * makes everywhere else in the app: a rule with no hours written down is live all the time, never
 * live none of the time.
 */
@Composable
fun ScheduleSection(schedule: Schedule, onChange: (Schedule) -> Unit) {
    Text("When?", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = schedule.isAlwaysOn,
            onClick = { onChange(Schedule.ALWAYS) },
            label = { Text("Always") },
        )
        FilterChip(
            selected = !schedule.isAlwaysOn,
            onClick = { if (schedule.isAlwaysOn) onChange(Schedule(listOf(DEFAULT_BLOCK))) },
            label = { Text("On a schedule") },
        )
    }

    if (schedule.isAlwaysOn) return

    Spacer(Modifier.height(12.dp))
    schedule.blocks.forEachIndexed { index, block ->
        TimeBlockRow(
            block = block,
            // With one row left the ✕ would be a mode switch wearing a delete icon. "Always" is
            // right there and says what it does.
            onRemove = if (schedule.blocks.size > 1) {
                { onChange(Schedule(schedule.blocks.filterIndexed { i, _ -> i != index })) }
            } else {
                null
            },
            onChange = { updated ->
                onChange(Schedule(schedule.blocks.mapIndexed { i, b -> if (i == index) updated else b }))
            },
        )
        Spacer(Modifier.height(8.dp))
    }
    TextButton(onClick = { onChange(Schedule(schedule.blocks + DEFAULT_BLOCK)) }) {
        Text("Add hours")
    }
    Spacer(Modifier.height(2.dp))
    Hint("Outside these hours the rule does nothing — no block, no countdown, and nothing counts against the quota.")
}

@Composable
private fun TimeBlockRow(
    block: TimeBlock,
    onRemove: (() -> Unit)?,
    onChange: (TimeBlock) -> Unit,
) {
    var editing by remember { mutableStateOf<Edge?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                DAY_INITIALS.forEachIndexed { day, initial ->
                    val on = block.coversDay(day)
                    DayToggle(initial, on) {
                        val flipped = block.days xor (1 shl day)
                        // A block covering no days is a rule that never fires. Refusing the last
                        // deselection is the only way that stays impossible to express.
                        if (flipped != 0) onChange(block.copy(days = flipped))
                    }
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove these hours",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeField(block.startMinute) { editing = Edge.Start }
            Text(
                "  →  ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TimeField(block.endMinute) { editing = Edge.End }
            if (block.endMinute <= block.startMinute) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "next day",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    editing?.let { edge ->
        TimePickerDialog(
            initialMinuteOfDay = if (edge == Edge.Start) block.startMinute else block.endMinute,
            onDismiss = { editing = null },
            onPick = { minuteOfDay ->
                editing = null
                onChange(
                    if (edge == Edge.Start) {
                        block.copy(startMinute = minuteOfDay)
                    } else {
                        block.copy(endMinute = minuteOfDay)
                    },
                )
            },
        )
    }
}

private enum class Edge { Start, End }

@Composable
private fun DayToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .border(
                1.dp,
                if (selected) Color.Transparent else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            textAlign = TextAlign.Center,
        )
    }
}

/** A time, dressed as the chips it sits among, that opens the picker when tapped. */
@Composable
private fun TimeField(minuteOfDay: Int, onClick: () -> Unit) {
    Text(
        formatMinuteOfDay(minuteOfDay),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Material 3 has no `TimePickerDialog` at the Compose BOM this project pins, only the picker
 * itself, so this is the standard wrapping of one in an [AlertDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}
