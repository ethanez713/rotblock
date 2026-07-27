package com.ezhou.rotblock

import com.ezhou.rotblock.pause.BudgetStatus
import com.ezhou.rotblock.pause.dayStartMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
    Calendar.getInstance(UTC).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

class DayStartTest {

    @Test
    fun `after the rollover hour the day starts today`() {
        val now = at(2026, 7, 26, 9, 30)
        assertEquals(at(2026, 7, 26, 4), dayStartMs(now, dayStartHour = 4, tz = UTC))
    }

    @Test
    fun `before the rollover hour the day still belongs to yesterday`() {
        // 1am belongs to the night you are still awake for, not to the morning ahead.
        val now = at(2026, 7, 26, 1, 15)
        assertEquals(at(2026, 7, 25, 4), dayStartMs(now, dayStartHour = 4, tz = UTC))
    }

    @Test
    fun `exactly at the rollover hour starts the new day`() {
        val now = at(2026, 7, 26, 4)
        assertEquals(now, dayStartMs(now, dayStartHour = 4, tz = UTC))
    }

    @Test
    fun `midnight rollover behaves like a normal calendar day`() {
        val now = at(2026, 7, 26, 0, 5)
        assertEquals(at(2026, 7, 26, 0), dayStartMs(now, dayStartHour = 0, tz = UTC))
    }
}

class BudgetStatusTest {

    @Test
    fun `zero limits mean unlimited`() {
        val status = BudgetStatus(pausesUsed = 99, minutesUsed = 999, maxPauses = 0, maxMinutes = 0)
        assertFalse(status.exhausted)
        assertNull(status.pausesRemaining)
        assertEquals(30, status.grantableMinutes(30))
    }

    @Test
    fun `pause count limit blocks further pauses`() {
        val status = BudgetStatus(pausesUsed = 3, minutesUsed = 10, maxPauses = 3, maxMinutes = 60)
        assertTrue(status.exhausted)
        assertNull("no pauses left means nothing is grantable", status.grantableMinutes(15))
    }

    @Test
    fun `a request is trimmed to the remaining minutes`() {
        val status = BudgetStatus(pausesUsed = 1, minutesUsed = 50, maxPauses = 5, maxMinutes = 60)
        assertEquals(10, status.grantableMinutes(30))
    }

    @Test
    fun `a request that fits is granted whole`() {
        val status = BudgetStatus(pausesUsed = 1, minutesUsed = 10, maxPauses = 5, maxMinutes = 60)
        assertEquals(15, status.grantableMinutes(15))
    }

    @Test
    fun `spent minute budget grants nothing`() {
        val status = BudgetStatus(pausesUsed = 1, minutesUsed = 60, maxPauses = 5, maxMinutes = 60)
        assertTrue(status.exhausted)
        assertNull(status.grantableMinutes(5))
    }

    @Test
    fun `remaining counters never go negative`() {
        // Over-budget emergency skips can push usage past the cap; the display must not show
        // "-2 pauses left".
        val status = BudgetStatus(pausesUsed = 6, minutesUsed = 90, maxPauses = 3, maxMinutes = 60)
        assertEquals(0, status.pausesRemaining)
        assertEquals(0, status.minutesRemaining)
    }
}
