package com.ezhou.rotblock

import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.insights.effectOf
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.UsageSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 24 * 60 * 60 * 1000L
private const val NOW = 1_000 * DAY

private fun policy(id: String = "app:reddit", createdAtMs: Long = 0L) = Policy(
    id = id,
    kind = TargetKind.APP,
    matcher = "reddit",
    label = "Reddit",
    createdAtMs = createdAtMs,
)

private fun event(type: AuditType, targetId: String, tsMs: Long = NOW) =
    AuditEvent(tsMs = tsMs, type = type, targetId = targetId)

class RuleEffectTest {

    @Test
    fun `counts are filtered to the right target and ignore other rules' events`() {
        val events = listOf(
            event(AuditType.BLOCK_SHOWN, "app:reddit"),
            event(AuditType.BLOCK_SHOWN, "app:reddit"),
            event(AuditType.BLOCK_SHOWN, "app:twitter"),
            event(AuditType.PAUSE_STARTED, "app:reddit"),
            event(AuditType.PAUSE_STARTED, "app:twitter"),
            event(AuditType.WEAKENING_APPLIED, "app:reddit"),
            event(AuditType.POLICY_STRENGTHENED, "app:reddit"),
            event(AuditType.WEAKENING_APPLIED, "app:twitter"),
        )
        val effect = effectOf(policy(), sessions = emptyList(), events = events, nowMs = NOW)
        assertEquals(2, effect.blocksShown)
        assertEquals(1, effect.pausesPastIt)
        assertEquals(2, effect.weakenings)
    }

    @Test
    fun `a policy younger than 14 days has no history regardless of usage`() {
        val sessions = wellPopulated28Days()
        val effect = effectOf(
            policy(createdAtMs = NOW - 3 * DAY),
            sessions = sessions,
            events = emptyList(),
            nowMs = NOW,
        )
        assertFalse(effect.hasEnoughHistory)
    }

    @Test
    fun `a sparse half has no history even on an old policy`() {
        // Only the second half of the 28-day window has any sessions at all.
        val sessions = (0 until 6).map { day ->
            val start = NOW - (13 - day) * DAY
            UsageSession(start, start + 30 * 60_000)
        }
        val effect = effectOf(
            policy(createdAtMs = NOW - 60 * DAY),
            sessions = sessions,
            events = emptyList(),
            nowMs = NOW,
        )
        assertFalse(effect.hasEnoughHistory)
        assertNull(effect.firstHalfDailyMeanMs)
    }

    @Test
    fun `a well-populated 28-day span on an old-enough policy has history`() {
        val effect = effectOf(
            policy(createdAtMs = NOW - 60 * DAY),
            sessions = wellPopulated28Days(),
            events = emptyList(),
            nowMs = NOW,
        )
        assertTrue(effect.hasEnoughHistory)
        assertEquals(30 * 60_000L, effect.firstHalfDailyMeanMs)
        assertEquals(60 * 60_000L, effect.secondHalfDailyMeanMs)
    }

    @Test
    fun `the mean is per day with data, not per calendar day in the half`() {
        // Four days of one hour each, spread across the whole 14-day first half with gaps between
        // them. Dividing by the width of the half (14 days) would understate the mean at ~17m/day;
        // dividing by the four days that actually have data on them gives the true 60m/day.
        val offsets = listOf(27, 23, 19, 15)
        val sparse = offsets.map { daysAgo ->
            val start = NOW - daysAgo * DAY
            UsageSession(start, start + 60 * 60_000)
        }
        val effect = effectOf(
            policy(createdAtMs = NOW - 60 * DAY),
            sessions = sparse,
            events = emptyList(),
            nowMs = NOW,
        )
        assertEquals(60 * 60_000L, effect.firstHalfDailyMeanMs)
    }

    /** 7 days at 30m/day in the first half of the 28-day window, 7 days at 60m/day in the second. */
    private fun wellPopulated28Days(): List<UsageSession> {
        val first = (0 until 7).map { day ->
            val start = NOW - (27 - day * 2) * DAY
            UsageSession(start, start + 30 * 60_000)
        }
        val second = (0 until 7).map { day ->
            val start = NOW - (13 - day * 2) * DAY
            UsageSession(start, start + 60 * 60_000)
        }
        return first + second
    }
}
