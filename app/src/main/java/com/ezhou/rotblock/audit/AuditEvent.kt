package com.ezhou.rotblock.audit

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Everything that changes how much rot gets through.
 *
 * The point of the log is to make leaning on the escape hatches visible to you later, so the
 * events that matter most are the ones that *weaken* enforcement: pauses, emergency skips, and
 * policy edits. Blocks are recorded too, but as context rather than as the headline.
 */
enum class AuditType {
    POLICY_CREATED,
    POLICY_DELETED,
    POLICY_STRENGTHENED,
    WEAKENING_QUEUED,
    WEAKENING_APPLIED,
    WEAKENING_CANCELLED,
    PAUSE_STARTED,
    PAUSE_ENDED_EARLY,
    PAUSE_EXPIRED,
    COOLDOWN_STARTED,
    COOLDOWN_ABANDONED,
    EMERGENCY_SKIP,
    QUOTA_EXHAUSTED,
    BLOCK_SHOWN,

    /**
     * Enforcement stopped or started. Recorded on accessibility service unbind so that turning
     * rotblock off shows up in the log as an explicit gap rather than as silence.
     */
    SERVICE_DISABLED,
    SERVICE_ENABLED,

    /** The service caught an exception it swallowed to stay alive. */
    INTERNAL_ERROR,
}

/**
 * Append-only. There is deliberately no update or delete path anywhere above the DAO -- a log you
 * can quietly tidy up is not an accountability record.
 */
@Entity(tableName = "audit_events")
data class AuditEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsMs: Long,
    val type: AuditType,
    /** Policy/target id this concerns, or null for global events. */
    val targetId: String? = null,
    /** Human-readable one-liner shown in the log UI. */
    val detail: String = "",
    /** Free text the user typed, e.g. the reason for an emergency skip. */
    val userReason: String? = null,
    /** Duration in seconds where meaningful (pause length, cooldown length). */
    val durationSeconds: Int? = null,
    /** True when the action went past a configured budget. */
    val overBudget: Boolean = false,
)
