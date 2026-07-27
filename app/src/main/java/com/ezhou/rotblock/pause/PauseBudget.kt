package com.ezhou.rotblock.pause

import java.util.Calendar
import java.util.TimeZone

/**
 * Start of the budget day containing [nowMs], where the day rolls over at [dayStartHour].
 *
 * A 4am rollover rather than midnight is deliberate: a pause taken at 1am belongs to the night
 * you are still awake for, not to the morning you have not reached yet.
 */
fun dayStartMs(nowMs: Long, dayStartHour: Int, tz: TimeZone = TimeZone.getDefault()): Long {
    val cal = Calendar.getInstance(tz).apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, dayStartHour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis > nowMs) cal.add(Calendar.DAY_OF_YEAR, -1)
    return cal.timeInMillis
}

/** What the daily pause budget looks like right now. */
data class BudgetStatus(
    val pausesUsed: Int,
    val minutesUsed: Int,
    val maxPauses: Int,
    val maxMinutes: Int,
) {
    /** 0 means unlimited for either dimension. */
    val pausesExhausted: Boolean get() = maxPauses > 0 && pausesUsed >= maxPauses
    val minutesExhausted: Boolean get() = maxMinutes > 0 && minutesUsed >= maxMinutes
    val exhausted: Boolean get() = pausesExhausted || minutesExhausted

    val pausesRemaining: Int? get() = if (maxPauses > 0) (maxPauses - pausesUsed).coerceAtLeast(0) else null
    val minutesRemaining: Int? get() = if (maxMinutes > 0) (maxMinutes - minutesUsed).coerceAtLeast(0) else null

    /**
     * Longest pause that fits the remaining budget, capped at [requestedMinutes].
     * Null when the budget is spent -- only an emergency skip gets through at that point.
     */
    fun grantableMinutes(requestedMinutes: Int): Int? {
        if (pausesExhausted) return null
        val remaining = minutesRemaining ?: return requestedMinutes
        if (remaining <= 0) return null
        return minOf(requestedMinutes, remaining)
    }
}
