package com.ezhou.rotblock.policy

import com.ezhou.rotblock.quota.RollingQuota
import com.ezhou.rotblock.quota.UsageSession
import java.util.TimeZone

/** Why a target was blocked. Surfaced verbatim on the block screen and in the audit log. */
enum class BlockReason { HARD_BLOCK, QUOTA_EXHAUSTED, WORKOUT_MODE }

sealed interface Decision {

    /**
     * When this answer could change with nothing happening at all, or null if only an event can
     * change it. The ticker arms for this.
     *
     * It exists because none of the deadlines in this app announce themselves: a pause running
     * out, an allowance running out and a scheduled window opening all fire no event whatsoever,
     * and the phone can sit on the same screen through every one of them. Anything that folds a
     * timestamp into a decision therefore owes the caller the next timestamp that would fold
     * differently — otherwise a rule that should have come back on simply never does.
     */
    val recheckAtMs: Long?

    /** No policy, policy disabled, outside the policy's hours, or a pause is in effect. */
    data class Allow(
        val pausedUntilMs: Long? = null,
        override val recheckAtMs: Long? = null,
    ) : Decision

    /** Within quota. [remainingMs] is how much is left before the block lands. */
    data class Allowed(
        val remainingMs: Long,
        val usedMs: Long,
        val quotaMs: Long,
        override val recheckAtMs: Long? = null,
    ) : Decision

    /**
     * Blocked. [liftsAtMs] is when the block lifts on its own -- quota ageing out, or the
     * scheduled window ending -- or null if nothing but a pause or a policy change will lift it.
     */
    data class Blocked(
        val reason: BlockReason,
        val liftsAtMs: Long?,
        override val recheckAtMs: Long? = null,
    ) : Decision
}

/**
 * The single answer for a target, folded from every policy that covers it.
 *
 * [governing] is the policy the answer is *about* -- the one blocking, or the one with least time
 * left -- and is what the block screen and the countdown are labelled with. It is null only when
 * no policy covers the target at all. [grayscale] and [frictionSeconds] are separate from it
 * because they are the union across every live policy rather than a property of one: two rules can
 * cover a page while only the other one wants the colour drained.
 */
data class Verdict(
    val decision: Decision,
    val governing: Policy?,
    val grayscale: Boolean = false,
    val frictionSeconds: Int = 0,
    /** The rule that asked for the wait, which is not always [governing] -- see [PolicyEngine]. */
    val frictionFrom: Policy? = null,
)

/**
 * Decides what to do about a target, from persisted state and the clock only.
 *
 * The fail-closed invariant lives here: this function is a pure fold over a timestamp and stored
 * rows. It never consults a timer, a flag, or an "is blocking currently on" boolean. If the
 * process died mid-pause and came back, the same inputs still produce the same answer, so a
 * crash, a force-stop or a reboot cannot leave a target unblocked.
 *
 * A schedule is the one relaxation here that is not a persisted deadline, and it does not need to
 * be: the wall clock is already durable state that no crash can lose. Being outside a rule's hours
 * is derived fresh on every call, so there is nothing to restore and nothing to leak.
 */
object PolicyEngine {

    fun evaluate(
        policy: Policy?,
        sessions: List<UsageSession>,
        pausedUntilMs: Long,
        workoutUntilMs: Long?,
        nowMs: Long,
        tz: TimeZone = TimeZone.getDefault(),
    ): Decision {
        if (policy == null || !policy.enabled) return Decision.Allow()

        // The one *strengthening* in this whole fold -- everything else here relaxes enforcement,
        // this overrides it. So its dangerous direction has to run opposite to every other branch:
        // stuck-on rather than fail-open. Expressed as a deadline that lapses on its own, like
        // every relaxation in this file, rather than a boolean some caller has to remember to
        // clear -- an "is a workout in progress" flag left set is exactly the kind of state this
        // whole engine exists to avoid. It runs ahead of the pause check and the schedule check on
        // purpose: a workout in progress overrides a pause already in effect and a rule's own
        // scheduled hours alike. It only ever respects [Policy.enabled] -- a disabled rule stays
        // disabled, and a target with no rule at all is untouched.
        if (workoutUntilMs != null && nowMs < workoutUntilMs) {
            // The cap arms the ticker but is deliberately not offered as a lift time. It is a
            // backstop against a workout that never ends, not a promise about this one: the block
            // really lifts when you finish, which is an event and not a deadline. Handing the cap
            // to the block screen would put a three-hour countdown on it, telling you the phone is
            // locked until then -- the one kind of lie a readout in this app may not tell.
            return Decision.Blocked(BlockReason.WORKOUT_MODE, liftsAtMs = null, recheckAtMs = workoutUntilMs)
        }

        val schedule = policy.schedule
        // Held for every branch below: whatever the answer is, it stops being the answer at the
        // next edge of the schedule, and nothing will say so.
        val boundaryMs = schedule.nextBoundaryAfter(nowMs, tz)

        if (nowMs < pausedUntilMs) {
            return Decision.Allow(
                pausedUntilMs = pausedUntilMs,
                recheckAtMs = soonest(pausedUntilMs, boundaryMs),
            )
        }
        if (!schedule.isActiveAt(nowMs, tz)) {
            return Decision.Allow(recheckAtMs = boundaryMs)
        }

        // Ends the block on its own when the scheduled window closes, so a hard block inside
        // working hours can honestly count down to the moment it lets go.
        val windowEndsMs = schedule.activeUntil(nowMs, tz)

        val quotaMs = policy.quotaMs
            ?: return Decision.Blocked(BlockReason.HARD_BLOCK, windowEndsMs, boundaryMs)

        val charged = chargeable(policy, sessions, nowMs, tz)
        val usedMs = RollingQuota.usedInWindow(charged, nowMs, policy.windowMs)
        val remainingMs = quotaMs - usedMs
        return if (remainingMs > 0) {
            Decision.Allowed(
                remainingMs = remainingMs,
                usedMs = usedMs,
                quotaMs = quotaMs,
                recheckAtMs = soonest(nowMs + remainingMs, boundaryMs),
            )
        } else {
            val releaseMs = RollingQuota.nextRelease(charged, nowMs, policy.windowMs, quotaMs)
            val liftsAtMs = soonest(releaseMs, windowEndsMs)
            Decision.Blocked(
                reason = BlockReason.QUOTA_EXHAUSTED,
                liftsAtMs = liftsAtMs,
                // The block lifting is itself a deadline, and it is the one branch here where
                // that is not also a schedule edge: a rolling window refills at an hour past
                // some old session, on a rule that may have no schedule at all. Left out, an
                // always-on quota rule stopped the ticker the moment it blocked, and the block
                // screen counted down to a lift time nothing was awake for. An app target has
                // no inspection loop to rescue it either, so it simply stayed blocked.
                recheckAtMs = soonest(liftsAtMs, boundaryMs),
            )
        }
    }

    /**
     * The parts of [sessions] this policy is allowed to charge for: the time it was live,
     * excluding visits tagged as pause-time ([UsageSession.countsTowardQuota] false).
     *
     * A no-op for an always-on rule, which is what makes this free for every rule that has no
     * hours. For one that does, see [Schedule.activeIntervalsWithin] for why the service closing
     * the session at the boundary is no longer enough on its own.
     */
    fun chargeable(
        policy: Policy,
        sessions: List<UsageSession>,
        nowMs: Long,
        tz: TimeZone = TimeZone.getDefault(),
    ): List<UsageSession> {
        val countable = sessions.filter { it.countsTowardQuota }
        if (policy.schedule.isAlwaysOn) return countable
        val windowStartMs = nowMs - policy.windowMs
        val live = policy.schedule.activeIntervalsWithin(windowStartMs, nowMs, tz)
        if (live.isEmpty()) return emptyList()
        val out = ArrayList<UsageSession>(countable.size)
        for (session in countable) {
            for ((liveStart, liveEnd) in live) {
                val lo = maxOf(session.startMs, liveStart)
                val hi = minOf(session.endMs, liveEnd)
                if (hi > lo) out.add(UsageSession(lo, hi))
            }
        }
        return out
    }

    /**
     * One answer for the target in front, since a target can be covered by more than one policy:
     * its own individual rule, and every group it belongs to.
     *
     * A target with its own rule is governed by *that rule alone* -- its friction, its greyscale,
     * its block, its countdown. The groups it also belongs to drop to pure accounting: their shared
     * timers still fill, because the ledger is keyed by target and each group's [chargeable] sums
     * its members' sessions, so this target still drains every group's cap for the other members --
     * it simply is not itself held to them. A no-wait app rule inside a group that asks for a wait
     * therefore keeps its no-wait behaviour rather than inheriting the group's.
     *
     * A target *without* its own rule -- a plain group member -- folds strictest-wins across the
     * groups covering it, which is the only reading that makes a shared cap mean anything: any group
     * blocking blocks; the block lifts when the last of them lets go; the countdown shows the least
     * time left; greyscale and friction are the union of the live ones.
     *
     * [sessionsFor] is a function rather than a list because a group's usage is the union of its
     * members' -- the ledger is keyed by target and knows nothing about groups, which is exactly
     * what lets a minute spent on one site be charged to every policy that covers it at once.
     */
    fun evaluateAll(
        policies: List<Policy>,
        sessionsFor: (Policy) -> List<UsageSession>,
        pausedUntilMs: Long,
        workoutUntilMs: Long?,
        nowMs: Long,
        tz: TimeZone = TimeZone.getDefault(),
    ): Verdict {
        if (policies.isEmpty()) return Verdict(Decision.Allow(), governing = null)
        val judged = policies.map { it to evaluate(it, sessionsFor(it), pausedUntilMs, workoutUntilMs, nowMs, tz) }

        // The individual rule for this exact target -- the only non-group policy that can cover it,
        // since a non-group policy charges only its own id. When present and enabled it governs
        // alone; a disabled one steps aside and the groups fold as normal. Behaviour and the ticker
        // both look only at the governing set: a group's deadline cannot change an answer the
        // group no longer decides, so arming for it would be a wakeup that changes nothing.
        val behavioral = judged.filter { it.first.kind != TargetKind.GROUP && it.first.enabled }
            .ifEmpty { judged }
        val recheckAtMs = behavioral.mapNotNull { it.second.recheckAtMs }.minOrNull()
        return foldVerdict(behavioral, recheckAtMs)
    }

    /**
     * Collapses the governing set into one [Verdict], strictest-wins. For a target with its own
     * rule this set is that single rule; for a plain group member it is every group covering it.
     */
    private fun foldVerdict(judged: List<Pair<Policy, Decision>>, recheckAtMs: Long?): Verdict {
        // Only a rule that is actually live gets to drain the colour or ask for a wait. One that is
        // paused, outside its hours, or already blocking is not governing the screen you are looking
        // at, and a group demanding friction for hours it does not cover is the reverse of what a
        // schedule means.
        val live = judged.filter { it.second is Decision.Allowed }
        val grayscale = live.any { it.first.grayscale }
        // The longest wait any live rule asks for, and which one asked -- the countdown is named
        // after the rule demanding it, which need not be the one with least time left.
        val frictionFrom = live.map { it.first }
            .filter { it.frictionSeconds > 0 }
            .maxByOrNull { it.frictionSeconds }
        val frictionSeconds = frictionFrom?.frictionSeconds ?: 0

        val blocked = judged.filter { it.second is Decision.Blocked }
        if (blocked.isNotEmpty()) {
            // Whichever holds you longest, so the screen names the constraint that is really in
            // the way. A block with no lift time at all outranks every dated one -- null here
            // means "nothing but a pause or an edit lifts this", not "lifts at zero".
            val governing = blocked.maxWith(
                compareBy(
                    { (it.second as Decision.Blocked).liftsAtMs == null },
                    { (it.second as Decision.Blocked).liftsAtMs ?: Long.MIN_VALUE },
                )
            )
            val decision = governing.second as Decision.Blocked
            return Verdict(
                decision = decision.copy(recheckAtMs = recheckAtMs),
                governing = governing.first,
                grayscale = false,
                frictionSeconds = frictionSeconds,
                frictionFrom = frictionFrom,
            )
        }

        val binding = live.minByOrNull { (it.second as Decision.Allowed).remainingMs }
        if (binding != null) {
            val decision = binding.second as Decision.Allowed
            return Verdict(
                decision = decision.copy(recheckAtMs = recheckAtMs),
                governing = binding.first,
                grayscale = grayscale,
                frictionSeconds = frictionSeconds,
                frictionFrom = frictionFrom,
            )
        }

        // Nothing is live. Any one of them being paused is enough to say so, since a pause covers
        // the whole phone rather than one rule.
        val paused = judged.firstNotNullOfOrNull { (it.second as? Decision.Allow)?.pausedUntilMs }
        return Verdict(
            decision = Decision.Allow(pausedUntilMs = paused, recheckAtMs = recheckAtMs),
            governing = judged.first().first,
        )
    }

    private fun soonest(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }
}
