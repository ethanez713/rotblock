package com.ezhou.rotblock.policy

import java.util.Calendar
import java.util.TimeZone

private const val MINUTES_PER_DAY = 24 * 60
private const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY

/** How far either side of now [Schedule] looks for occurrences. A week plus slack on both ends. */
private const val LOOK_BACK_DAYS = -1
private const val LOOK_AHEAD_DAYS = 8

/**
 * One stretch of the week a rule is live for.
 *
 * [days] is a bitmask with bit 0 = Monday through bit 6 = Sunday, naming the days a stretch
 * *starts* on. [startMinute] and [endMinute] are minutes since local midnight.
 *
 * An [endMinute] at or before [startMinute] wraps past midnight into the next day, which is what
 * makes "10pm to 7am" expressible at all. Equal ends mean a full 24 hours rather than nothing:
 * where a degenerate block could be read either way, it has to be read as the one that keeps
 * blocking.
 */
data class TimeBlock(val days: Int, val startMinute: Int, val endMinute: Int) {

    /** Minutes this block lasts. Never zero — see the class note on equal ends. */
    val durationMinutes: Int
        get() = if (endMinute > startMinute) {
            endMinute - startMinute
        } else {
            endMinute + MINUTES_PER_DAY - startMinute
        }

    fun coversDay(bitIndex: Int): Boolean = (days shr bitIndex) and 1 == 1

    fun encode(): String = "$days:$startMinute-$endMinute"

    companion object {
        const val ALL_DAYS = 0b111_1111
        const val WEEKDAYS = 0b001_1111
        const val WEEKENDS = 0b110_0000

        fun decode(text: String): TimeBlock? {
            val colon = text.indexOf(':')
            val dash = text.indexOf('-', startIndex = colon + 1)
            if (colon <= 0 || dash <= colon) return null
            val days = text.substring(0, colon).toIntOrNull() ?: return null
            val start = text.substring(colon + 1, dash).toIntOrNull() ?: return null
            val end = text.substring(dash + 1).toIntOrNull() ?: return null
            // A block covering no days at all would silently switch a rule off forever, so it is
            // not a block — it is a corrupt row, and decoding rejects the whole schedule for it.
            if (days !in 1..ALL_DAYS) return null
            if (start !in 0 until MINUTES_PER_DAY || end !in 0 until MINUTES_PER_DAY) return null
            return TimeBlock(days, start, end)
        }
    }
}

/**
 * When a policy is live, as a set of weekly stretches.
 *
 * An empty schedule means always on, and that is the only reading an empty schedule may have: it
 * is what an untouched rule stores, what a failed decode falls back to, and what a schedule
 * emptied of its last block collapses to. All three have to resolve toward *more* enforcement.
 *
 * Everything here is a pure function of the wall clock, which is what lets a schedule exist at all
 * under the fail-closed invariant. There is no "the window opened" event to miss and no timer to
 * lose: outside its hours a rule simply folds to allow, and it folds back the instant the clock
 * says so. The one thing this does owe the caller is [nextBoundaryAfter] — a window opening fires
 * no event, exactly like a pause elapsing, so something has to be awake to notice it.
 */
data class Schedule(val blocks: List<TimeBlock> = emptyList()) {

    val isAlwaysOn: Boolean get() = blocks.isEmpty()

    fun isActiveAt(nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Boolean {
        if (isAlwaysOn) return true
        return occurrences(nowMs, tz).any { nowMs >= it.first && nowMs < it.second }
    }

    /**
     * The soonest moment after [nowMs] at which [isActiveAt] could give a different answer.
     *
     * Every edge of every nearby occurrence counts, whether or not it is a real transition —
     * overlapping blocks produce edges that change nothing. That is deliberate: this feeds the
     * ticker, and re-checking a moment early costs one wakeup while missing a window opening
     * leaves a rule dormant for hours.
     */
    fun nextBoundaryAfter(nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        if (isAlwaysOn) return null
        var soonest: Long? = null
        for ((start, end) in occurrences(nowMs, tz)) {
            for (edge in longArrayOf(start, end)) {
                if (edge > nowMs && (soonest == null || edge < soonest!!)) soonest = edge
            }
        }
        return soonest
    }

    /**
     * When the current stretch ends, or null if the schedule is always on or not active now.
     *
     * Not simply the end of the occurrence containing [nowMs]: two overlapping blocks hand over to
     * each other, and the rule stays live across the seam. So this is the first edge the schedule
     * is genuinely inactive at, which is what a block screen can honestly count down to.
     */
    fun activeUntil(nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        if (isAlwaysOn || !isActiveAt(nowMs, tz)) return null
        return occurrences(nowMs, tz)
            .map { it.second }
            .filter { it > nowMs }
            .sorted()
            .firstOrNull { !isActiveAt(it, tz) }
    }

    /**
     * How much of the week this schedule is live for, as a fraction of 1.
     *
     * Always-on is 1.0 and nothing can be 0.0 — a block covering no days is rejected at decode, so
     * every schedule that exists covers at least some minutes. Overlapping blocks are counted once,
     * which is the whole reason this goes through the minute grid rather than summing durations.
     */
    fun coverageFraction(): Double {
        if (isAlwaysOn) return 1.0
        return activeMinutes().count { it } / MINUTES_PER_WEEK.toDouble()
    }

    /** True when every minute of the week [other] covers is also covered here. */
    fun coversAllOf(other: Schedule): Boolean {
        if (isAlwaysOn) return true
        if (other.isAlwaysOn) return false
        val mine = activeMinutes()
        val theirs = other.activeMinutes()
        return mine.indices.all { !theirs[it] || mine[it] }
    }

    fun encode(): String = blocks.joinToString(",") { it.encode() }

    /** "Mon–Fri 10am–6pm · Sat 12pm–4pm", or null when always on. */
    fun describe(): String? {
        if (isAlwaysOn) return null
        return blocks.joinToString(" · ") {
            "${describeDays(it.days)} ${formatMinuteOfDay(it.startMinute)}–${formatMinuteOfDay(it.endMinute)}"
        }
    }

    /**
     * Which minutes of the week this covers, Monday 00:00 first.
     *
     * A flat 10,080-entry grid rather than interval arithmetic, because the only questions asked of
     * it — is this a superset, do these overlap — are the ones interval arithmetic gets subtly
     * wrong around wrapping windows, and the grid is small enough that being obviously right is
     * the cheaper trade. It is only built when a policy is edited.
     */
    private fun activeMinutes(): BooleanArray {
        val grid = BooleanArray(MINUTES_PER_WEEK)
        if (isAlwaysOn) return BooleanArray(MINUTES_PER_WEEK) { true }
        for (block in blocks) {
            for (day in 0 until 7) {
                if (!block.coversDay(day)) continue
                val from = day * MINUTES_PER_DAY + block.startMinute
                repeat(block.durationMinutes) { grid[(from + it) % MINUTES_PER_WEEK] = true }
            }
        }
        return grid
    }

    /**
     * Every occurrence starting within a week and a bit of [nowMs], as (startMs, endMs) pairs.
     *
     * Built by walking a [Calendar] a day at a time and setting the wall-clock hour on each, not by
     * adding milliseconds: a schedule is written in local time, so "10pm to 7am" has to stay 10pm
     * to 7am across a daylight-saving change rather than sliding by an hour.
     */
    private fun occurrences(nowMs: Long, tz: TimeZone): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>(blocks.size * 2)
        val day = Calendar.getInstance(tz).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, LOOK_BACK_DAYS)
        }
        repeat(LOOK_AHEAD_DAYS - LOOK_BACK_DAYS) {
            val bitIndex = bitIndexFor(day.get(Calendar.DAY_OF_WEEK))
            for (block in blocks) {
                if (!block.coversDay(bitIndex)) continue
                val start = day.atMinuteOfDay(block.startMinute, dayOffset = 0)
                val wraps = block.endMinute <= block.startMinute
                val end = day.atMinuteOfDay(block.endMinute, dayOffset = if (wraps) 1 else 0)
                // Only reachable if a DST shift swallowed the whole stretch. Round up to a full
                // day rather than down to nothing: a lost block is a rule that stops enforcing.
                out.add(start to if (end > start) end else start + MINUTES_PER_DAY * 60_000L)
            }
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    companion object {
        val ALWAYS = Schedule()

        /**
         * Parses [text], falling back to [ALWAYS] on anything it does not understand.
         *
         * All-or-nothing on purpose. Dropping the one block that failed to parse would leave a
         * narrower schedule than the user wrote, which enforces *less*; falling back to always-on
         * enforces more. A row this cannot read is a bug, and a bug must not quietly unblock
         * anything.
         */
        fun decode(text: String?): Schedule {
            if (text.isNullOrBlank()) return ALWAYS
            val blocks = text.split(',').map { TimeBlock.decode(it.trim()) ?: return ALWAYS }
            return Schedule(blocks)
        }
    }
}

/** Calendar numbers Sunday 1..Saturday 7; [TimeBlock] numbers Monday 0..Sunday 6. */
private fun bitIndexFor(calendarDayOfWeek: Int): Int = (calendarDayOfWeek + 5) % 7

private fun Calendar.atMinuteOfDay(minuteOfDay: Int, dayOffset: Int): Long {
    val c = clone() as Calendar
    if (dayOffset != 0) c.add(Calendar.DAY_OF_YEAR, dayOffset)
    c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
    c.set(Calendar.MINUTE, minuteOfDay % 60)
    return c.timeInMillis
}

/** Short day names, Monday first, matching [TimeBlock.days] bit order. */
val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** The single letters the day toggles show. Two of each — position carries the rest. */
val DAY_INITIALS = listOf("M", "T", "W", "T", "F", "S", "S")

/** "Every day" / "Weekdays" / "Mon–Thu" / "Mon, Wed, Fri". */
fun describeDays(days: Int): String {
    when (days) {
        TimeBlock.ALL_DAYS -> return "Every day"
        TimeBlock.WEEKDAYS -> return "Weekdays"
        TimeBlock.WEEKENDS -> return "Weekends"
    }
    val selected = (0 until 7).filter { (days shr it) and 1 == 1 }
    if (selected.isEmpty()) return "Never"

    // Collapse runs of three or more, so "Mon, Tue, Wed, Thu" reads as "Mon–Thu" on a narrow card.
    val parts = mutableListOf<String>()
    var i = 0
    while (i < selected.size) {
        var j = i
        while (j + 1 < selected.size && selected[j + 1] == selected[j] + 1) j++
        if (j - i >= 2) {
            parts.add("${DAY_NAMES[selected[i]]}–${DAY_NAMES[selected[j]]}")
        } else {
            (i..j).forEach { parts.add(DAY_NAMES[selected[it]]) }
        }
        i = j + 1
    }
    return parts.joinToString(", ")
}

/** "10am", "10:30am", "12pm", "midnight". */
fun formatMinuteOfDay(minuteOfDay: Int): String {
    val hour24 = minuteOfDay / 60
    val minute = minuteOfDay % 60
    if (minuteOfDay == 0) return "midnight"
    if (minuteOfDay == 12 * 60) return "noon"
    val hour12 = when (hour24 % 12) {
        0 -> 12
        else -> hour24 % 12
    }
    val suffix = if (hour24 < 12) "am" else "pm"
    return if (minute == 0) "$hour12$suffix" else "$hour12:${minute.toString().padStart(2, '0')}$suffix"
}
