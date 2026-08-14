package com.ezhou.rotblock.insights

import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.quota.UsageSession

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val TREND_WINDOW_DAYS = 28L
private const val MIN_POLICY_AGE_DAYS = 14L
private const val MIN_DAYS_WITH_DATA = 4

/**
 * What a rule has actually done, read straight off the audit log and the usage ledger. No score:
 * see policy/PolicyOrder.kt -- there is no usage recorded before a rule existed to compare
 * against, so [firstHalfDailyMeanMs] and [secondHalfDailyMeanMs] are a trend within the retention
 * window, not a before/after.
 */
data class RuleEffect(
    val blocksShown: Int,
    val pausesPastIt: Int,
    val weakenings: Int,
    val firstHalfDailyMeanMs: Long?,
    val secondHalfDailyMeanMs: Long?,
    val hasEnoughHistory: Boolean,
) {
    /** A rule written this morning has nothing to report, and should say nothing rather than zero. */
    val hasAnythingToSay: Boolean
        get() = hasEnoughHistory || blocksShown > 0 || pausesPastIt > 0
}

/** Pure fold over data the caller already loaded -- no Android, no clock of its own. */
fun effectOf(
    policy: Policy,
    sessions: List<UsageSession>,
    events: List<AuditEvent>,
    nowMs: Long,
): RuleEffect {
    val ownEvents = events.filter { it.targetId == policy.id }
    val blocksShown = ownEvents.count { it.type == AuditType.BLOCK_SHOWN }
    val pausesPastIt = ownEvents.count { it.type == AuditType.PAUSE_STARTED }
    val weakenings = ownEvents.count {
        it.type == AuditType.WEAKENING_APPLIED || it.type == AuditType.POLICY_STRENGTHENED
    }

    val windowMs = TREND_WINDOW_DAYS * DAY_MS
    val windowStart = nowMs - windowMs
    val midpoint = windowStart + windowMs / 2
    val firstHalf = dailyMeanMs(sessions, windowStart, midpoint)
    val secondHalf = dailyMeanMs(sessions, midpoint, nowMs)

    val oldEnough = nowMs - policy.createdAtMs >= MIN_POLICY_AGE_DAYS * DAY_MS

    return RuleEffect(
        blocksShown = blocksShown,
        pausesPastIt = pausesPastIt,
        weakenings = weakenings,
        firstHalfDailyMeanMs = firstHalf,
        secondHalfDailyMeanMs = secondHalf,
        hasEnoughHistory = oldEnough && firstHalf != null && secondHalf != null,
    )
}

/**
 * Mean tracked time per day within `[start, end)`, divided by the number of days that actually
 * have a session in them rather than by the width of the range -- a stretch with no usage at all
 * must not silently drag the mean toward zero. Null when fewer than [MIN_DAYS_WITH_DATA] days
 * have anything on them, which is also the sparse-half signal [effectOf] gates the trend on.
 *
 * Days are fixed 24h ticks from `start`: this is a trend, not the quota's budget day, so it has no
 * need of a day-start hour or timezone.
 */
private fun dailyMeanMs(sessions: List<UsageSession>, start: Long, end: Long): Long? {
    val perDay = HashMap<Long, Long>()
    for (session in sessions) {
        var segStart = maxOf(session.startMs, start)
        val hi = minOf(session.endMs, end)
        while (segStart < hi) {
            val dayIndex = (segStart - start) / DAY_MS
            val dayEnd = minOf(start + (dayIndex + 1) * DAY_MS, hi)
            perDay[dayIndex] = (perDay[dayIndex] ?: 0L) + (dayEnd - segStart)
            segStart = dayEnd
        }
    }
    if (perDay.size < MIN_DAYS_WITH_DATA) return null
    return perDay.values.sum() / perDay.size
}
