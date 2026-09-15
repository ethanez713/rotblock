package com.ezhou.rotblock

import com.ezhou.rotblock.insights.DiscouragingStatsResult
import com.ezhou.rotblock.insights.TargetSession
import com.ezhou.rotblock.insights.foldStats
import com.ezhou.rotblock.insights.formatCompactStats
import com.ezhou.rotblock.insights.recentDayStarts
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
    recentDayStarts(nowMs, dayStartHour = 0, days = 14, tz = UTC)

private fun session(targetId: String, startMs: Long, endMs: Long) =
    TargetSession(targetId, startMs, endMs)

class DiscouragingStatsFoldTest {

    @Test
    fun `no sessions returns all zeros and hasHistory false`() {
        val now = at(14, 12)
        val dayStarts = makeDayStarts(now)
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = emptyList(),
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertFalse(result.hasHistory)
        assertEquals(0L, result.thisTargetToday)
        assertEquals(0L, result.allBlockedToday)
    }

    @Test
    fun `session today is counted in today and this week but not last week`() {
        val now = at(14, 12)
        val dayStarts = makeDayStarts(now)
        val todayStart = dayStarts[13]
        val sessions = listOf(session("app:com.example", todayStart + HOUR, todayStart + 2 * HOUR))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(HOUR, result.thisTargetToday)
        assertEquals(0L, result.thisTargetYesterday)
        assertEquals(HOUR, result.thisTargetThisWeek)
        assertEquals(0L, result.thisTargetLastWeek)
        assertEquals(HOUR, result.allBlockedToday)
        assertEquals(0L, result.allBlockedYesterday)
        assertEquals(HOUR, result.allBlockedThisWeek)
        assertEquals(0L, result.allBlockedLastWeek)
        assertTrue(result.hasHistory)
    }

    @Test
    fun `session yesterday is not in today but is in this week`() {
        val now = at(14, 12)
        val dayStarts = makeDayStarts(now)
        val yesterdayStart = dayStarts[12]
        val sessions = listOf(session("app:com.example", yesterdayStart + HOUR, yesterdayStart + 2 * HOUR))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(0L, result.thisTargetToday)
        assertEquals(HOUR, result.thisTargetYesterday)
        assertEquals(HOUR, result.thisTargetThisWeek)
        assertEquals(0L, result.thisTargetLastWeek)
    }

    @Test
    fun `session last week is not in this week`() {
        val now = at(14, 12)
        val dayStarts = makeDayStarts(now)
        val lastWeekStart = dayStarts[0]
        val sessions = listOf(session("app:com.example", lastWeekStart + HOUR, lastWeekStart + 2 * HOUR))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(0L, result.thisTargetThisWeek)
        assertEquals(HOUR, result.thisTargetLastWeek)
    }

    @Test
    fun `session spanning day boundary is split correctly`() {
        val now = at(14, 12)
        val dayStarts = makeDayStarts(now)
        val dayBoundary = dayStarts[13]
        // 30 min before and after midnight-equivalent (day boundary at hour 0)
        val sessions = listOf(session("app:com.example", dayBoundary - 30 * MIN, dayBoundary + 30 * MIN))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(30 * MIN, result.thisTargetToday)
        assertEquals(30 * MIN, result.thisTargetYesterday)
    }

    @Test
    fun `unrelated target is ignored`() {
        val now = at(14, 12)
        val dayStarts = makeDayStarts(now)
        val todayStart = dayStarts[13]
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
    }

    @Test
    fun `target in multiple groups is counted once in all-blocked total`() {
        val now = at(14, 12)
        val dayStarts = makeDayStarts(now)
        val todayStart = dayStarts[13]
        val sessions = listOf(session("app:com.example", todayStart + HOUR, todayStart + 2 * HOUR))
        // com.example is in both the direct blocked set entries
        val blockedTargetIds = setOf("app:com.example")
        val result = foldStats(
            targetId = null,
            blockedTargetIds = blockedTargetIds,
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(HOUR, result.allBlockedToday)
        assertFalse(result.hasTarget)
    }

    @Test
    fun `null targetId has no target row`() {
        val now = at(14, 12)
        val dayStarts = makeDayStarts(now)
        val result = foldStats(
            targetId = null,
            blockedTargetIds = setOf("app:com.example"),
            sessions = emptyList(),
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertFalse(result.hasTarget)
        assertEquals(0L, result.thisTargetToday)
    }

    @Test
    fun `session outside any period is ignored`() {
        val now = at(14, 12)
        val dayStarts = makeDayStarts(now)
        val tooOld = dayStarts[0] - HOUR
        val sessions = listOf(session("app:com.example", tooOld, tooOld + 30 * MIN))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertEquals(0L, result.allBlockedLastWeek)
        assertFalse(result.hasHistory)
    }

    @Test
    fun `hasHistory true when only last week has data`() {
        val now = at(14, 12)
        val dayStarts = makeDayStarts(now)
        val lastWeekStart = dayStarts[0]
        val sessions = listOf(session("app:com.example", lastWeekStart + HOUR, lastWeekStart + 2 * HOUR))
        val result = foldStats(
            targetId = "app:com.example",
            blockedTargetIds = setOf("app:com.example"),
            sessions = sessions,
            dayStarts = dayStarts,
            nowMs = now,
        )
        assertTrue(result.hasHistory)
        assertEquals(0L, result.allBlockedThisWeek)
        assertEquals(HOUR, result.allBlockedLastWeek)
    }
}

class FormatCompactStatsTest {

    private fun stats(
        targetThisWeek: Long = 0L, targetLastWeek: Long = 0L,
        allThisWeek: Long = 0L, allLastWeek: Long = 0L,
        hasTarget: Boolean = true,
    ) = DiscouragingStatsResult(
        thisTargetThisWeek = targetThisWeek,
        thisTargetLastWeek = targetLastWeek,
        allBlockedThisWeek = allThisWeek,
        allBlockedLastWeek = allLastWeek,
        hasTarget = hasTarget,
    )

    @Test
    fun `returns empty when no history`() {
        assertEquals("", formatCompactStats(stats(), "App"))
    }

    @Test
    fun `omits target row when no target usage`() {
        val text = formatCompactStats(stats(allThisWeek = HOUR, allLastWeek = HOUR / 2), "App")
        assertFalse(text.contains("App:"))
        assertTrue(text.contains("All blocked:"))
    }

    @Test
    fun `includes target row when target has usage`() {
        val text = formatCompactStats(
            stats(targetThisWeek = HOUR, targetLastWeek = 0L, allThisWeek = HOUR, allLastWeek = 0L),
            "Reddit",
        )
        assertTrue(text.contains("Reddit:"))
        assertTrue(text.contains("All blocked:"))
    }

    @Test
    fun `shows increase arrow when this week is more`() {
        val text = formatCompactStats(
            stats(allThisWeek = 2 * HOUR, allLastWeek = HOUR),
            null,
        )
        assertTrue(text.contains("▲"))
        assertFalse(text.contains("▼"))
    }

    @Test
    fun `shows decrease arrow when this week is less`() {
        val text = formatCompactStats(
            stats(allThisWeek = HOUR, allLastWeek = 2 * HOUR),
            null,
        )
        assertTrue(text.contains("▼"))
        assertFalse(text.contains("▲"))
    }

    @Test
    fun `no arrow when this week equals last week`() {
        val text = formatCompactStats(stats(allThisWeek = HOUR, allLastWeek = HOUR), null)
        assertFalse(text.contains("▲"))
        assertFalse(text.contains("▼"))
    }

    @Test
    fun `omits target row when hasTarget is false even with null label`() {
        val text = formatCompactStats(
            stats(targetThisWeek = HOUR, allThisWeek = HOUR, hasTarget = false),
            null,
        )
        assertFalse(text.startsWith("null"))
        assertTrue(text.contains("All blocked:"))
    }
}
