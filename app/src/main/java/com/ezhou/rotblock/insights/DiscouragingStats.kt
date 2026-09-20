package com.ezhou.rotblock.insights

import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.data.AuditDao
import com.ezhou.rotblock.data.PolicyDao
import com.ezhou.rotblock.data.UsageDao

/** Days of usage the interstitial charts and averages cover. Matches usage retention. */
const val STATS_DAYS = 30

/**
 * Per-day ms totals for the interstitial screens.
 *
 * Raw wall-clock foreground time, not schedule-clipped: this is a behavioural mirror
 * ("you spent 3h here today"), not quota accounting. Daily series are oldest-first and
 * always [STATS_DAYS] long; a day with no sessions is 0.
 */
data class DiscouragingStatsResult(
    val thisTargetDailyMs: List<Long> = List(STATS_DAYS) { 0L },
    val allBlockedDailyMs: List<Long> = List(STATS_DAYS) { 0L },
    val emergencyDailyMs: List<Long> = List(STATS_DAYS) { 0L },
    val hasTarget: Boolean = false,
) {
    val thisTargetToday: Long get() = thisTargetDailyMs.lastOrNull() ?: 0L
    val allBlockedToday: Long get() = allBlockedDailyMs.lastOrNull() ?: 0L
    val thisTargetAvg30Ms: Long get() = mean(thisTargetDailyMs)
    val emergencyAvg30Ms: Long get() = mean(emergencyDailyMs)
    val hasHistory: Boolean
        get() = allBlockedDailyMs.any { it > 0 } || thisTargetDailyMs.any { it > 0 }
    val hasEmergencyHistory: Boolean
        get() = emergencyDailyMs.any { it > 0 }
}

private fun mean(xs: List<Long>): Long = if (xs.isEmpty()) 0L else xs.sum() / xs.size

/**
 * Folds raw sessions into per-day totals for the interstitial charts.
 *
 * [dayStarts] must be [STATS_DAYS] budget-day starts in ascending order. The fold is raw,
 * not schedule-clipped: off-hours and paused time count as time spent. A target that is in
 * several groups is not double-counted in the "all blocked" total because sessions are keyed
 * by target id, not by rule.
 */
fun foldStats(
    targetId: String?,
    blockedTargetIds: Set<String>,
    sessions: List<TargetSession>,
    dayStarts: List<Long>,
    nowMs: Long,
    emergencyDailyMs: List<Long> = List(dayStarts.size) { 0L },
): DiscouragingStatsResult {
    require(dayStarts.size == STATS_DAYS)
    val buckets = bucketByDay(sessions, dayStarts, nowMs)
    val targetDaily = buckets.map { b ->
        if (targetId != null) b.perTarget[targetId] ?: 0L else 0L
    }
    val allDaily = buckets.map { b ->
        var sum = 0L
        for ((id, ms) in b.perTarget) if (id in blockedTargetIds) sum += ms
        sum
    }
    val emergency = if (emergencyDailyMs.size == STATS_DAYS) emergencyDailyMs else List(STATS_DAYS) { 0L }
    return DiscouragingStatsResult(
        thisTargetDailyMs = targetDaily,
        allBlockedDailyMs = allDaily,
        emergencyDailyMs = emergency,
        hasTarget = targetId != null,
    )
}

/**
 * Minutes of emergency skip per day, oldest-first.
 *
 * An emergency skip is two audit rows written together: [AuditType.EMERGENCY_SKIP] and the
 * [AuditType.PAUSE_STARTED] it unlocks, sharing a timestamp. Duration lives on the pause
 * row. A skip with no matching pause contributes nothing rather than inventing a length.
 */
fun foldEmergencyMinutes(
    events: List<AuditEvent>,
    dayStarts: List<Long>,
    nowMs: Long,
): List<Long> {
    require(dayStarts.size == STATS_DAYS)
    val skipTs = HashSet<Long>()
    for (e in events) if (e.type == AuditType.EMERGENCY_SKIP) skipTs.add(e.tsMs)
    val durations = ArrayList<Pair<Long, Long>>()
    for (e in events) {
        if (e.type != AuditType.PAUSE_STARTED || e.tsMs !in skipTs) continue
        durations.add(e.tsMs to (e.durationSeconds ?: 0) * 1000L)
    }
    return dayStarts.mapIndexed { i, start ->
        val end = dayStarts.getOrNull(i + 1) ?: nowMs
        var sum = 0L
        for ((ts, ms) in durations) if (ts in start until end) sum += ms
        sum
    }
}

/**
 * Reads sessions, the enabled-policy target set, and emergency skips, then runs [foldStats].
 *
 * Single source of truth used by the pause-request screen, the friction countdown and the
 * block overlay, so they cannot disagree on the numbers.
 */
suspend fun loadDiscouragingStats(
    usageDao: UsageDao,
    policyDao: PolicyDao,
    auditDao: AuditDao,
    dayStartHour: Int,
    nowMs: Long,
    targetId: String?,
): DiscouragingStatsResult {
    val dayStarts = recentDayStarts(nowMs, dayStartHour, STATS_DAYS)
    val sessions = usageDao.since(dayStarts.first())
        .map { TargetSession(it.targetId, it.startMs, it.endMs) }
    val blockedTargetIds = policyDao.getAll()
        .filter { it.enabled }
        .flatMap { it.chargedTargets }
        .toSet()
    val emergency = foldEmergencyMinutes(auditDao.since(dayStarts.first()), dayStarts, nowMs)
    return foldStats(targetId, blockedTargetIds, sessions, dayStarts, nowMs, emergency)
}

/** Minute-resolution delta of today against the 30-day mean, for the large more/less line. */
fun deltaMinutes(todayMs: Long, avgMs: Long): Long =
    (todayMs / 60_000L) - (avgMs / 60_000L)

/**
 * Hours-and-minutes without seconds. Stats are a day's worth of time; seconds there are noise.
 */
fun formatHoursMinutes(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

data class YTick(val valueMs: Long, val label: String)

/**
 * Y-axis for the 30-day bars. Usage charts always tick in hours. Emergency charts tick every
 * 15 minutes until a day would need more than six of those lines, then hours.
 */
fun yTicks(maxMs: Long, quarterHour: Boolean): Pair<Long, List<YTick>> {
    val hour = 3_600_000L
    val quarter = 15 * 60_000L
    if (quarterHour && maxMs <= 90 * 60_000L) {
        val top = maxOf(quarter, ((maxMs + quarter - 1) / quarter) * quarter)
        val ticks = ArrayList<YTick>()
        var v = quarter
        while (v <= top) {
            ticks.add(YTick(v, formatYLabel(v)))
            v += quarter
        }
        return top to ticks
    }
    val top = maxOf(hour, ((maxMs + hour - 1) / hour) * hour)
    val ticks = ArrayList<YTick>()
    var v = hour
    while (v <= top) {
        ticks.add(YTick(v, formatYLabel(v)))
        v += hour
    }
    return top to ticks
}

private fun formatYLabel(ms: Long): String {
    val minutes = ms / 60_000L
    return if (minutes % 60L == 0L && minutes >= 60L) "${minutes / 60}h" else "${minutes}m"
}
