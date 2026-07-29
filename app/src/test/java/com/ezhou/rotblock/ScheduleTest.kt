package com.ezhou.rotblock

import com.ezhou.rotblock.policy.Schedule
import com.ezhou.rotblock.policy.TimeBlock
import com.ezhou.rotblock.policy.describeDays
import com.ezhou.rotblock.policy.formatMinuteOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

/** Epoch ms for a wall-clock moment in [tz]. Month is 1-based, unlike Calendar's. */
private fun at(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int = 0,
    tz: TimeZone = UTC,
): Long = Calendar.getInstance(tz).apply {
    clear()
    set(year, month - 1, day, hour, minute, 0)
}.timeInMillis

private fun block(days: Int, startHour: Int, endHour: Int) =
    TimeBlock(days, startHour * 60, endHour * 60)

// 2026-07-27 is a Monday, so 27..31 are Mon..Fri and 1..2 August are Sat..Sun.
private const val MON = 27
private const val WED = 29
private const val SAT = 1

class ScheduleTest {

    @Test
    fun `an empty schedule is always on`() {
        assertTrue(Schedule.ALWAYS.isAlwaysOn)
        assertTrue(Schedule.ALWAYS.isActiveAt(at(2026, 7, MON, 3), UTC))
        assertNull(Schedule.ALWAYS.nextBoundaryAfter(at(2026, 7, MON, 3), UTC))
        assertNull(Schedule.ALWAYS.activeUntil(at(2026, 7, MON, 3), UTC))
    }

    @Test
    fun `weekday hours are live inside them and dead outside`() {
        val s = Schedule(listOf(block(TimeBlock.WEEKDAYS, 10, 18)))
        assertTrue(s.isActiveAt(at(2026, 7, MON, 10), UTC))
        assertTrue(s.isActiveAt(at(2026, 7, WED, 14), UTC))
        assertFalse("before the window", s.isActiveAt(at(2026, 7, MON, 9, 59), UTC))
        assertFalse("after the window", s.isActiveAt(at(2026, 7, MON, 18), UTC))
        assertFalse("wrong day", s.isActiveAt(at(2026, 8, SAT, 14), UTC))
    }

    @Test
    fun `the window edges are exact to the millisecond`() {
        val s = Schedule(listOf(block(TimeBlock.WEEKDAYS, 10, 18)))
        val opens = at(2026, 7, MON, 10)
        assertFalse("one ms early must not be live", s.isActiveAt(opens - 1, UTC))
        assertTrue("the opening instant is live", s.isActiveAt(opens, UTC))

        val closes = at(2026, 7, MON, 18)
        assertTrue("one ms before close is live", s.isActiveAt(closes - 1, UTC))
        assertFalse("the closing instant is not", s.isActiveAt(closes, UTC))
    }

    @Test
    fun `a window ending before it starts runs overnight`() {
        // 10pm Monday to 7am Tuesday. The day bits name the day it *starts*.
        val s = Schedule(listOf(TimeBlock(1 shl 0, 22 * 60, 7 * 60)))
        assertTrue(s.isActiveAt(at(2026, 7, MON, 23), UTC))
        assertTrue("carries past midnight", s.isActiveAt(at(2026, 7, MON + 1, 3), UTC))
        assertFalse("ends in the morning", s.isActiveAt(at(2026, 7, MON + 1, 7), UTC))
        assertFalse("does not start on Tuesday", s.isActiveAt(at(2026, 7, MON + 1, 23), UTC))
    }

    @Test
    fun `equal ends mean a full day rather than nothing`() {
        // Degenerate either way; it has to be read as the one that keeps blocking.
        val s = Schedule(listOf(TimeBlock(1 shl 0, 9 * 60, 9 * 60)))
        assertTrue(s.isActiveAt(at(2026, 7, MON, 9), UTC))
        assertTrue(s.isActiveAt(at(2026, 7, MON, 23), UTC))
        assertTrue(s.isActiveAt(at(2026, 7, MON + 1, 8, 59), UTC))
        assertFalse(s.isActiveAt(at(2026, 7, MON + 1, 9), UTC))
    }

    @Test
    fun `the next boundary is when the answer changes`() {
        val s = Schedule(listOf(block(TimeBlock.WEEKDAYS, 10, 18)))

        // Before it opens on Monday: the boundary is the opening.
        assertEquals(
            at(2026, 7, MON, 10),
            s.nextBoundaryAfter(at(2026, 7, MON, 8), UTC),
        )
        // Inside it: the boundary is the close.
        assertEquals(
            at(2026, 7, MON, 18),
            s.nextBoundaryAfter(at(2026, 7, MON, 12), UTC),
        )
        // Friday evening skips the weekend entirely and lands on Monday morning.
        assertEquals(
            at(2026, 8, SAT + 2, 10),
            s.nextBoundaryAfter(at(2026, 7, MON + 4, 20), UTC),
        )
    }

    @Test
    fun `a boundary is always found for any schedule anywhere in the week`() {
        // Sunday-only, checked from a Monday: the search has to reach a week out.
        val sundayOnly = Schedule(listOf(block(1 shl 6, 10, 18)))
        val boundary = sundayOnly.nextBoundaryAfter(at(2026, 7, MON, 12), UTC)
        assertEquals(at(2026, 8, SAT + 1, 10), boundary)
    }

    @Test
    fun `activeUntil carries across overlapping blocks`() {
        val handover = Schedule(
            listOf(
                block(TimeBlock.WEEKDAYS, 9, 13),
                block(TimeBlock.WEEKDAYS, 12, 17),
            ),
        )
        // 1pm is not the end: the second block was already live at noon and runs to 5.
        assertEquals(
            at(2026, 7, MON, 17),
            handover.activeUntil(at(2026, 7, MON, 10), UTC),
        )
    }

    @Test
    fun `activeUntil is null when the schedule is not live`() {
        val s = Schedule(listOf(block(TimeBlock.WEEKDAYS, 10, 18)))
        assertNull(s.activeUntil(at(2026, 8, SAT, 14), UTC))
    }

    @Test
    fun `a wall-clock window keeps its hours across a DST change`() {
        // US spring forward: 2026-03-08, clocks jump 2am -> 3am in New York.
        val ny = TimeZone.getTimeZone("America/New_York")
        val everyNight = Schedule(listOf(TimeBlock(TimeBlock.ALL_DAYS, 22 * 60, 7 * 60)))

        // Saturday 10pm through the shift to Sunday 7am -- eight wall-clock hours, seven real ones.
        assertTrue(everyNight.isActiveAt(at(2026, 3, 7, 23, tz = ny), ny))
        assertTrue("survives the missing hour", everyNight.isActiveAt(at(2026, 3, 8, 4, tz = ny), ny))
        assertFalse("still ends at 7am local", everyNight.isActiveAt(at(2026, 3, 8, 7, tz = ny), ny))
    }

    @Test
    fun `encode and decode round-trip`() {
        val s = Schedule(
            listOf(
                block(TimeBlock.WEEKDAYS, 10, 18),
                TimeBlock(TimeBlock.WEEKENDS, 12 * 60 + 30, 16 * 60),
            ),
        )
        assertEquals("31:600-1080,96:750-960", s.encode())
        assertEquals(s, Schedule.decode(s.encode()))
    }

    @Test
    fun `an unparseable schedule falls back to always on`() {
        // Every one of these has to enforce more, not less. A schedule we cannot read is a bug,
        // and a bug must never be the thing that quietly unblocks a target.
        val junk = listOf(
            null,
            "",
            "   ",
            "not a schedule",
            "31:600",
            "31:600-",
            "31:600-9999",   // minute out of range
            "0:600-1080",    // covers no days, so the rule would never fire
            "128:600-1080",  // day bit that does not exist
            "-1:600-1080",
            "31:600-1080,garbage", // one bad block condemns the whole string
        )
        for (text in junk) {
            assertTrue("\"$text\" should decode to always-on", Schedule.decode(text).isAlwaysOn)
        }
    }

    @Test
    fun `coversAllOf recognises a superset`() {
        val weekdays10to6 = Schedule(listOf(block(TimeBlock.WEEKDAYS, 10, 18)))
        val weekdays11to5 = Schedule(listOf(block(TimeBlock.WEEKDAYS, 11, 17)))
        val allWeek10to6 = Schedule(listOf(block(TimeBlock.ALL_DAYS, 10, 18)))

        assertTrue(weekdays10to6.coversAllOf(weekdays11to5))
        assertFalse(weekdays11to5.coversAllOf(weekdays10to6))
        assertTrue(allWeek10to6.coversAllOf(weekdays10to6))
        assertFalse(weekdays10to6.coversAllOf(allWeek10to6))
        assertTrue("identical schedules cover each other", weekdays10to6.coversAllOf(weekdays10to6))
    }

    @Test
    fun `always-on covers everything and nothing covers it`() {
        val some = Schedule(listOf(block(TimeBlock.WEEKDAYS, 10, 18)))
        assertTrue(Schedule.ALWAYS.coversAllOf(some))
        assertFalse(some.coversAllOf(Schedule.ALWAYS))
        assertTrue(Schedule.ALWAYS.coversAllOf(Schedule.ALWAYS))
    }

    @Test
    fun `coversAllOf handles windows that wrap past midnight`() {
        // Mon 10pm-2am is inside Mon-Tue 8pm-6am, which the naive "compare start and end" test
        // gets backwards because 2am sorts before 8pm.
        val wide = Schedule(listOf(TimeBlock(0b11, 20 * 60, 6 * 60)))
        val narrow = Schedule(listOf(TimeBlock(0b01, 22 * 60, 2 * 60)))
        assertTrue(wide.coversAllOf(narrow))
        assertFalse(narrow.coversAllOf(wide))
    }

    @Test
    fun `describeDays names the common sets and collapses runs`() {
        assertEquals("Every day", describeDays(TimeBlock.ALL_DAYS))
        assertEquals("Weekdays", describeDays(TimeBlock.WEEKDAYS))
        assertEquals("Weekends", describeDays(TimeBlock.WEEKENDS))
        assertEquals("Mon–Thu", describeDays(0b000_1111))
        assertEquals("Mon, Wed, Fri", describeDays(0b001_0101))
        assertEquals("Mon, Tue", describeDays(0b000_0011))
        assertEquals("Sun", describeDays(0b100_0000))
    }

    @Test
    fun `describe reads as a sentence`() {
        val s = Schedule(
            listOf(
                block(TimeBlock.WEEKDAYS, 10, 18),
                TimeBlock(TimeBlock.WEEKENDS, 12 * 60, 16 * 60 + 30),
            ),
        )
        assertEquals("Weekdays 10am–6pm · Weekends noon–4:30pm", s.describe())
        assertNull(Schedule.ALWAYS.describe())
    }

    @Test
    fun `times read the way people say them`() {
        assertEquals("midnight", formatMinuteOfDay(0))
        assertEquals("12:30am", formatMinuteOfDay(30))
        assertEquals("9am", formatMinuteOfDay(9 * 60))
        assertEquals("noon", formatMinuteOfDay(12 * 60))
        assertEquals("12:15pm", formatMinuteOfDay(12 * 60 + 15))
        assertEquals("6pm", formatMinuteOfDay(18 * 60))
        assertEquals("11:59pm", formatMinuteOfDay(23 * 60 + 59))
    }
}
