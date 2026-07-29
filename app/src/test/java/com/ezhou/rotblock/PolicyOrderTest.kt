package com.ezhou.rotblock

import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicySort
import com.ezhou.rotblock.policy.Schedule
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.policy.TimeBlock
import com.ezhou.rotblock.policy.restrictionScore
import com.ezhou.rotblock.policy.sortedFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun policy(
    label: String,
    quotaSeconds: Int? = null,
    windowSeconds: Int = 3_600,
    schedule: Schedule = Schedule.ALWAYS,
    enabled: Boolean = true,
    frictionSeconds: Int = 0,
) = Policy(
    id = Policy.targetId(TargetKind.APP, label.lowercase()),
    kind = TargetKind.APP,
    matcher = label.lowercase(),
    label = label,
    enabled = enabled,
    quotaSeconds = quotaSeconds,
    windowSeconds = windowSeconds,
    frictionSeconds = frictionSeconds,
    schedule = schedule,
)

/** Weekdays 9am-5pm: 5 days x 8 hours out of 168. */
private val WORKDAYS = Schedule(listOf(TimeBlock(TimeBlock.WEEKDAYS, 9 * 60, 17 * 60)))

class PolicyOrderTest {

    @Test
    fun `an always-on hard block is as restrictive as it gets`() {
        assertEquals(1.0, policy("Reddit").restrictionScore(), 1e-9)
    }

    @Test
    fun `an allowance scores by the share of time it hands back`() {
        assertEquals(0.95, policy("Reddit", quotaSeconds = 180).restrictionScore(), 1e-9)
        assertEquals(0.5, policy("Reddit", quotaSeconds = 1_800).restrictionScore(), 1e-9)
    }

    @Test
    fun `the same allowance in different currencies scores the same`() {
        val perHour = policy("A", quotaSeconds = 180, windowSeconds = 3_600)
        val perTwoHours = policy("B", quotaSeconds = 360, windowSeconds = 7_200)
        assertEquals(perHour.restrictionScore(), perTwoHours.restrictionScore(), 1e-9)
    }

    @Test
    fun `an allowance wider than its own window forbids nothing`() {
        // 9999 minutes out of any 30 is a rule you can never trip. It must not score as negative
        // restriction and sort below a disabled rule.
        val absurd = policy("A", quotaSeconds = 9_999 * 60, windowSeconds = 1_800)
        assertEquals(0.0, absurd.restrictionScore(), 1e-9)
    }

    @Test
    fun `a scheduled rule is only as restrictive as the hours it covers`() {
        assertEquals(40.0 / 168.0, policy("A", schedule = WORKDAYS).restrictionScore(), 1e-9)
    }

    @Test
    fun `a scheduled hard block ranks below an always-on allowance`() {
        // The point of the single score: these are written in different units and still compare.
        val workHoursBlock = policy("A", schedule = WORKDAYS)
        val alwaysThreeMinutes = policy("B", quotaSeconds = 180)
        assertTrue(alwaysThreeMinutes.restrictionScore() > workHoursBlock.restrictionScore())
    }

    @Test
    fun `strictest first orders across kinds of rule`() {
        val list = listOf(
            policy("Twenty a day", quotaSeconds = 1_200, windowSeconds = 86_400),
            policy("Hard block"),
            policy("Work hours only", schedule = WORKDAYS),
            policy("Three an hour", quotaSeconds = 180),
        )
        assertEquals(
            listOf("Hard block", "Twenty a day", "Three an hour", "Work hours only"),
            list.sortedFor(PolicySort.STRICTEST).map { it.label },
        )
    }

    @Test
    fun `a disabled rule sorts last however strict it reads`() {
        val list = listOf(
            policy("Off but absolute", enabled = false),
            policy("Barely there", quotaSeconds = 3_540),
        )
        assertEquals(
            listOf("Barely there", "Off but absolute"),
            list.sortedFor(PolicySort.STRICTEST).map { it.label },
        )
    }

    @Test
    fun `a disabled rule still outranks one that forbids nothing at all`() {
        // Both score zero, so only the enabled-first key separates them.
        val list = listOf(
            policy("Off", enabled = false),
            policy("Unlimited", quotaSeconds = 7_200, windowSeconds = 3_600),
        )
        assertEquals(
            listOf("Unlimited", "Off"),
            list.sortedFor(PolicySort.STRICTEST).map { it.label },
        )
    }

    @Test
    fun `a hard block outranks a quota it ties with`() {
        // A quota of the whole window scores 0, so pick a tie that is reachable: both these cover
        // the same hours and forbid everything in them.
        val list = listOf(
            policy("Quota", quotaSeconds = 1, windowSeconds = 86_400),
            policy("Absolute"),
        )
        assertEquals(
            listOf("Absolute", "Quota"),
            list.sortedFor(PolicySort.STRICTEST).map { it.label },
        )
    }

    @Test
    fun `equal rules fall back to name, so the list never shuffles between refreshes`() {
        val list = listOf(policy("Zed"), policy("apple"), policy("Beta"))
        assertEquals(
            listOf("apple", "Beta", "Zed"),
            list.sortedFor(PolicySort.STRICTEST).map { it.label },
        )
    }

    @Test
    fun `a countdown breaks a tie the quota cannot`() {
        val list = listOf(
            policy("Aaa", quotaSeconds = 180),
            policy("Zzz", quotaSeconds = 180, frictionSeconds = 10),
        )
        assertEquals(
            listOf("Zzz", "Aaa"),
            list.sortedFor(PolicySort.STRICTEST).map { it.label },
        )
    }

    @Test
    fun `by name is case-insensitive and ignores how strict anything is`() {
        val list = listOf(
            policy("zebra"),
            policy("Apple", quotaSeconds = 3_000),
            policy("banana", enabled = false),
        )
        assertEquals(
            listOf("Apple", "banana", "zebra"),
            list.sortedFor(PolicySort.NAME).map { it.label },
        )
    }

    @Test
    fun `a stored sort that no longer exists falls back to the default`() {
        assertEquals(PolicySort.STRICTEST, PolicySort.decode("STRICTEST"))
        assertEquals(PolicySort.DEFAULT, PolicySort.decode(null))
        assertEquals(PolicySort.DEFAULT, PolicySort.decode("BY_MOST_USED"))
    }
}
