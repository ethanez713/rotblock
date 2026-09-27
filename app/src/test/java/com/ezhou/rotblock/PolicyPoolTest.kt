package com.ezhou.rotblock

import com.ezhou.rotblock.policy.BlockReason
import com.ezhou.rotblock.policy.ChangeDirection
import com.ezhou.rotblock.policy.Decision
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyChange
import com.ezhou.rotblock.policy.PolicyEngine
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.UsageSession
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L
private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
private const val GAME = "app:com.necromerger"

private fun at(hour: Int, minute: Int = 0, day: Int = 29): Long = Calendar.getInstance(UTC).apply {
    clear()
    set(2026, Calendar.JULY, day, hour, minute, 0)
}.timeInMillis

private fun game(
    quotaSeconds: Int? = null,
    windowSeconds: Int = 3600,
    poolMinutes: Int = 20,
    frictionSeconds: Int = 0,
    enabled: Boolean = true,
) = Policy(
    id = GAME,
    kind = TargetKind.APP,
    matcher = "com.necromerger",
    label = "NecroMerger",
    enabled = enabled,
    quotaSeconds = quotaSeconds,
    windowSeconds = windowSeconds,
    frictionSeconds = frictionSeconds,
    poolSeconds = poolMinutes * 60,
)

private fun games(quotaSeconds: Int?, poolMinutes: Int = 0) = Policy(
    id = Policy.targetId(TargetKind.GROUP, "games"),
    kind = TargetKind.GROUP,
    matcher = "games",
    label = "Games",
    quotaSeconds = quotaSeconds,
    windowSeconds = 3600,
    poolSeconds = poolMinutes * 60,
    members = listOf(GAME),
)

private fun minutes(fromHour: Int, fromMinute: Int, length: Int, countsTowardQuota: Boolean = true) =
    UsageSession(at(fromHour, fromMinute), at(fromHour, fromMinute) + length * MIN, countsTowardQuota)

/**
 * The pool is a daily head start: the first N minutes of a target's use each day that no rule may
 * block, still charged to every rule so the quotas pick up afterwards knowing what was spent.
 */
class PolicyPoolTest {

    private val noon = at(12)

    private fun verdict(
        policies: List<Policy>,
        sessions: List<UsageSession> = emptyList(),
        pausedUntilMs: Long = 0L,
        workoutUntilMs: Long? = null,
        nowMs: Long = noon,
    ) = PolicyEngine.evaluateAll(
        policies,
        { sessions },
        pausedUntilMs,
        workoutUntilMs,
        nowMs,
        dayStartHour = 4,
        tz = UTC,
    )

    @Test
    fun `a hard block opens for the pool and counts the rest of it down`() {
        val v = verdict(listOf(game()), listOf(minutes(9, 0, 5)))
        val d = v.decision as Decision.Allowed
        assertTrue(d.fromPool)
        assertEquals(15 * MIN, d.remainingMs)
        assertEquals(20 * MIN, d.quotaMs)
        assertEquals(noon + 15 * MIN, d.recheckAtMs)
        assertEquals(GAME, v.governing?.id)
    }

    @Test
    fun `a spent pool hands the target back to its rules`() {
        val d = verdict(listOf(game()), listOf(minutes(9, 0, 20))).decision
        assertEquals(BlockReason.HARD_BLOCK, (d as Decision.Blocked).reason)
    }

    @Test
    fun `the pool drains on every visit, not only on time the rules would have refused`() {
        // 20 min/day quota, 20 min pool: the quota never ran out, and the pool is spent all the same.
        val d = verdict(listOf(game(quotaSeconds = 30 * 60, windowSeconds = 86_400)), listOf(minutes(9, 0, 20)))
            .decision as Decision.Allowed
        assertFalse(d.fromPool)
        assertEquals(10 * MIN, d.remainingMs)
    }

    @Test
    fun `usage before the 4am rollover belongs to yesterday's pool`() {
        val sessions = listOf(
            minutes(3, 0, 50),                              // 03:00-03:50, yesterday's budget day
            UsageSession(at(3, 55), at(4, 5)),              // straddles it: 5 of these 10 are today's
        )
        val d = verdict(listOf(game()), sessions).decision as Decision.Allowed
        assertEquals(5 * MIN, d.usedMs)
    }

    @Test
    fun `paused time is not pool time`() {
        val d = verdict(listOf(game()), listOf(minutes(9, 0, 30, countsTowardQuota = false))).decision
        assertEquals(20 * MIN, (d as Decision.Allowed).remainingMs)
    }

    @Test
    fun `the pool holds a spent quota open, and that time is still charged to it`() {
        val rule = game(quotaSeconds = 3 * 60)
        // 3 min at 11:40 spent the quota; 10 more on the pool since.
        val sessions = listOf(minutes(11, 40, 3), minutes(11, 50, 10))
        assertTrue((verdict(listOf(rule), sessions).decision as Decision.Allowed).fromPool)

        // Once the pool is gone, the hour's quota is judged over pool time too: 13 minutes spent
        // of 3 means it stays blocked until enough of that ages out.
        val spent = sessions + minutes(12, 0, 7)
        val later = at(12, 7)
        val d = verdict(listOf(rule), spent, nowMs = later).decision as Decision.Blocked
        assertEquals(BlockReason.QUOTA_EXHAUSTED, d.reason)
        assertTrue(d.liftsAtMs!! > at(12, 50))
    }

    @Test
    fun `a rule with more time left than the pool keeps its own countdown`() {
        val d = verdict(listOf(game(quotaSeconds = 30 * 60, windowSeconds = 86_400, poolMinutes = 10)))
            .decision as Decision.Allowed
        assertFalse(d.fromPool)
        assertEquals(30 * MIN, d.remainingMs)
    }

    @Test
    fun `the pool lifts a group block too, and names the target's own rule`() {
        val v = verdict(listOf(game(quotaSeconds = 3600), games(quotaSeconds = 60)), listOf(minutes(11, 0, 2)))
        val d = v.decision as Decision.Allowed
        assertTrue(d.fromPool)
        assertEquals(GAME, v.governing?.id)
    }

    @Test
    fun `a pool on a group is ignored`() {
        val d = verdict(listOf(games(quotaSeconds = 60, poolMinutes = 20)), listOf(minutes(11, 0, 2))).decision
        assertTrue(d is Decision.Blocked)
    }

    @Test
    fun `a disabled rule's pool lifts nothing`() {
        val d = verdict(listOf(game(enabled = false), games(quotaSeconds = 60)), listOf(minutes(11, 0, 2))).decision
        assertTrue(d is Decision.Blocked)
    }

    @Test
    fun `a workout outranks the pool`() {
        val d = verdict(listOf(game()), workoutUntilMs = noon + 60 * MIN).decision as Decision.Blocked
        assertEquals(BlockReason.WORKOUT_MODE, d.reason)
    }

    @Test
    fun `a pause is left alone, so pause time neither charges nor drains`() {
        val d = verdict(listOf(game()), pausedUntilMs = noon + 10 * MIN).decision
        assertTrue(d is Decision.Allow)
    }

    @Test
    fun `the rule's friction wait still applies while the pool holds it open`() {
        val v = verdict(listOf(game(frictionSeconds = 10)))
        assertEquals(10, v.frictionSeconds)
    }

    @Test
    fun `a bigger pool waits and a smaller one lands at once`() {
        assertEquals(ChangeDirection.WEAKENING, PolicyChange.classify(game(poolMinutes = 10), game(poolMinutes = 20)))
        assertEquals(ChangeDirection.STRENGTHENING, PolicyChange.classify(game(poolMinutes = 20), game(poolMinutes = 0)))
    }
}
