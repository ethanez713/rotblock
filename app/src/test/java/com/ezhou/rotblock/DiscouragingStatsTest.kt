package com.ezhou.rotblock

import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.insights.STATS_DAYS
import com.ezhou.rotblock.insights.TargetSession
import com.ezhou.rotblock.insights.deltaMinutes
import com.ezhou.rotblock.insights.foldEmergencyMinutes
import com.ezhou.rotblock.insights.foldStats
import com.ezhou.rotblock.insights.formatHoursMinutes
import com.ezhou.rotblock.insights.recentDayStarts
import com.ezhou.rotblock.insights.yTicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR

private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    Calendar.getInstance(UTC).apply {
        clear()
        set(2026, Calendar.JULY, day, hour, minute, 0)
    }.timeInMillis

private fun makeDayStarts(nowMs: Long): List<Long> =
    recentDayStarts(nowMs, dayStartHour = 0, days = STATS_DAYS, tz = UTC)

private fun session(targetId: String, startMs: Long, endMs: Long) =
    TargetSession(targetId, startMs, endMs)

class DiscouragingStatsFoldTest {

    @Test
    fun `no sessions returns zeros and hasHistory false`() {
        val now = at(31, 12)
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = emptyList(),
            dayStarts = makeDayStarts(now),
            nowMs = now,
        )
        assertFalse(result.hasHistory)
        assertEquals(0L, result.thisTargetToday)
        assertEquals(0L, result.allBlockedToday)
        assertEquals(STATS_DAYS, result.thisTargetDailyMs.size)
        assertEquals(STATS_DAYS, result.allBlockedDailyMs.size)
    }

    @Test
    fun `session today is the last daily bucket and the today convenience`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val todayStart = dayStarts.last()
        val sessions = listOf(session("app:com.example", todayStart + HOUR, todayStart + 2 * HOUR))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(HOUR, result.thisTargetToday)
        assertEquals(HOUR, result.allBlockedToday)
        assertEquals(HOUR, result.thisTargetDailyMs.last())
        assertEquals(0L, result.thisTargetDailyMs[result.thisTargetDailyMs.size - 2])
        assertTrue(result.hasHistory)
    }

    @Test
    fun `session yesterday is not in today`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val yesterdayStart = dayStarts[dayStarts.size - 2]
        val sessions = listOf(session("app:com.example", yesterdayStart + HOUR, yesterdayStart + 2 * HOUR))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(0L, result.thisTargetToday)
        assertEquals(HOUR, result.thisTargetDailyMs[result.thisTargetDailyMs.size - 2])
        assertTrue(result.hasHistory)
    }

    @Test
    fun `session spanning day boundary is split correctly`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val dayBoundary = dayStarts.last()
        val sessions = listOf(session("app:com.example", dayBoundary - 30 * MIN, dayBoundary + 30 * MIN))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(30 * MIN, result.thisTargetToday)
        assertEquals(30 * MIN, result.thisTargetDailyMs[result.thisTargetDailyMs.size - 2])
    }

    @Test
    fun `unrelated target is ignored`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val todayStart = dayStarts.last()
        val sessions = listOf(session("app:com.other", todayStart + HOUR, todayStart + 2 * HOUR))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(0L, result.thisTargetToday)
        assertEquals(0L, result.allBlockedToday)
        assertFalse(result.hasHistory)
    }

    @Test
    fun `target in multiple groups is counted once in all-blocked total`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val todayStart = dayStarts.last()
        val sessions = listOf(session("app:com.example", todayStart + HOUR, todayStart + 2 * HOUR))
        val result = foldStats(
            targetId = null,
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(HOUR, result.allBlockedToday)
        assertFalse(result.hasTarget)
    }

    @Test
    fun `null targetId has no target row`() {
        val now = at(31, 12)
        val result = foldStats(
            targetId = null,
            blockedTargetIds = setOf("app:com.example"),
            sessions = emptyList(),
            dayStarts = makeDayStarts(now),
            nowMs = now,
        )
        assertFalse(result.hasTarget)
        assertEquals(0L, result.thisTargetToday)
    }

    @Test
    fun `session outside the 30 days is ignored`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val tooOld = dayStarts.first() - HOUR
        val sessions = listOf(session("app:com.example", tooOld, tooOld + 30 * MIN))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertFalse(result.hasHistory)
    }

    @Test
    fun `hasHistory true when only an older day in the window has data`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val old = dayStarts.first()
        val sessions = listOf(session("app:com.example", old + HOUR, old + 2 * HOUR))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertTrue(result.hasHistory)
        assertEquals(0L, result.thisTargetToday)
        assertEquals(HOUR, result.thisTargetDailyMs.first())
    }

    @Test
    fun `30-day average is the mean of every daily bucket including zeros`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val todayStart = dayStarts.last()
        val sessions = listOf(session("app:com.example", todayStart, todayStart + HOUR))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(HOUR / STATS_DAYS, result.thisTargetAvg30Ms)
    }
}

class FoldEmergencyMinutesTest {

    @Test
    fun `pairs a skip with the pause started at the same timestamp`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val ts = dayStarts.last() + HOUR
        val events = listOf(
            AuditEvent(tsMs = ts, type = AuditType.EMERGENCY_SKIP),
            AuditEvent(tsMs = ts, type = AuditType.PAUSE_STARTED, durationSeconds = 15 * 60),
        )
        val days = foldEmergencyMinutes(events, dayStarts, now)
        assertEquals(15 * 60 * 1000L, days.last())
        assertEquals(0L, days[days.size - 2])
    }

    @Test
    fun `a skip with no matching pause contributes nothing`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val ts = dayStarts.last() + HOUR
        val events = listOf(AuditEvent(tsMs = ts, type = AuditType.EMERGENCY_SKIP))
        val days = foldEmergencyMinutes(events, dayStarts, now)
        assertEquals(0L, days.last())
        assertTrue(days.all { it == 0L })
    }

    @Test
    fun `an ordinary pause is not counted as emergency time`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val ts = dayStarts.last() + HOUR
        val events = listOf(
            AuditEvent(tsMs = ts, type = AuditType.PAUSE_STARTED, durationSeconds = 15 * 60),
        )
        val days = foldEmergencyMinutes(events, dayStarts, now)
        assertEquals(0L, days.last())
    }

    @Test
    fun `a skip whose pause has no duration contributes nothing`() {
        val now = at(31, 12)
        val dayStarts = makeDayStarts(now)
        val ts = dayStarts.last() + HOUR
        val events = listOf(
            AuditEvent(tsMs = ts, type = AuditType.EMERGENCY_SKIP),
            AuditEvent(tsMs = ts, type = AuditType.PAUSE_STARTED, durationSeconds = null),
        )
        val days = foldEmergencyMinutes(events, dayStarts, now)
        assertEquals(0L, days.last())
    }
}

class FormatHoursMinutesTest {

    @Test
    fun `drops seconds`() {
        assertEquals("0m", formatHoursMinutes(12_000L))
        assertEquals("4m", formatHoursMinutes(4 * MIN + 12_000L))
        assertEquals("1h 3m", formatHoursMinutes(HOUR + 3 * MIN))
        assertEquals("2h", formatHoursMinutes(2 * HOUR))
    }
}

class DeltaMinutesTest {

    @Test
    fun `compares at minute resolution`() {
        assertEquals(24L, deltaMinutes(102 * MIN, 78 * MIN))
        assertEquals(-10L, deltaMinutes(50 * MIN, 60 * MIN))
        assertEquals(0L, deltaMinutes(HOUR + 20_000L, HOUR))
    }
}

class YTicksTest {

    @Test
    fun `usage charts tick in hours even when the max day is under an hour`() {
        val (top, ticks) = yTicks(20 * MIN, quarterHour = false)
        assertEquals(HOUR, top)
        assertEquals(listOf("1h"), ticks.map { it.label })
    }

    @Test
    fun `emergency charts tick every 15 minutes when a day stays under 90m`() {
        val (top, ticks) = yTicks(45 * MIN, quarterHour = true)
        assertEquals(45 * MIN, top)
        assertEquals(listOf("15m", "30m", "45m"), ticks.map { it.label })
    }

    @Test
    fun `emergency charts switch to hours when 15-minute lines would cramp`() {
        val (top, ticks) = yTicks(2 * HOUR + 10 * MIN, quarterHour = true)
        assertEquals(3 * HOUR, top)
        assertEquals(listOf("1h", "2h", "3h"), ticks.map { it.label })
    }
}
