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

    /** The user asked Setup to kick the accessibility service component back into binding. */
    ENFORCEMENT_REVIVAL_ATTEMPTED,

    /**
     * rotblock put itself back into Android's enabled-accessibility-services list after the phone
     * dropped it. Worth its own entry rather than a [SERVICE_ENABLED]: the gap it ends is one
     * nothing else in the app could have closed, and it should be visible that the app did it
     * rather than the user. See [com.ezhou.rotblock.setup.AccessibilityEnrolment].
     */
    ENFORCEMENT_RE_ENROLLED,

    /** The service caught an exception it swallowed to stay alive. */
    INTERNAL_ERROR,

    /**
     * The enforcement loop re-evaluated faster than any user action can explain, and again when it
     * stopped. See [com.ezhou.rotblock.service.EvaluationRate].
     *
     * Diagnostic rather than accountability, like [INTERNAL_ERROR], and here for the same reason
     * that one is: this log is the only store in the app that outlives the process, and the state
     * a storm has to be diagnosed from is gone by the time anyone can go looking for it. The
     * [com.ezhou.rotblock.service.EnforcementStatus] trail cannot hold it -- a storm writes two
     * entries per cycle and they alternate, so they never collapse, and 300 entries are spent in
     * six seconds.
     */
    EVALUATION_STORM,

    /**
     * Brackets a period when every enabled rule was blocking regardless of quota, schedule or
     * pause -- a live workout overrides them all. A gap in the log between these two should read
     * as "a workout was running", not as a mystery.
     */
    WORKOUT_MODE_STARTED,
    WORKOUT_MODE_ENDED,
}

/**
 * Append-only, with one exception that is deliberately shaped so it cannot become a tidy-up.
 *
 * There is no update path anywhere, and the only delete is
 * [com.ezhou.rotblock.audit.AUDIT_RETENTION_MS]: a blanket cutoff on age, applied to every event
 * equally. That distinction is the whole of it. A log you can quietly tidy up is not an
 * accountability record -- but "tidying up" means reaching in and removing *the inconvenient
 * event*, and a rule that takes only a timestamp cannot be aimed at one. Nothing in the app
 * deletes by id, by type, or by target, and nothing should be added that does.
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
