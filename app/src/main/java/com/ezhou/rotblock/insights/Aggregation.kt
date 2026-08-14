package com.ezhou.rotblock.insights

import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.pause.dayStartMs
import java.util.Calendar
import java.util.TimeZone

/** A usage session tagged with what it was spent on. */
data class TargetSession(val targetId: String, val startMs: Long, val endMs: Long)

/** One day's totals, keyed by target id. */
data class DayBucket(val dayStartMs: Long, val perTarget: Map<String, Long>) {
    val totalMs: Long get() = perTarget.values.sum()
}

/**
 * Ascending list of the last [days] budget-day boundaries, ending with the day containing [nowMs].
 *
 * Built by stepping a [Calendar] rather than subtracting 24h so a DST change does not smear every
 * bucket by an hour.
 */
fun recentDayStarts(
    nowMs: Long,
    dayStartHour: Int,
    days: Int,
    tz: TimeZone = TimeZone.getDefault(),
): List<Long> {
    val today = dayStartMs(nowMs, dayStartHour, tz)
    val cal = Calendar.getInstance(tz).apply { timeInMillis = today }
    val out = ArrayList<Long>(days)
    repeat(days) {
        out.add(cal.timeInMillis)
        cal.add(Calendar.DAY_OF_YEAR, -1)
    }
    return out.reversed()
}

/**
 * Splits sessions into per-day, per-target totals.
 *
 * A session that runs across a day boundary is divided between the days it touches rather than
 * being credited whole to either -- a scroll from 3:50am to 4:10am with a 4am rollover belongs
 * partly to each.
 */
fun bucketByDay(
    sessions: List<TargetSession>,
    dayStarts: List<Long>,
    endMs: Long,
): List<DayBucket> {
    if (dayStarts.isEmpty()) return emptyList()

    val bounds = dayStarts.mapIndexed { index, start ->
        val end = dayStarts.getOrNull(index + 1) ?: endMs
        start to end
    }

    return bounds.map { (start, end) ->
        val perTarget = HashMap<String, Long>()
        for (session in sessions) {
            val lo = maxOf(session.startMs, start)
            val hi = minOf(session.endMs, end)
            if (hi > lo) perTarget[session.targetId] = (perTarget[session.targetId] ?: 0L) + (hi - lo)
        }
        DayBucket(start, perTarget)
    }
}

/** Counts per day, for the escape-hatch chart. */
fun countByDay(timestamps: List<Long>, dayStarts: List<Long>, endMs: Long): List<Int> {
    if (dayStarts.isEmpty()) return emptyList()
    return dayStarts.mapIndexed { index, start ->
        val end = dayStarts.getOrNull(index + 1) ?: endMs
        timestamps.count { it in start until end }
    }
}

/** Milliseconds spent per [Category] and per [Productivity], over a set of sessions. */
data class KindTotals(val byCategory: Map<Category, Long>, val byProductivity: Map<Productivity, Long>)

/**
 * The package behind a target id, when one can be read off the id itself with no device lookup.
 *
 * An app target's matcher already is the package. A feed target's matcher is a [FeedRegistry] key,
 * which does resolve to a package. A website target's matcher is a bare host -- resolving that to
 * an installed app needs [android.content.pm.PackageManager] (see `TargetIcon.kt`), which this pure
 * fold has no access to and must not reach for, so it stays unresolved rather than guessing.
 */
private fun packageFor(targetId: String): String? {
    val kind = targetId.substringBefore(':', "")
    val matcher = targetId.substringAfter(':', "")
    return when (kind) {
        "app" -> matcher
        "feed" -> FeedRegistry.specs.firstOrNull { it.key == matcher }?.packageName
        else -> null
    }
}

/**
 * Folds sessions into totals by kind, so Insights can report "of which N distracting" alongside
 * the per-target breakdown. [classify] is injected rather than read from [AppCatalog] directly so
 * this stays testable with no bundled asset involved.
 */
fun classifyTotals(sessions: List<TargetSession>, classify: (String) -> Classification): KindTotals {
    val byCategory = HashMap<Category, Long>()
    val byProductivity = HashMap<Productivity, Long>()
    for (session in sessions) {
        val durationMs = session.endMs - session.startMs
        if (durationMs <= 0) continue
        val classification = packageFor(session.targetId)?.let(classify) ?: Classification()
        byCategory[classification.category] = (byCategory[classification.category] ?: 0L) + durationMs
        byProductivity[classification.productivity] =
            (byProductivity[classification.productivity] ?: 0L) + durationMs
    }
    return KindTotals(byCategory, byProductivity)
}
