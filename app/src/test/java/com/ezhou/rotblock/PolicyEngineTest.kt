package com.ezhou.rotblock

import com.ezhou.rotblock.policy.BlockReason
import com.ezhou.rotblock.policy.Decision
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyEngine
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.UsageSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val NOW = 1_000 * HOUR

private fun policy(quotaSeconds: Int?, enabled: Boolean = true) = Policy(
    id = Policy.targetId(TargetKind.APP, "com.reddit.frontpage"),
    kind = TargetKind.APP,
    matcher = "com.reddit.frontpage",
    label = "Reddit",
    enabled = enabled,
    quotaSeconds = quotaSeconds,
    windowSeconds = 3600,
)

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
        assertEquals(Decision.Allow(pausedUntilMs = NOW + 5 * MIN), d)
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
}
