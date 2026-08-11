package com.ezhou.rotblock

import com.ezhou.rotblock.policy.ChangeDirection
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyChange
import com.ezhou.rotblock.policy.Schedule
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.policy.TimeBlock
import org.junit.Assert.assertEquals
import org.junit.Test

private fun p(
    quotaSeconds: Int? = 180,
    windowSeconds: Int = 3600,
    enabled: Boolean = true,
    frictionSeconds: Int = 0,
    grayscale: Boolean = false,
    schedule: Schedule = Schedule.ALWAYS,
) = Policy(
    id = Policy.targetId(TargetKind.APP, "com.reddit.frontpage"),
    kind = TargetKind.APP,
    matcher = "com.reddit.frontpage",
    label = "Reddit",
    enabled = enabled,
    quotaSeconds = quotaSeconds,
    windowSeconds = windowSeconds,
    frictionSeconds = frictionSeconds,
    grayscale = grayscale,
    schedule = schedule,
)

private fun hours(days: Int, startHour: Int, endHour: Int) =
    Schedule(listOf(TimeBlock(days, startHour * 60, endHour * 60)))

private val WEEKDAYS_10_TO_6 = hours(TimeBlock.WEEKDAYS, 10, 18)

private fun classify(old: Policy?, new: Policy?) = PolicyChange.classify(old, new)

class PolicyChangeTest {

    @Test
    fun `adding a policy is strengthening`() {
        assertEquals(ChangeDirection.STRENGTHENING, classify(null, p()))
    }

    @Test
    fun `deleting a policy is weakening`() {
        assertEquals(ChangeDirection.WEAKENING, classify(p(), null))
    }

    @Test
    fun `disabling is weakening and enabling is strengthening`() {
        assertEquals(ChangeDirection.WEAKENING, classify(p(), p(enabled = false)))
        assertEquals(ChangeDirection.STRENGTHENING, classify(p(enabled = false), p()))
    }

    @Test
    fun `disabled is more permissive than any quota`() {
        // Even an enormous quota is stricter than switching the rule off.
        val hugeQuota = p(quotaSeconds = 86_000, windowSeconds = 86_400)
        assertEquals(ChangeDirection.STRENGTHENING, classify(p(enabled = false), hugeQuota))
        assertEquals(ChangeDirection.WEAKENING, classify(hugeQuota, p(enabled = false)))
    }

    @Test
    fun `raising a quota is weakening and lowering it is strengthening`() {
        assertEquals(ChangeDirection.WEAKENING, classify(p(quotaSeconds = 180), p(quotaSeconds = 600)))
        assertEquals(ChangeDirection.STRENGTHENING, classify(p(quotaSeconds = 600), p(quotaSeconds = 180)))
    }

    @Test
    fun `a hard block is the strictest state`() {
        assertEquals(ChangeDirection.STRENGTHENING, classify(p(quotaSeconds = 180), p(quotaSeconds = null)))
        assertEquals(ChangeDirection.WEAKENING, classify(p(quotaSeconds = null), p(quotaSeconds = 1)))
    }

    @Test
    fun `same quota over a longer window is stricter`() {
        // 3 min a day is a tighter leash than 3 min an hour.
        val perHour = p(quotaSeconds = 180, windowSeconds = 3600)
        val perDay = p(quotaSeconds = 180, windowSeconds = 86_400)
        assertEquals(ChangeDirection.STRENGTHENING, classify(perHour, perDay))
        assertEquals(ChangeDirection.WEAKENING, classify(perDay, perHour))
    }

    @Test
    fun `removing friction is weakening even when the quota is untouched`() {
        assertEquals(
            ChangeDirection.WEAKENING,
            classify(p(frictionSeconds = 10), p(frictionSeconds = 0)),
        )
    }

    @Test
    fun `turning greyscale off is weakening`() {
        assertEquals(ChangeDirection.WEAKENING, classify(p(grayscale = true), p(grayscale = false)))
        assertEquals(ChangeDirection.STRENGTHENING, classify(p(grayscale = false), p(grayscale = true)))
    }

    @Test
    fun `a mixed edit counts as weakening`() {
        // Quota up but friction also up: any loosening dimension makes the whole edit wait.
        // Mixed edits must fail closed, or this becomes a way to smuggle a quota increase past
        // the delay by bundling it with something stricter.
        val old = p(quotaSeconds = 180, frictionSeconds = 0)
        val new = p(quotaSeconds = 600, frictionSeconds = 10)
        assertEquals(ChangeDirection.WEAKENING, classify(old, new))
    }

    @Test
    fun `an identical policy is neutral`() {
        assertEquals(ChangeDirection.NEUTRAL, classify(p(), p()))
    }

    @Test
    fun `relabelling is neutral`() {
        assertEquals(ChangeDirection.NEUTRAL, classify(p(), p().copy(label = "Reddit (bad)")))
    }

    @Test
    fun `putting an always-on rule on a schedule is weakening`() {
        assertEquals(
            ChangeDirection.WEAKENING,
            classify(p(), p(schedule = WEEKDAYS_10_TO_6)),
        )
    }

    @Test
    fun `taking a rule off its schedule is strengthening`() {
        assertEquals(
            ChangeDirection.STRENGTHENING,
            classify(p(schedule = WEEKDAYS_10_TO_6), p()),
        )
    }

    @Test
    fun `widening the hours strengthens and narrowing them weakens`() {
        val narrow = p(schedule = hours(TimeBlock.WEEKDAYS, 11, 17))
        val wide = p(schedule = WEEKDAYS_10_TO_6)
        assertEquals(ChangeDirection.STRENGTHENING, classify(narrow, wide))
        assertEquals(ChangeDirection.WEAKENING, classify(wide, narrow))
    }

    @Test
    fun `moving the hours is weakening even when just as many are covered`() {
        // The edit this rule exists for: at 9pm, sliding an eight-hour block off the evening and
        // onto the morning. Counting hours would call it neutral and apply it instantly.
        val working = p(schedule = hours(TimeBlock.ALL_DAYS, 9, 17))
        val evenings = p(schedule = hours(TimeBlock.ALL_DAYS, 17, 25 % 24))
        assertEquals(ChangeDirection.WEAKENING, classify(working, evenings))
        assertEquals(ChangeDirection.WEAKENING, classify(evenings, working))
    }

    @Test
    fun `an unchanged schedule is neutral`() {
        assertEquals(
            ChangeDirection.NEUTRAL,
            classify(p(schedule = WEEKDAYS_10_TO_6), p(schedule = WEEKDAYS_10_TO_6)),
        )
    }

    @Test
    fun `narrowing the hours while raising the quota is still weakening`() {
        // The mixed-edit rule, on the dimension that most invites gaming: a smaller window is not
        // currency you can spend on a bigger allowance.
        val old = p(quotaSeconds = 180)
        val new = p(quotaSeconds = 600, schedule = WEEKDAYS_10_TO_6)
        assertEquals(ChangeDirection.WEAKENING, classify(old, new))
    }

    @Test
    fun `switching a rule on with hours attached is strengthening`() {
        // Off is the most permissive state there is, so anything that turns a rule on tightens
        // things -- however narrow the hours it comes back with. Comparing them against the
        // always-on schedule an off rule happens to store would delay a strengthening for an hour.
        assertEquals(
            ChangeDirection.STRENGTHENING,
            classify(p(enabled = false), p(enabled = true, schedule = WEEKDAYS_10_TO_6)),
        )
    }

    @Test
    fun `turning a scheduled rule off is still weakening`() {
        assertEquals(
            ChangeDirection.WEAKENING,
            classify(p(schedule = WEEKDAYS_10_TO_6), p(enabled = false, schedule = WEEKDAYS_10_TO_6)),
        )
    }
}
