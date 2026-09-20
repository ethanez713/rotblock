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
    fun `a member with its own rule drains the group for the others but is not held to it`() {
        // Three minutes on reddit and three on youtube is six minutes of group time, over its
        // five-minute cap. youtube has no rule of its own, so the exhausted group blocks it. reddit
        // has its own four-minute rule with a minute still left, so that rule governs reddit and the
        // group it filled does not block it -- the individual rule wins for behaviour, the group
        // still counts the time.
        val usage = mapOf(
            REDDIT to listOf(UsageSession(now - 50 * MIN, now - 47 * MIN)),
            YOUTUBE to listOf(UsageSession(now - 40 * MIN, now - 37 * MIN)),
        )
        val brainrot = group(300, listOf(REDDIT, YOUTUBE))

        val youtube = verdict(listOf(brainrot), usage)
        assertEquals(BlockReason.QUOTA_EXHAUSTED, (youtube.decision as Decision.Blocked).reason)
        assertEquals("Brainrot", youtube.governing?.label)

        val reddit = verdict(listOf(site("reddit.com", 240), brainrot), usage)
        assertTrue("its own rule still has a minute left", reddit.decision is Decision.Allowed)
        assertEquals("reddit.com", reddit.governing?.label)
    }

    @Test
    fun `an own rule's friction and greyscale govern, the group's do not reach it`() {
        // The necromerger case: the individual rule wants no wait and no greyscale, the group it is
        // in wants both. The individual rule governs, so neither reaches the target.
        val v = verdict(
            listOf(
                site("reddit.com", 600),
                group(900, listOf(REDDIT), frictionSeconds = 10, grayscale = true),
            ),
        )
        assertTrue(v.decision is Decision.Allowed)
        assertEquals("reddit.com", v.governing?.label)
        assertTrue("the group's greyscale must not reach a target with its own rule", !v.grayscale)
        assertEquals(0, v.frictionSeconds)
        assertNull(v.frictionFrom)
    }

    @Test
    fun `a disabled own rule steps aside and the group governs`() {
        val usage = mapOf(REDDIT to listOf(UsageSession(now - 30 * MIN, now - 20 * MIN)))
        val disabledSite = site("reddit.com", 600).copy(enabled = false)
        val v = verdict(listOf(disabledSite, group(180, listOf(REDDIT))), usage)
        // Ten minutes spent, group cap three -- with the individual rule off, the group is back in
        // charge and blocks.
        assertEquals(BlockReason.QUOTA_EXHAUSTED, (v.decision as Decision.Blocked).reason)
        assertEquals("Brainrot", v.governing?.label)
    }

    @Test
    fun `an own rule's ticker ignores a sooner group deadline it no longer answers`() {
        val usage = mapOf(REDDIT to listOf(UsageSession(now - 30 * MIN, now - 29 * MIN)))
        // reddit's own ten-minute rule has nine left; the group has two. The group no longer governs
        // reddit, so waking at its two-minute mark would change nothing on this screen.
        val v = verdict(listOf(site("reddit.com", 600), group(180, listOf(REDDIT))), usage)
        assertEquals(now + 9 * MIN, v.decision.recheckAtMs)
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
    fun `the countdown shows the least time left of any group covering a plain member`() {
        val usage = mapOf(REDDIT to listOf(UsageSession(now - 30 * MIN, now - 29 * MIN)))
        val evening = group(180, listOf(REDDIT)).copy(id = "group:evening", label = "Evening")
        val v = verdict(listOf(group(600, listOf(REDDIT)), evening), usage)
        val allowed = v.decision as Decision.Allowed
        assertEquals(2 * MIN, allowed.remainingMs)
        assertEquals("Evening", v.governing?.label)
    }

    @Test
    fun `a hard block outranks a dated one, because it is the one still holding you`() {
        val usage = mapOf(REDDIT to listOf(UsageSession(now - 30 * MIN, now - 20 * MIN)))
        val dated = group(180, listOf(REDDIT)).copy(id = "group:evening", label = "Evening")
        val v = verdict(listOf(dated, group(null, listOf(REDDIT))), usage)
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
        val onlyReddit = group(60, listOf(REDDIT))
        val both = group(60, listOf(REDDIT, YOUTUBE)).copy(id = "group:evening", label = "Evening")
        val v = verdict(listOf(onlyReddit, both), usage)
        val blocked = v.decision as Decision.Blocked
        val redditGroupAlone = PolicyEngine.evaluate(
            onlyReddit, usage[REDDIT]!!, 0L, null, now, UTC,
        ) as Decision.Blocked
        assertTrue(
            "the group holding more of the usage lifts later",
            blocked.liftsAtMs!! > redditGroupAlone.liftsAtMs!!,
        )
    }

    @Test
    fun `the ticker is armed for the soonest deadline any group named`() {
        val usage = mapOf(REDDIT to listOf(UsageSession(now - 30 * MIN, now - 29 * MIN)))
        val evening = group(180, listOf(REDDIT)).copy(id = "group:evening", label = "Evening")
        val v = verdict(listOf(group(600, listOf(REDDIT)), evening), usage)
        // The evening group runs out in two minutes, the other in nine. Missing the sooner one is a
        // rule that never comes back on.
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
    fun `greyscale and the wait are the union across the live groups covering a plain member`() {
        val plain = group(600, listOf(REDDIT))
        val strict = group(900, listOf(REDDIT), frictionSeconds = 8, grayscale = true)
            .copy(id = "group:evening", label = "Evening")
        val v = verdict(listOf(plain, strict))
        assertTrue(v.decision is Decision.Allowed)
        // The plain group has least left, so it governs -- and it asks for neither. Taking these off
        // the governing rule would silently drop the other group's.
        assertEquals("Brainrot", v.governing?.label)
        assertTrue(v.grayscale)
        assertEquals(8, v.frictionSeconds)
        // And the countdown is named after the rule that asked for it, not the one governing.
        assertEquals("Evening", v.frictionFrom?.label)
    }

    @Test
    fun `a group outside its hours asks for no wait and drains no colour`() {
        val evenings = Schedule(listOf(TimeBlock(TimeBlock.ALL_DAYS, 18 * 60, 23 * 60)))
        val plain = group(600, listOf(REDDIT))
        val evening = group(900, listOf(REDDIT), schedule = evenings, frictionSeconds = 8, grayscale = true)
            .copy(id = "group:evening", label = "Evening")
        val v = verdict(listOf(plain, evening))
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
