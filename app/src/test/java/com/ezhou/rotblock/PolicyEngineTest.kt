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
        assertTrue(PolicyEngine.evaluate(null, emptyList(), 0L, null, NOW) is Decision.Allow)
    }

    @Test
    fun `disabled policy allows`() {
        val d = PolicyEngine.evaluate(policy(180, enabled = false), emptyList(), 0L, null, NOW)
        assertTrue(d is Decision.Allow)
    }

    @Test
    fun `null quota is a hard block`() {
        val d = PolicyEngine.evaluate(policy(null), emptyList(), 0L, null, NOW)
        assertEquals(Decision.Blocked(BlockReason.HARD_BLOCK, null), d)
    }

    @Test
    fun `a hard block still yields to an active pause`() {
        val d = PolicyEngine.evaluate(policy(null), emptyList(), NOW + 5 * MIN, null, NOW)
        assertEquals(
            Decision.Allow(pausedUntilMs = NOW + 5 * MIN, recheckAtMs = NOW + 5 * MIN),
            d,
        )
    }

    @Test
    fun `pause that has already elapsed does not allow`() {
        // The boundary that matters most: an expired deadline must fail closed, with no timer or
        // flag involved. This is the whole fail-closed invariant in one assertion.
        val d = PolicyEngine.evaluate(policy(null), emptyList(), NOW, null, NOW)
        assertTrue("expired pause must not allow", d is Decision.Blocked)
    }

    @Test
    fun `pause one millisecond in the future still allows`() {
        val d = PolicyEngine.evaluate(policy(null), emptyList(), NOW + 1, null, NOW)
        assertTrue(d is Decision.Allow)
    }

    @Test
    fun `pause-tagged sessions do not count toward quota`() {
        val paused = UsageSession(NOW - 10 * MIN, NOW, countsTowardQuota = false)
        val d = PolicyEngine.evaluate(policy(180), listOf(paused), 0L, null, NOW) as Decision.Allowed
        assertEquals(3 * MIN, d.remainingMs)
        assertEquals(0L, d.usedMs)
    }

    @Test
    fun `within quota reports remaining time`() {
        val sessions = listOf(UsageSession(NOW - 10 * MIN, NOW - 9 * MIN)) // 1 min used
        val d = PolicyEngine.evaluate(policy(180), sessions, 0L, null, NOW) as Decision.Allowed
        assertEquals(2 * MIN, d.remainingMs)
        assertEquals(1 * MIN, d.usedMs)
        assertEquals(3 * MIN, d.quotaMs)
    }

    @Test
    fun `exhausted quota blocks and reports when it lifts`() {
        val sessions = listOf(UsageSession(NOW - 10 * MIN, NOW - 7 * MIN)) // exactly 3 min
        val d = PolicyEngine.evaluate(policy(180), sessions, 0L, null, NOW) as Decision.Blocked
        assertEquals(BlockReason.QUOTA_EXHAUSTED, d.reason)
        assertTrue("block should lift in the future", (d.liftsAtMs ?: 0) > NOW)
    }

    @Test
    fun `the quota boundary is exact to the millisecond`() {
        val overByOne = listOf(UsageSession(NOW - 10 * MIN, NOW - 7 * MIN + 1))
        assertTrue(
            "one ms over quota must block",
            PolicyEngine.evaluate(policy(180), overByOne, 0L, null, NOW) is Decision.Blocked,
        )

        val underByOne = listOf(UsageSession(NOW - 10 * MIN, NOW - 7 * MIN - 1))
        val allowed = PolicyEngine.evaluate(policy(180), underByOne, 0L, null, NOW)
        assertTrue("one ms under quota must allow", allowed is Decision.Allowed)
        assertEquals(1L, (allowed as Decision.Allowed).remainingMs)
    }

    @Test
    fun `usage outside the window does not count against the quota`() {
        val sessions = listOf(UsageSession(NOW - 3 * HOUR, NOW - 3 * HOUR + 30 * MIN))
        val d = PolicyEngine.evaluate(policy(180), sessions, 0L, null, NOW)
        assertTrue(d is Decision.Allowed)
        assertEquals(3 * MIN, (d as Decision.Allowed).remainingMs)
    }

    @Test
    fun `an unscheduled decision has nothing to wake up for`() {
        // The counterpart of every recheckAtMs assertion below: when no deadline is approaching,
        // the ticker must be allowed to stop entirely. This is most of the battery budget.
        //
        // An always-on hard block qualifies and an exhausted rolling quota does not, which is the
        // distinction this test used to get wrong -- see the test below it.
        assertNull(PolicyEngine.evaluate(policy(null), emptyList(), 0L, null, NOW).recheckAtMs)
        assertNull(PolicyEngine.evaluate(null, emptyList(), 0L, null, NOW).recheckAtMs)
    }

    @Test
    fun `an exhausted quota arms the ticker for the moment it refills`() {
        // Every rule on the device this was found on is always-on, so the schedule boundary the
        // block used to arm for was null on all of them: the ticker stopped the moment the block
        // appeared, and the lift time on screen counted down to nothing happening. Sitting on the
        // block screen is exactly when nothing else will re-evaluate.
        val sessions = listOf(UsageSession(NOW - 10 * MIN, NOW - 7 * MIN)) // 3 min of a 3 min quota
        val d = PolicyEngine.evaluate(policy(180), sessions, 0L, null, NOW)
        assertTrue(d is Decision.Blocked)
        // One millisecond of the session has to age out of the hour-long window before usage drops
        // strictly below the quota.
        assertEquals(NOW - 10 * MIN + 1 + HOUR, (d as Decision.Blocked).liftsAtMs)
        assertEquals("the ticker arms for the lift it just promised", d.liftsAtMs, d.recheckAtMs)
    }

    @Test
    fun `an exhausted quota arms for whichever comes first, the refill or the window closing`() {
        // 10-6 on a Wednesday, at 5:30pm, with the quota spent at 10:05am: the window shuts before
        // that usage could ever age out of a 24-hour rolling window.
        val p = policy(180, schedule = WEEKDAYS_10_TO_6).copy(windowSeconds = 24 * 60 * 60)
        val sessions = listOf(UsageSession(onWednesday(10, 5), onWednesday(10, 8)))
        val d = PolicyEngine.evaluate(p, sessions, 0L, null, onWednesday(17, 30), UTC)
        assertTrue(d is Decision.Blocked)
        assertEquals(onWednesday(18), (d as Decision.Blocked).recheckAtMs)
    }

    @Test
    fun `an allowance arms the ticker for the moment it runs out`() {
        val sessions = listOf(UsageSession(NOW - 10 * MIN, NOW - 9 * MIN)) // 2 min left of 3
        val d = PolicyEngine.evaluate(policy(180), sessions, 0L, null, NOW)
        assertEquals(NOW + 2 * MIN, d.recheckAtMs)
    }

    @Test
    fun `outside its hours a hard block allows`() {
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, null, onWednesday(20), UTC)
        assertTrue("8pm is outside 10-6", d is Decision.Allow)
    }

    @Test
    fun `inside its hours a hard block blocks`() {
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, null, onWednesday(14), UTC)
        assertTrue(d is Decision.Blocked)
        assertEquals(BlockReason.HARD_BLOCK, (d as Decision.Blocked).reason)
    }

    @Test
    fun `a scheduled hard block counts down to the end of its window`() {
        // Nothing else in this app makes a hard block lift on its own, so the block screen has a
        // real answer to "when does this let go" for the first time.
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, null, onWednesday(14), UTC) as Decision.Blocked
        assertEquals(onWednesday(18), d.liftsAtMs)
    }

    @Test
    fun `being outside the hours arms the ticker for the window opening`() {
        // The whole reason a schedule is safe: nothing fires an event at 10am. If this is null,
        // an app already open at 9:59 is never blocked at all.
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, null, onWednesday(9, 59), UTC)
        assertEquals(onWednesday(10), d.recheckAtMs)
    }

    @Test
    fun `being inside the hours arms the ticker for the window closing`() {
        // Over-charging is the one direction the ledger may not err in, so the session has to be
        // closed at 6pm rather than whenever something happens to wake us next.
        val p = policy(180, schedule = WEEKDAYS_10_TO_6)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, null, onWednesday(17, 59), UTC)
        assertEquals(onWednesday(18), d.recheckAtMs)
    }

    @Test
    fun `the soonest deadline wins when a quota runs out before the window closes`() {
        // 3 min of quota left at 5pm, and the window does not close until 6.
        val p = policy(180, schedule = WEEKDAYS_10_TO_6)
        val now = onWednesday(17)
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, null, now, UTC)
        assertEquals(now + 3 * MIN, d.recheckAtMs)
    }

    @Test
    fun `an exhausted quota lifts at the window close when that comes first`() {
        val p = policy(180, schedule = WEEKDAYS_10_TO_6)
        val now = onWednesday(17, 59)
        // Spent right now, so the rolling window would not release any of it until 6:59.
        val sessions = listOf(UsageSession(now - 3 * MIN, now))
        val d = PolicyEngine.evaluate(p, sessions, 0L, null, now, UTC) as Decision.Blocked
        assertEquals(BlockReason.QUOTA_EXHAUSTED, d.reason)
        assertEquals("the window closing beats the quota ageing out", onWednesday(18), d.liftsAtMs)
    }

    @Test
    fun `the schedule boundary is exact to the millisecond`() {
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val opens = onWednesday(10)
        assertTrue(
            "one ms before the window opens must still allow",
            PolicyEngine.evaluate(p, emptyList(), 0L, null, opens - 1, UTC) is Decision.Allow,
        )
        assertTrue(
            "the opening instant must block",
            PolicyEngine.evaluate(p, emptyList(), 0L, null, opens, UTC) is Decision.Blocked,
        )
    }

    @Test
    fun `a pause still wins inside the scheduled hours`() {
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val now = onWednesday(14)
        val d = PolicyEngine.evaluate(p, emptyList(), now + 5 * MIN, null, now, UTC)
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
        val d = PolicyEngine.evaluate(p, emptyList(), now + 2 * HOUR, null, now, UTC)
        assertEquals(onWednesday(18), d.recheckAtMs)
    }

    @Test
    fun `workout mode blocks a rule that still has plenty of quota left`() {
        val sessions = listOf(UsageSession(NOW - 10 * MIN, NOW - 9 * MIN)) // 1 min used of 3
        val d = PolicyEngine.evaluate(policy(180), sessions, 0L, NOW + 30 * MIN, NOW) as Decision.Blocked
        assertEquals(BlockReason.WORKOUT_MODE, d.reason)
    }

    @Test
    fun `workout mode overrides an active pause`() {
        val d = PolicyEngine.evaluate(policy(null), emptyList(), NOW + 5 * MIN, NOW + 30 * MIN, NOW)
        assertTrue("a pause must not win over a live workout", d is Decision.Blocked)
        assertEquals(BlockReason.WORKOUT_MODE, (d as Decision.Blocked).reason)
    }

    @Test
    fun `workout mode overrides a rule outside its scheduled hours`() {
        val p = policy(null, schedule = WEEKDAYS_10_TO_6)
        val now = onWednesday(20) // 8pm, outside 10-6
        val d = PolicyEngine.evaluate(p, emptyList(), 0L, now + 30 * MIN, now, UTC)
        assertTrue("a workout must block even outside the rule's own hours", d is Decision.Blocked)
        assertEquals(BlockReason.WORKOUT_MODE, (d as Decision.Blocked).reason)
    }

    @Test
    fun `workout mode does not resurrect a disabled rule`() {
        val d = PolicyEngine.evaluate(policy(180, enabled = false), emptyList(), 0L, NOW + 30 * MIN, NOW)
        assertTrue(d is Decision.Allow)
    }

    @Test
    fun `once the workout cap passes the decision reverts to normal`() {
        val workoutUntil = NOW - 1 // already lapsed
        val d = PolicyEngine.evaluate(policy(null), emptyList(), 0L, workoutUntil, NOW)
        assertEquals(Decision.Blocked(BlockReason.HARD_BLOCK, null), d)
    }

    @Test
    fun `the workout branch arms the ticker for the cap`() {
        val d = PolicyEngine.evaluate(policy(180), emptyList(), 0L, NOW + 30 * MIN, NOW)
        assertEquals(NOW + 30 * MIN, d.recheckAtMs)
    }
}
