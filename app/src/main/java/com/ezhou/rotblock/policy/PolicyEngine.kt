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

        val usedMs = RollingQuota.usedInWindow(sessions, nowMs, policy.windowMs)
        val remainingMs = quotaMs - usedMs
        return if (remainingMs > 0) {
            Decision.Allowed(
                remainingMs = remainingMs,
                usedMs = usedMs,
                quotaMs = quotaMs,
                recheckAtMs = soonest(nowMs + remainingMs, boundaryMs),
            )
        } else {
            val releaseMs = RollingQuota.nextRelease(sessions, nowMs, policy.windowMs, quotaMs)
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

    private fun soonest(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }
}
