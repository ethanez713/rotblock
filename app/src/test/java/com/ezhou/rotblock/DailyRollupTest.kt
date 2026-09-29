package com.ezhou.rotblock

import com.ezhou.rotblock.insights.DailyRollup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
private val NEW_YORK: TimeZone = TimeZone.getTimeZone("America/New_York")

private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    Calendar.getInstance(UTC).apply {
        clear()
        set(2026, Calendar.JULY, day, hour, minute, 0)
    }.timeInMillis

private fun inNewYork(month: Int, day: Int, hour: Int): Long =
    Calendar.getInstance(NEW_YORK).apply {
        clear()
        set(2026, month, day, hour, 0, 0)
    }.timeInMillis

class DaysToRollTest {

    @Test
    fun `a fresh marker rolls the nine completed days and not today`() {
        val days = DailyRollup.daysToRoll(at(26, 10), dayStartHour = 4, markerMs = 0L, tz = UTC)
        assertEquals((17..25).map { at(it, 4) }, days)
    }

    @Test
    fun `before the rollover the completed window ends yesterday`() {
        val days = DailyRollup.daysToRoll(at(26, 2), dayStartHour = 4, markerMs = 0L, tz = UTC)
        assertEquals((16..24).map { at(it, 4) }, days)
    }

    @Test
    fun `only days strictly after the marker are rolled`() {
        val days = DailyRollup.daysToRoll(at(26, 10), dayStartHour = 4, markerMs = at(23, 4), tz = UTC)
        assertEquals(listOf(at(24, 4), at(25, 4)), days)
    }

    @Test
    fun `a marker on the last completed day or on today rolls nothing`() {
        assertEquals(
            emptyList<Long>(),
            DailyRollup.daysToRoll(at(26, 10), dayStartHour = 4, markerMs = at(25, 4), tz = UTC),
        )
        assertEquals(
            emptyList<Long>(),
            DailyRollup.daysToRoll(at(26, 10), dayStartHour = 4, markerMs = at(26, 4), tz = UTC),
        )
    }

    @Test
    fun `a non-positive window rolls nothing`() {
        assertEquals(
            emptyList<Long>(),
            DailyRollup.daysToRoll(at(26, 10), dayStartHour = 4, markerMs = 0L, windowDays = 0, tz = UTC),
        )
    }

    @Test
    fun `spring forward keeps both wall-clock day starts`() {
        val march7 = inNewYork(Calendar.MARCH, 7, 4)
        val march8 = inNewYork(Calendar.MARCH, 8, 4)
        assertEquals(23 * 60 * 60 * 1000L, march8 - march7)
        val days = DailyRollup.daysToRoll(
            inNewYork(Calendar.MARCH, 10, 10),
            dayStartHour = 4,
            markerMs = 0L,
            tz = NEW_YORK,
        )
        assertEquals(9, days.size)
        assertTrue(march7 in days)
        assertTrue(march8 in days)
    }

    @Test
    fun `the fallback day is twenty-five hours and still one boundary`() {
        val oct31 = inNewYork(Calendar.OCTOBER, 31, 4)
        val nov1 = inNewYork(Calendar.NOVEMBER, 1, 4)
        assertEquals(25 * 60 * 60 * 1000L, nov1 - oct31)
        val days = DailyRollup.daysToRoll(
            inNewYork(Calendar.NOVEMBER, 3, 10),
            dayStartHour = 4,
            markerMs = 0L,
            tz = NEW_YORK,
        )
        assertEquals(9, days.size)
        assertTrue(oct31 in days)
        assertTrue(nov1 in days)
    }
}

class RollDueTest {

    @Test
    fun `nothing rolled yet is always due`() {
        assertTrue(DailyRollup.rollDue(at(26, 10), markerMs = 0L))
    }

    @Test
    fun `a roll is not due until forty-nine hours after the marker`() {
        assertFalse(DailyRollup.rollDue(at(26, 10), markerMs = at(25, 4)))
        assertFalse(DailyRollup.rollDue(at(27, 4), markerMs = at(25, 4)))
        assertTrue(DailyRollup.rollDue(at(27, 5), markerMs = at(25, 4)))
    }
}
