package com.ezhou.rotblock.insights

import com.ezhou.rotblock.block.formatDuration
import com.ezhou.rotblock.data.PolicyDao
import com.ezhou.rotblock.data.UsageDao

/**
 * Per-period ms totals for the two rows shown at pause-request and block time.
 *
 * Raw wall-clock foreground time, not schedule-clipped: this is a behavioural mirror
 * ("you spent 3h here this week"), not quota accounting.
 */
data class DiscouragingStatsResult(
    val thisTargetToday: Long = 0L,
    val thisTargetYesterday: Long = 0L,
    val thisTargetThisWeek: Long = 0L,
    val thisTargetLastWeek: Long = 0L,
    val allBlockedToday: Long = 0L,
    val allBlockedYesterday: Long = 0L,
    val allBlockedThisWeek: Long = 0L,
    val allBlockedLastWeek: Long = 0L,
    val hasTarget: Boolean = false,
) {
    val hasHistory: Boolean
        get() = allBlockedThisWeek > 0 || allBlockedLastWeek > 0
}

/**
 * Folds raw sessions into per-period totals for the two discouraging-stats rows.
 *
 * [dayStarts] must be exactly 14 budget-day start timestamps in ascending order (as returned by
 * [recentDayStarts] with days=14). The fold is raw, not schedule-clipped: off-hours and paused
 * time count as time spent. A target that is in several groups is not double-counted in the
 * "all blocked" total because sessions are keyed by target id, not by rule.
 */
fun foldStats(
    targetId: String?,
    blockedTargetIds: Set<String>,
    sessions: List<TargetSession>,
    dayStarts: List<Long>,
    nowMs: Long,
): DiscouragingStatsResult {
    require(dayStarts.size == 14)
    val today = dayStarts[13]
    val yesterday = dayStarts[12]
    val thisWeekStart = dayStarts[7]
    val lastWeekStart = dayStarts[0]
    val lastWeekEnd = dayStarts[7]

    var targetToday = 0L; var targetYesterday = 0L
    var targetThisWeek = 0L; var targetLastWeek = 0L
    var allToday = 0L; var allYesterday = 0L
    var allThisWeek = 0L; var allLastWeek = 0L

    for (s in sessions) {
        val isTarget = targetId != null && s.targetId == targetId
        val isBlocked = s.targetId in blockedTargetIds
        if (!isTarget && !isBlocked) continue

        fun slice(start: Long, end: Long): Long {
            val lo = maxOf(s.startMs, start)
            val hi = minOf(s.endMs, end)
            return if (hi > lo) hi - lo else 0L
        }

        val todayMs = slice(today, nowMs)
        val yestMs = slice(yesterday, today)
        val weekMs = slice(thisWeekStart, nowMs)
        val lastWkMs = slice(lastWeekStart, lastWeekEnd)

        if (isTarget) {
            targetToday += todayMs; targetYesterday += yestMs
            targetThisWeek += weekMs; targetLastWeek += lastWkMs
        }
        if (isBlocked) {
            allToday += todayMs; allYesterday += yestMs
            allThisWeek += weekMs; allLastWeek += lastWkMs
        }
    }

    return DiscouragingStatsResult(
        thisTargetToday = targetToday,
        thisTargetYesterday = targetYesterday,
        thisTargetThisWeek = targetThisWeek,
        thisTargetLastWeek = targetLastWeek,
        allBlockedToday = allToday,
        allBlockedYesterday = allYesterday,
        allBlockedThisWeek = allThisWeek,
        allBlockedLastWeek = allLastWeek,
        hasTarget = targetId != null,
    )
}

/**
 * Reads sessions and the enabled-policy target set from the database, then runs [foldStats].
 *
 * Single source of truth used by both the pause-request ViewModel and the block overlay, so they
 * cannot disagree on the numbers.
 */
suspend fun loadDiscouragingStats(
    usageDao: UsageDao,
    policyDao: PolicyDao,
    dayStartHour: Int,
    nowMs: Long,
    targetId: String?,
): DiscouragingStatsResult {
    val dayStarts = recentDayStarts(nowMs, dayStartHour, 14)
    val sessions = usageDao.since(dayStarts.first())
        .map { TargetSession(it.targetId, it.startMs, it.endMs) }
    val blockedTargetIds = policyDao.getAll()
        .filter { it.enabled }
        .flatMap { it.chargedTargets }
        .toSet()
    return foldStats(targetId, blockedTargetIds, sessions, dayStarts, nowMs)
}

/**
 * Compact two-line string for the block overlay: week figures with deltas only.
 *
 * Returns empty when there is no history, so the caller can skip the [TextView] update entirely
 * rather than showing a row of zeros. The target row is omitted when there is no target or when
 * the target has no recorded usage in either week.
 */
fun formatCompactStats(stats: DiscouragingStatsResult, targetLabel: String?): String {
    if (!stats.hasHistory) return ""
    return buildList {
        if (stats.hasTarget && targetLabel != null &&
            (stats.thisTargetThisWeek > 0 || stats.thisTargetLastWeek > 0)
        ) {
            add(weekLine(targetLabel, stats.thisTargetThisWeek, stats.thisTargetLastWeek))
        }
        add(weekLine("All blocked", stats.allBlockedThisWeek, stats.allBlockedLastWeek))
    }.joinToString("\n")
}

private fun weekLine(label: String, thisWeek: Long, lastWeek: Long): String {
    val base = "$label: ${formatDuration(thisWeek)} this week"
    val diff = thisWeek - lastWeek
    return when {
        diff > 0 -> "$base  ▲ ${formatDuration(diff)} more"
        diff < 0 -> "$base  ▼ ${formatDuration(-diff)} less"
        else -> base
    }
}
