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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.audit.AuditType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FORMAT = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())

/**
 * The accountability view.
 *
 * Phase 6 adds charts on top of this data. Even as a plain list it does the main job: every
 * relaxation of the rules is here, in order, with the reason you typed at the time.
 */
@Composable
fun LogScreen(viewModel: RotblockViewModel) {
    val events by viewModel.recentEvents.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Everything that changed the rules",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Append-only. There is no way to edit or delete an entry, including from here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
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
    AuditType.EMERGENCY_SKIP -> MaterialTheme.colorScheme.error
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
