package com.ezhou.rotblock.insights

import com.ezhou.rotblock.pause.dayStartMs
import com.ezhou.rotblock.policy.Policy
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

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

/**
 * 90 and 365 days are about a pixel per bar, so those ranges draw one bar per week. 7 and 30
 * stay one bar per day.
 */
fun chartIsWeekly(days: Int): Boolean = days == 90 || days == 365

/**
 * Groups ascending [days] into buckets of [weekLength] consecutive entries, aligned so the newest
 * bucket is a full week ending on the last day. The oldest bucket keeps the remainder and may be
 * shorter.
 *
 * A week here is that many rotblock budget-days in a row, not a calendar week. A calendar week
 * would park today in the middle of a bar, and the last bar has to be the day the range ends on.
 */
fun <T> foldIntoWeeks(days: List<T>, weekLength: Int = 7, combine: (List<T>) -> T): List<T> {
    if (days.isEmpty()) return emptyList()
    val out = ArrayList<T>((days.size + weekLength - 1) / weekLength)
    var index = days.size % weekLength
    if (index != 0) out.add(combine(days.subList(0, index)))
    while (index < days.size) {
        val end = index + weekLength
        out.add(combine(days.subList(index, end)))
        index = end
    }
    return out
}

/** Sums per-target time. The bucket's [DayBucket.dayStartMs] is its oldest day. */
fun foldBucketsIntoWeeks(buckets: List<DayBucket>, weekLength: Int = 7): List<DayBucket> =
    foldIntoWeeks(buckets, weekLength) { days ->
        val totals = HashMap<String, Long>()
        for (day in days) {
            for ((key, ms) in day.perTarget) {
                if (ms > 0L) totals[key] = (totals[key] ?: 0L) + ms
            }
        }
        DayBucket(days.first().dayStartMs, totals)
    }

/** The same cut as [foldBucketsIntoWeeks], for the escape-hatch counts that share its bars. */
fun foldCountsIntoWeeks(counts: List<Int>, weekLength: Int = 7): List<Int> =
    foldIntoWeeks(counts, weekLength) { slice -> slice.sum() }

/**
 * Target ids any current rule covers, enabled or not, including a group's members.
 *
 * Today's rules colour every day in the range. A disabled rule still counts: switching one off
 * must not repaint its history as time nothing covers.
 */
fun rotblockedKeys(policies: List<Policy>): Set<String> =
    policies.flatMap { it.chargedTargets }.toSet()

/**
 * The named series for one loaded range: the three rotblocked keys with the most time, then the
 * three unblocked ones. Everyone else shares that side's "other" colour.
 *
 * Rank is over the whole of [buckets], with the key as a tiebreak. Re-running this on a single
 * tapped day would move the colours under the finger, so the caller holds the result for as long
 * as the range stays on screen.
 */
fun screenTimeSlots(buckets: List<DayBucket>, rotblocked: Set<String>, named: Int = 3): ScreenTimeSlots {
    val totals = HashMap<String, Long>()
    for (bucket in buckets) {
        for ((key, ms) in bucket.perTarget) {
            if (ms > 0L) totals[key] = (totals[key] ?: 0L) + ms
        }
    }
    val ranked = totals.entries.sortedWith(
        compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key },
    )
    return ScreenTimeSlots(
        warm = ranked.asSequence().map { it.key }.filter { it in rotblocked }.take(named).toList(),
        cool = ranked.asSequence().map { it.key }.filter { it !in rotblocked }.take(named).toList(),
    )
}

data class ScreenTimeSlots(val warm: List<String>, val cool: List<String>)

/**
 * Bands of one bar, baseline first.
 *
 * This order — warm slots, other warm, cool slots, other cool — is the adjacency the warm/cool
 * palette was checked for. [seriesSlot] keeps a key in its named slot even on a day when a later
 * slot is taller; sorting a bar by height would put hues next to each other that were never checked.
 * The chart paints the first of these on the baseline and stacks upward, so the list order is the
 * order on screen.
 */
enum class SegmentSlot {
    WARM_0, WARM_1, WARM_2, OTHER_WARM, COOL_0, COOL_1, COOL_2, OTHER_COOL,
}

data class ScreenSegment(val slot: SegmentSlot, val ms: Long)

private val WARM_NAMED = listOf(SegmentSlot.WARM_0, SegmentSlot.WARM_1, SegmentSlot.WARM_2)
private val COOL_NAMED = listOf(SegmentSlot.COOL_0, SegmentSlot.COOL_1, SegmentSlot.COOL_2)

/**
 * Which band [key] keeps for a range whose named slots are [slots].
 *
 * A ranked key keeps its slot. Any other rotblocked key is other-warm, and whatever is left is
 * other-cool. Rotblocked is checked before the cool slots, so a key a rule covers cannot be drawn
 * as unblocked just because a stale slot list still names it there.
 */
fun seriesSlot(key: String, slots: ScreenTimeSlots, rotblocked: Set<String>): SegmentSlot {
    val warmAt = slots.warm.indexOf(key)
    if (warmAt in WARM_NAMED.indices) return WARM_NAMED[warmAt]
    if (key in rotblocked) return SegmentSlot.OTHER_WARM
    val coolAt = slots.cool.indexOf(key)
    if (coolAt in COOL_NAMED.indices) return COOL_NAMED[coolAt]
    return SegmentSlot.OTHER_COOL
}

fun screenTimeSegments(
    perTarget: Map<String, Long>,
    slots: ScreenTimeSlots,
    rotblocked: Set<String>,
): List<ScreenSegment> {
    val totals = LongArray(SegmentSlot.entries.size)
    for ((key, ms) in perTarget) {
        if (ms <= 0L) continue
        totals[seriesSlot(key, slots, rotblocked).ordinal] += ms
    }
    return SegmentSlot.entries.mapNotNull { slot ->
        val ms = totals[slot.ordinal]
        if (ms > 0L) ScreenSegment(slot, ms) else null
    }
}

/** Rotblocked and unblocked totals over the same keys the chart stacks. */
data class TimeSplit(val rotblockedMs: Long, val unblockedMs: Long) {
    val totalMs: Long get() = rotblockedMs + unblockedMs
}

fun splitScreenTime(buckets: List<DayBucket>, rotblocked: Set<String>): TimeSplit {
    var warm = 0L
    var cool = 0L
    for (bucket in buckets) {
        for ((key, ms) in bucket.perTarget) {
            if (ms <= 0L) continue
            if (key in rotblocked) warm += ms else cool += ms
        }
    }
    return TimeSplit(warm, cool)
}

fun splitScreenTime(perTarget: Map<String, Long>, rotblocked: Set<String>): TimeSplit =
    splitScreenTime(listOf(DayBucket(0L, perTarget)), rotblocked)

/**
 * Whole percentages that add to 100 whenever there is any time. A side that has time does not
 * round down to 0%: the bar would still show it, and a label of 0% next to a visible sliver is
 * the readout lying.
 */
fun splitPercents(split: TimeSplit): Pair<Int, Int> {
    val total = split.totalMs
    if (total <= 0L) return 0 to 0
    if (split.rotblockedMs <= 0L) return 0 to 100
    if (split.unblockedMs <= 0L) return 100 to 0
    val rot = ((split.rotblockedMs * 100.0) / total).roundToInt().coerceIn(1, 99)
    return rot to (100 - rot)
}
