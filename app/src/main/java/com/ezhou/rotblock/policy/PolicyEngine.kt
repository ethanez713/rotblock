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
     * How a [Policy] component combines when several policies cover one target -- its own individual
     * rule and every group it belongs to. Every component belongs to exactly one of these, and the
     * fold is split so that adding a component means placing it in one, not writing a fresh branch.
     *
     *  - [SHARED_STRICTEST] -- the **budget**: block, countdown, ticker. Every covering policy has a
     *    say and the strictest wins, because a shared cap a per-target rule could undercut would not
     *    be a cap. Folded by [foldBudget].
     *  - [OWN_WINS] -- **behaviour**: friction wait, greyscale. The target's own rule decides alone;
     *    the groups apply only as a fallback when there is no live own rule. Folded by [foldBehaviour]
     *    through [ownElseGroups].
     *  - [COUNT_ALL] -- **accrual**: a minute is charged to every covering policy at once. Not folded
     *    here -- realised by the ledger being keyed by target plus [Policy.chargedTargets]/[chargeable].
     *
     * When a new component is added to [Policy], it is added to whichever of these its nature fits,
     * and to the matching fold. There is no fourth option and no "default".
     */
    enum class MergeStrategy { SHARED_STRICTEST, OWN_WINS, COUNT_ALL }

    /**
     * One answer folded from every policy covering the target in front, since a target can be
     * covered by more than one: its own individual rule, and every group it belongs to. The budget
     * ([foldBudget]) and the behaviour ([foldBehaviour]) fold by different rules -- see [MergeStrategy].
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
        val recheckAtMs = judged.mapNotNull { it.second.recheckAtMs }.minOrNull()

        val budget = foldBudget(judged, recheckAtMs)
        val behaviour = foldBehaviour(judged)
        // A blocked screen is never greyscale: you are not in the app to have its colour drained.
        val grayscale = if (budget.decision is Decision.Blocked) false else behaviour.grayscale
        return Verdict(
            decision = budget.decision,
            governing = budget.governing,
            grayscale = grayscale,
            frictionSeconds = behaviour.frictionSeconds,
            frictionFrom = behaviour.frictionFrom,
        )
    }

    private data class BudgetVerdict(val decision: Decision, val governing: Policy?)

    /**
     * The [MergeStrategy.SHARED_STRICTEST] fold: block, countdown and ticker, strictest-wins across
     * every covering policy, so an app in a group is blocked as soon as *either* its own quota or the
     * group's is spent.
     */
    private fun foldBudget(judged: List<Pair<Policy, Decision>>, recheckAtMs: Long?): BudgetVerdict {
        val blocked = judged.filter { it.second is Decision.Blocked }
        if (blocked.isNotEmpty()) {
            // Whichever holds you longest, so the screen names the constraint that is really in the
            // way. A block with no lift time at all outranks every dated one -- null here means
            // "nothing but a pause or an edit lifts this", not "lifts at zero".
            val governing = blocked.maxWith(
                compareBy(
                    { (it.second as Decision.Blocked).liftsAtMs == null },
                    { (it.second as Decision.Blocked).liftsAtMs ?: Long.MIN_VALUE },
                )
            )
            val decision = governing.second as Decision.Blocked
            return BudgetVerdict(decision.copy(recheckAtMs = recheckAtMs), governing.first)
        }
        // The countdown shows the least time left of any of them, since that is the one that blocks
        // first.
        val binding = judged.filter { it.second is Decision.Allowed }
            .minByOrNull { (it.second as Decision.Allowed).remainingMs }
        if (binding != null) {
            val decision = binding.second as Decision.Allowed
            return BudgetVerdict(decision.copy(recheckAtMs = recheckAtMs), binding.first)
        }
        // Nothing is live. Any one being paused is enough to say so, since a pause covers the whole
        // phone rather than one rule.
        val paused = judged.firstNotNullOfOrNull { (it.second as? Decision.Allow)?.pausedUntilMs }
        return BudgetVerdict(Decision.Allow(pausedUntilMs = paused, recheckAtMs = recheckAtMs), judged.first().first)
    }

    private data class Behaviour(val grayscale: Boolean, val frictionSeconds: Int, val frictionFrom: Policy?)

    /**
     * The [MergeStrategy.OWN_WINS] fold: friction and greyscale belong to the target's own rule. When
     * it has a live individual rule those are that rule's alone, so a group can neither add a wait nor
     * drain the colour on a member that has its own rule; with no live own rule they fall back to the
     * live groups. Only a *live* rule (paused, off-hours and blocking ones contribute neither) has a say.
     */
    private fun foldBehaviour(judged: List<Pair<Policy, Decision>>): Behaviour {
        val ownLive = judged.firstOrNull {
            it.first.kind != TargetKind.GROUP && it.first.enabled && it.second is Decision.Allowed
        }?.first
        val liveGroups = judged.filter { it.first.kind == TargetKind.GROUP && it.second is Decision.Allowed }
            .map { it.first }
        val grayscale = ownElseGroups(ownLive, liveGroups, { it.grayscale }, { gs -> gs.any { it.grayscale } })
        val frictionFrom = ownElseGroups(
            ownLive, liveGroups,
            { it.takeIf { p -> p.frictionSeconds > 0 } },
            { gs -> gs.filter { it.frictionSeconds > 0 }.maxByOrNull { it.frictionSeconds } },
        )
        return Behaviour(grayscale, frictionFrom?.frictionSeconds ?: 0, frictionFrom)
    }

    /**
     * The one way an [MergeStrategy.OWN_WINS] component combines: the target's own rule when it has a
     * live one, otherwise the groups. Every behaviour component goes through this, so the own-wins rule
     * cannot be forgotten by copying a nearby branch.
     */
    private fun <T> ownElseGroups(
        ownLive: Policy?,
        liveGroups: List<Policy>,
        own: (Policy) -> T,
        groups: (List<Policy>) -> T,
    ): T = if (ownLive != null) own(ownLive) else groups(liveGroups)

    private fun soonest(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }
}
