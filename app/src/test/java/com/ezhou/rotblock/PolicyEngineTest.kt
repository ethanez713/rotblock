package com.ezhou.rotblock

import com.ezhou.rotblock.policy.BlockReason
import com.ezhou.rotblock.policy.Decision
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyEngine
import com.ezhou.rotblock.policy.Schedule
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.policy.TimeBlock
import com.ezhou.rotblock.quota.UsageSession
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val NOW = 1_000 * HOUR

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

private fun policy(
    quotaSeconds: Int?,
    enabled: Boolean = true,
    schedule: Schedule = Schedule.ALWAYS,
) = Policy(
    id = Policy.targetId(TargetKind.APP, "com.reddit.frontpage"),
    kind = TargetKind.APP,
    matcher = "com.reddit.frontpage",
    label = "Reddit",
    enabled = enabled,
    quotaSeconds = quotaSeconds,
    windowSeconds = 3600,
    schedule = schedule,
)

/** 2026-07-29 is a Wednesday. Hours are UTC, matching the timezone every schedule test passes. */
private fun onWednesday(hour: Int, minute: Int = 0): Long = Calendar.getInstance(UTC).apply {
    clear()
    set(2026, Calendar.JULY, 29, hour, minute, 0)
}.timeInMillis

private val WEEKDAYS_10_TO_6 = Schedule(listOf(TimeBlock(TimeBlock.WEEKDAYS, 10 * 60, 18 * 60)))

class PolicyEngineTest {

    @Test
    fun `no policy allows`() {
        assertTrue(PolicyEngine.evaluate(null, emptyList(), 0L, NOW) is Decision.Allow)
    }

    @Test
    fun `disabled policy allows`() {
        val d = PolicyEngine.evaluate(policy(180, enabled = false), emptyList(), 0L, NOW)
        assertTrue(d is Decision.Allow)
    }

    @Test
    fun `null quota is a hard block`() {
        val d = PolicyEngine.evaluate(policy(null), emptyList(), 0L, NOW)
        assertEquals(Decision.Blocked(BlockReason.HARD_BLOCK, null), d)
    }

    @Test
    fun `a hard block still yields to an active pause`() {
        val d = PolicyEngine.evaluate(policy(null), emptyList(), NOW + 5 * MIN, NOW)
        assertEquals(
            Decision.Allow(pausedUntilMs = NOW + 5 * MIN, recheckAtMs = NOW + 5 * MIN),
            d,
        )
    }

    @Test
    fun `pause that has already elapsed does not allow`() {
        // The boundary that matters most: an expired deadline must fail closed, with no timer or
        // flag involved. This is the whole fail-closed invariant in one assertion.
        val d = PolicyEngine.evaluate(policy(null), emptyList(), NOW, NOW)
        assertTrue("expired pause must not allow", d is Decision.Blocked)
    }

    @Test
    fun `pause one millisecond in the future still allows`() {
        val d = PolicyEngine.evaluate(policy(null), emptyList(), NOW + 1, NOW)
        assertTrue(d is Decision.Allow)
    }

    @Test
    fun `within quota reports remaining time`() {
        val sessions = listOf(UsageSession(NOW - 10 * MIN, NOW - 9 * MIN)) // 1 min used
        val d = PolicyEngine.evaluate(policy(180), sessions, 0L, NOW) as Decision.Allowed
        assertEquals(2 * MIN, d.remainingMs)
        assertEquals(1 * MIN, d.usedMs)
        assertEquals(3 * MIN, d.quotaMs)
    }

    @Test
    fun `exhausted quota blocks and reports when it lifts`() {
        val sessions = listOf(UsageSession(NOW - 10 * MIN, NOW - 7 * MIN)) // exactly 3 min
        val d = PolicyEngine.evaluate(policy(180), sessions, 0L, NOW) as Decision.Blocked
        assertEquals(BlockReason.QUOTA_EXHAUSTED, d.reason)
        assertTrue("block should lift in the future", (d.liftsAtMs ?: 0) > NOW)
    }

    @Test
    fun `the quota boundary is exact to the millisecond`() {
        val overByOne = listOf(UsageSession(NOW - 10 * MIN, NOW - 7 * MIN + 1))
        assertTrue(
            "one ms over quota must block",
            PolicyEngine.evaluate(policy(180), overByOne, 0L, NOW) is Decision.Blocked,
        )

        val underByOne = listOf(UsageSession(NOW - 10 * MIN, NOW - 7 * MIN - 1))
        val allowed = PolicyEngine.evaluate(policy(180), underByOne, 0L, NOW)
        assertTrue("one ms under quota must allow", allowed is Decision.Allowed)
        assertEquals(1L, (allowed as Decision.Allowed).remainingMs)
    }

    @Test
    fun `usage outside the window does not count against the quota`() {
        val sessions = listOf(UsageSession(NOW - 3 * HOUR, NOW - 3 * HOUR + 30 * MIN))
        val d = PolicyEngine.evaluate(policy(180), sessions, 0L, NOW)
        assertTrue(d is Decision.Allowed)
        assertEquals(3 * MIN, (d as Decision.Allowed).remainingMs)
    }

    @Test
    fun `an unscheduled decision has nothing to wake up for`() {
        // The counterpart of every recheckAtMs assertion below: when no deadline is approaching,
        // the ticker must be allowed to stop entirely. This is most of the battery budget.
        val sessions = listOf(UsageSession(NOW - 10 * MIN, NOW - 7 * MIN))
        assertNull(PolicyEngine.evaluate(policy(null), emptyList(), 0L, NOW).recheckAtMs)
        assertNull(PolicyEngine.evaluate(policy(180), sessions, 0L, NOW).recheckAtMs)
        assertNull(PolicyEngine.evaluate(null, emptyList(), 0L, NOW).recheckAtMs)
    }

    @Test
    fun `an allowance arms the ticker for the moment it runs out`() {
        val sessions = listOf(UsageSession(NOW - 10 * MIN, NOW - 9 * MIN)) // 2 min left of 3
        val d = PolicyEngine.evaluate(policy(180), sessions, 0L, NOW)
        assertEquals(NOW + 2 * MIN, d.recheckAtMs)
    }

    // region schedules

    @Test
    fun `outside its hours a hard block allows`() {
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, onWednesday(20), UTC)
        assertTrue("8pm is outside 10-6", d is Decision.Allow)
    }

    @Test
    fun `inside its hours a hard block blocks`() {
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, onWednesday(14), UTC)
        assertTrue(d is Decision.Blocked)
        assertEquals(BlockReason.HARD_BLOCK, (d as Decision.Blocked).reason)
    }

    @Test
    fun `a scheduled hard block counts down to the end of its window`() {
        // Nothing else in this app makes a hard block lift on its own, so the block screen has a
        // real answer to "when does this let go" for the first time.
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, onWednesday(14), UTC) as Decision.Blocked
        assertEquals(onWednesday(18), d.liftsAtMs)
    }

    @Test
    fun `being outside the hours arms the ticker for the window opening`() {
        // The whole reason a schedule is safe: nothing fires an event at 10am. If this is null,
        // an app already open at 9:59 is never blocked at all.
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, onWednesday(9, 59), UTC)
        assertEquals(onWednesday(10), d.recheckAtMs)
    }

    @Test
    fun `being inside the hours arms the ticker for the window closing`() {
        // Over-charging is the one direction the ledger may not err in, so the session has to be
        // closed at 6pm rather than whenever something happens to wake us next.
        val p = policy(180, schedule = WEEKDAYS_10_TO_6)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, onWednesday(17, 59), UTC)
        assertEquals(onWednesday(18), d.recheckAtMs)
    }

    @Test
    fun `the soonest deadline wins when a quota runs out before the window closes`() {
        // 3 min of quota left at 5pm, and the window does not close until 6.
        val p = policy(180, schedule = WEEKDAYS_10_TO_6)
        val now = onWednesday(17)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, now, UTC)
        assertEquals(now + 3 * MIN, d.recheckAtMs)
    }

    @Test
    fun `an exhausted quota lifts at the window close when that comes first`() {
        val p = policy(180, schedule = WEEKDAYS_10_TO_6)
        val now = onWednesday(17, 59)
        // Spent right now, so the rolling window would not release any of it until 6:59.
        val sessions = listOf(UsageSession(now - 3 * MIN, now))
        val d = PolicyEngine.evaluate(p, sessions, 0L, now, UTC) as Decision.Blocked
        assertEquals(BlockReason.QUOTA_EXHAUSTED, d.reason)
        assertEquals("the window closing beats the quota ageing out", onWednesday(18), d.liftsAtMs)
    }

    @Test
    fun `the schedule boundary is exact to the millisecond`() {
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val opens = onWednesday(10)
        assertTrue(
            "one ms before the window opens must still allow",
            PolicyEngine.evaluate(p, emptyList(), 0L, opens - 1, UTC) is Decision.Allow,
        )
        assertTrue(
            "the opening instant must block",
            PolicyEngine.evaluate(p, emptyList(), 0L, opens, UTC) is Decision.Blocked,
        )
    }

    @Test
    fun `a pause still wins inside the scheduled hours`() {
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val now = onWednesday(14)
        val d = PolicyEngine.evaluate(p, emptyList(), now + 5 * MIN, now, UTC)
        assertTrue(d is Decision.Allow)
        assertEquals(now + 5 * MIN, (d as Decision.Allow).pausedUntilMs)
        assertEquals("the pause ends before the window does", now + 5 * MIN, d.recheckAtMs)
    }

    @Test
    fun `a pause outlasting the window rechecks at the window edge`() {
        // The pause is the later deadline, but the window closing is still a change of answer --
        // and if this waited for the pause it would keep the target charged past 6pm.
        val p = policy(180, schedule = WEEKDAYS_10_TO_6)
        val now = onWednesday(17, 30)
        val d = PolicyEngine.evaluate(p, emptyList(), now + 2 * HOUR, now, UTC)
        assertEquals(onWednesday(18), d.recheckAtMs)
    }

    // endregion
}
