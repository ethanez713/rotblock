package com.ezhou.rotblock

import com.ezhou.rotblock.policy.BlockReason
import com.ezhou.rotblock.policy.ChangeDirection
import com.ezhou.rotblock.policy.Decision
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyChange
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

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

private const val REDDIT = "web:reddit.com"
private const val YOUTUBE = "web:youtube.com"

private fun site(matcher: String, quotaSeconds: Int?, schedule: Schedule = Schedule.ALWAYS) = Policy(
    id = Policy.targetId(TargetKind.WEBSITE, matcher),
    kind = TargetKind.WEBSITE,
    matcher = matcher,
    label = matcher,
    quotaSeconds = quotaSeconds,
    windowSeconds = 3600,
    schedule = schedule,
)

private fun group(
    quotaSeconds: Int?,
    members: List<String>,
    schedule: Schedule = Schedule.ALWAYS,
    frictionSeconds: Int = 0,
    grayscale: Boolean = false,
    enabled: Boolean = true,
) = Policy(
    id = Policy.targetId(TargetKind.GROUP, "brainrot"),
    kind = TargetKind.GROUP,
    matcher = "brainrot",
    label = "Brainrot",
    enabled = enabled,
    quotaSeconds = quotaSeconds,
    windowSeconds = 3600,
    frictionSeconds = frictionSeconds,
    grayscale = grayscale,
    schedule = schedule,
    members = members,
)

/** 2026-07-29 is a Wednesday. */
private fun onWednesday(hour: Int, minute: Int = 0): Long = Calendar.getInstance(UTC).apply {
    clear()
    set(2026, Calendar.JULY, 29, hour, minute, 0)
}.timeInMillis

/**
 * A group is a shared allowance across several targets, and the point of it is arithmetic the
 * per-target rules cannot do: three sites at two minutes each are three separate answers to
 * "have I had enough", and the sum is what actually gets spent.
 */
class PolicyGroupTest {

    private val now = onWednesday(12)

    private fun verdict(
        policies: List<Policy>,
        usage: Map<String, List<UsageSession>> = emptyMap(),
        pausedUntilMs: Long = 0L,
        workoutUntilMs: Long? = null,
        nowMs: Long = now,
    ) = PolicyEngine.evaluateAll(
        policies,
        { policy ->
            if (policy.kind == TargetKind.GROUP) policy.members.flatMap { usage[it].orEmpty() }
            else usage[policy.id].orEmpty()
        },
        pausedUntilMs,
        workoutUntilMs,
        nowMs,
        UTC,
    )

    // --- Encoding and identity -------------------------------------------------------------

    @Test
    fun `members survive a round trip`() {
        val members = listOf(REDDIT, "app:com.necromerger", "feed:youtube_shorts")
        assertEquals(members, Policy.decodeMembers(Policy.encodeMembers(members)))
    }

    @Test
    fun `a members string keeps every entry it can read rather than rejecting the row`() {
        // The direction is forced: there is no degenerate value here that blocks more, so
        // discarding the whole list would be the fail-open answer.
        assertEquals(listOf(REDDIT, YOUTUBE), Policy.decodeMembers("$REDDIT||  $YOUTUBE  |"))
    }

    @Test
    fun `a target id names its own kind, and a group is never a kind of screen`() {
        assertEquals(TargetKind.WEBSITE, Policy.kindOf(REDDIT))
        assertEquals(TargetKind.APP, Policy.kindOf("app:com.necromerger"))
        assertEquals(TargetKind.FEED, Policy.kindOf("feed:youtube_shorts"))
        assertEquals(TargetKind.GROUP, Policy.kindOf("group:brainrot"))
        assertNull(Policy.kindOf("reddit.com"))
    }

    // --- The fold --------------------------------------------------------------------------

    @Test
    fun `no policy at all allows and names nothing`() {
        val v = verdict(emptyList())
        assertTrue(v.decision is Decision.Allow)
        assertNull(v.governing)
    }

    @Test
    fun `time on one member is charged to the group as well as to the site's own rule`() {
        // Three minutes on reddit, three on youtube. Each site's own four-minute rule still has a
        // minute left; the group's five-minute allowance is a minute over. This is the whole
        // complaint the feature is for -- per-site limits that never add up to the time spent.
        val usage = mapOf(
            REDDIT to listOf(UsageSession(now - 50 * MIN, now - 47 * MIN)),
            YOUTUBE to listOf(UsageSession(now - 40 * MIN, now - 37 * MIN)),
        )
        val siteAlone = verdict(listOf(site("reddit.com", 240)), usage)
        assertTrue("its own rule still has a minute left", siteAlone.decision is Decision.Allowed)

        val withGroup = verdict(
            listOf(site("reddit.com", 240), group(300, listOf(REDDIT, YOUTUBE))),
            usage,
        )
        val blocked = withGroup.decision as Decision.Blocked
        assertEquals(BlockReason.QUOTA_EXHAUSTED, blocked.reason)
        assertEquals("Brainrot", withGroup.governing?.label)
    }

    @Test
    fun `a member in two groups is charged to both`() {
        val usage = mapOf(REDDIT to listOf(UsageSession(now - 30 * MIN, now - 26 * MIN)))
        val second = group(600, listOf(REDDIT)).copy(id = "group:evening", label = "Evening")
        val v = verdict(listOf(group(180, listOf(REDDIT)), second), usage)
        // Four minutes spent: the three-minute group is out, the ten-minute one is not, and the
        // stricter of the two is the answer.
        assertTrue(v.decision is Decision.Blocked)
        assertEquals("Brainrot", v.governing?.label)
    }

    @Test
    fun `the countdown shows the least time left of any rule covering the page`() {
        val usage = mapOf(REDDIT to listOf(UsageSession(now - 30 * MIN, now - 29 * MIN)))
        val v = verdict(listOf(site("reddit.com", 600), group(180, listOf(REDDIT))), usage)
        val allowed = v.decision as Decision.Allowed
        assertEquals(2 * MIN, allowed.remainingMs)
        assertEquals("Brainrot", v.governing?.label)
    }

    @Test
    fun `a hard block outranks a dated one, because it is the one still holding you`() {
        val usage = mapOf(REDDIT to listOf(UsageSession(now - 30 * MIN, now - 20 * MIN)))
        val v = verdict(listOf(site("reddit.com", 180), group(null, listOf(REDDIT))), usage)
        val blocked = v.decision as Decision.Blocked
        assertEquals(BlockReason.HARD_BLOCK, blocked.reason)
        assertNull("nothing but a pause or an edit lifts this", blocked.liftsAtMs)
    }

    @Test
    fun `two dated blocks lift at the later of the two`() {
        val usage = mapOf(
            REDDIT to listOf(UsageSession(now - 50 * MIN, now - 47 * MIN)),
            YOUTUBE to listOf(UsageSession(now - 20 * MIN, now - 17 * MIN)),
        )
        val v = verdict(
            listOf(site("reddit.com", 60), group(60, listOf(REDDIT, YOUTUBE))),
            usage,
        )
        val blocked = v.decision as Decision.Blocked
        val siteAlone = PolicyEngine.evaluate(
            site("reddit.com", 60), usage[REDDIT]!!, 0L, null, now, UTC,
        ) as Decision.Blocked
        assertTrue(
            "the group holds on longer than the site's own rule",
            blocked.liftsAtMs!! > siteAlone.liftsAtMs!!,
        )
    }

    @Test
    fun `the ticker is armed for the soonest deadline any rule named`() {
        val usage = mapOf(REDDIT to listOf(UsageSession(now - 30 * MIN, now - 29 * MIN)))
        val v = verdict(listOf(site("reddit.com", 600), group(180, listOf(REDDIT))), usage)
        // The group runs out in two minutes; the site's own rule in nine. Missing the sooner one
        // is a rule that never comes back on.
        assertEquals(now + 2 * MIN, v.decision.recheckAtMs)
    }

    @Test
    fun `a disabled group is not in the fold at all`() {
        val usage = mapOf(REDDIT to listOf(UsageSession(now - 30 * MIN, now - 20 * MIN)))
        // Callers filter on enabled before folding, but the engine must agree if one slips through.
        val v = verdict(listOf(group(180, listOf(REDDIT), enabled = false)))
        assertTrue(v.decision is Decision.Allow)
        assertTrue(usage.isNotEmpty())
    }

    @Test
    fun `greyscale unions across the live rules but the wait belongs to the app's own rule`() {
        val v = verdict(
            listOf(
                site("reddit.com", 600),
                group(900, listOf(REDDIT), frictionSeconds = 8, grayscale = true),
            ),
        )
        assertTrue(v.decision is Decision.Allowed)
        assertEquals("reddit.com", v.governing?.label)
        // Greyscale is part of the budget side and still unions across live rules, so the group's
        // colour drain reaches a member that has its own rule.
        assertTrue(v.grayscale)
        // The wait is the exception: it belongs to the app's own rule, which asks for none, so the
        // group's 8s does not reach it.
        assertEquals(0, v.frictionSeconds)
        assertNull(v.frictionFrom)
    }

    @Test
    fun `an app rule owns its wait, a group can neither add one nor lengthen it`() {
        // The necromerger case: the app rule wants no wait, the group it is in wants ten seconds.
        // The time budget still folds strictest-wins -- only the wait is the app rule's.
        val v = verdict(listOf(site("reddit.com", 600), group(900, listOf(REDDIT), frictionSeconds = 10)))
        assertTrue(v.decision is Decision.Allowed)
        assertEquals(0, v.frictionSeconds)
        assertNull(v.frictionFrom)
    }

    @Test
    fun `a disabled app rule hands the wait back to the group`() {
        val disabled = site("reddit.com", 600).copy(enabled = false)
        val v = verdict(listOf(disabled, group(900, listOf(REDDIT), frictionSeconds = 8)))
        assertTrue(v.decision is Decision.Allowed)
        assertEquals(8, v.frictionSeconds)
        assertEquals("Brainrot", v.frictionFrom?.label)
    }

    @Test
    fun `a rule outside its hours asks for no wait and drains no colour`() {
        val evenings = Schedule(listOf(TimeBlock(TimeBlock.ALL_DAYS, 18 * 60, 23 * 60)))
        val v = verdict(
            listOf(
                site("reddit.com", 600),
                group(900, listOf(REDDIT), schedule = evenings, frictionSeconds = 8, grayscale = true),
            ),
        )
        assertTrue(v.decision is Decision.Allowed)
        assertTrue("noon is outside the evening group's hours", !v.grayscale)
        assertEquals(0, v.frictionSeconds)
        assertNull(v.frictionFrom)
    }

    @Test
    fun `a pause reaches every rule covering the page`() {
        val usage = mapOf(REDDIT to listOf(UsageSession(now - 30 * MIN, now - 20 * MIN)))
        val v = verdict(
            listOf(site("reddit.com", 180), group(180, listOf(REDDIT))),
            usage,
            pausedUntilMs = now + 5 * MIN,
        )
        val allow = v.decision as Decision.Allow
        assertEquals(now + 5 * MIN, allow.pausedUntilMs)
    }

    @Test
    fun `a workout blocks through a group with quota to spare`() {
        val v = verdict(listOf(group(900, listOf(REDDIT))), workoutUntilMs = now + HOUR)
        assertEquals(BlockReason.WORKOUT_MODE, (v.decision as Decision.Blocked).reason)
    }

    // --- Charging only for hours the rule was live -------------------------------------------

    @Test
    fun `a rule is not charged for time spent outside its own hours`() {
        // Ten minutes at 9am, on a rule that only exists from 10. Another policy covering the same
        // target kept the ledger running through it, which is what makes this reachable at all.
        val tenTo6 = Schedule(listOf(TimeBlock(TimeBlock.ALL_DAYS, 10 * 60, 18 * 60)))
        val early = listOf(UsageSession(onWednesday(9), onWednesday(9, 10)))
        val at1030 = onWednesday(10, 30)

        val unscheduled = PolicyEngine.evaluate(
            site("reddit.com", 300).copy(windowSeconds = 6 * 3600), early, 0L, null, at1030, UTC,
        )
        assertTrue("without hours, that usage counts", unscheduled is Decision.Blocked)

        val scheduled = PolicyEngine.evaluate(
            site("reddit.com", 300, tenTo6).copy(windowSeconds = 6 * 3600),
            early, 0L, null, at1030, UTC,
        )
        assertEquals(0L, (scheduled as Decision.Allowed).usedMs)
    }

    @Test
    fun `a session straddling the opening of a window is charged only for the part inside it`() {
        val tenTo6 = Schedule(listOf(TimeBlock(TimeBlock.ALL_DAYS, 10 * 60, 18 * 60)))
        val straddling = listOf(UsageSession(onWednesday(9, 55), onWednesday(10, 5)))
        val d = PolicyEngine.evaluate(
            site("reddit.com", 600, tenTo6).copy(windowSeconds = 6 * 3600),
            straddling, 0L, null, onWednesday(10, 30), UTC,
        )
        assertEquals(5 * MIN, (d as Decision.Allowed).usedMs)
    }

    @Test
    fun `an always-on rule counts every minute, unchanged`() {
        val sessions = listOf(UsageSession(now - 10 * MIN, now - 8 * MIN))
        val d = PolicyEngine.evaluate(site("reddit.com", 600), sessions, 0L, null, now, UTC)
        assertEquals(2 * MIN, (d as Decision.Allowed).usedMs)
    }

    // --- Editing a group -----------------------------------------------------------------------

    @Test
    fun `dropping a member is a weakening and waits`() {
        val before = group(300, listOf(REDDIT, YOUTUBE))
        val after = before.copy(members = listOf(REDDIT))
        assertEquals(ChangeDirection.WEAKENING, PolicyChange.classify(before, after))
    }

    @Test
    fun `adding a member lands at once`() {
        val before = group(300, listOf(REDDIT))
        val after = before.copy(members = listOf(REDDIT, YOUTUBE))
        assertEquals(ChangeDirection.STRENGTHENING, PolicyChange.classify(before, after))
    }

    @Test
    fun `swapping one member for another fails closed, like a schedule that merely moved`() {
        val before = group(300, listOf(REDDIT))
        val after = before.copy(members = listOf(YOUTUBE))
        assertEquals(ChangeDirection.WEAKENING, PolicyChange.classify(before, after))
    }

    @Test
    fun `reordering members changes nothing`() {
        val before = group(300, listOf(REDDIT, YOUTUBE))
        val after = before.copy(members = listOf(YOUTUBE, REDDIT))
        assertEquals(ChangeDirection.NEUTRAL, PolicyChange.classify(before, after))
    }
}
