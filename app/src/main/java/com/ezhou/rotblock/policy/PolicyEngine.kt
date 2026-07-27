package com.ezhou.rotblock.policy

import com.ezhou.rotblock.quota.RollingQuota
import com.ezhou.rotblock.quota.UsageSession

/** Why a target was blocked. Surfaced verbatim on the block screen and in the audit log. */
enum class BlockReason { HARD_BLOCK, QUOTA_EXHAUSTED }

sealed interface Decision {
    /** No policy, policy disabled, or a pause is in effect. */
    data class Allow(val pausedUntilMs: Long? = null) : Decision

    /** Within quota. [remainingMs] is how much is left before the block lands. */
    data class Allowed(val remainingMs: Long, val usedMs: Long, val quotaMs: Long) : Decision

    /**
     * Blocked. [liftsAtMs] is when the block lifts on its own (quota ageing out), or null if
     * nothing but a pause or a policy change will lift it.
     */
    data class Blocked(val reason: BlockReason, val liftsAtMs: Long?) : Decision
}

/**
 * Decides what to do about a target, from persisted state only.
 *
 * The fail-closed invariant lives here: this function is a pure fold over a timestamp and stored
 * rows. It never consults a timer, a flag, or an "is blocking currently on" boolean. If the
 * process died mid-pause and came back, the same inputs still produce the same answer, so a
 * crash, a force-stop or a reboot cannot leave a target unblocked.
 */
object PolicyEngine {

    fun evaluate(
        policy: Policy?,
        sessions: List<UsageSession>,
        pausedUntilMs: Long,
        nowMs: Long,
    ): Decision {
        if (policy == null || !policy.enabled) return Decision.Allow()
        if (nowMs < pausedUntilMs) return Decision.Allow(pausedUntilMs = pausedUntilMs)

        val quotaMs = policy.quotaMs ?: return Decision.Blocked(BlockReason.HARD_BLOCK, null)

        val usedMs = RollingQuota.usedInWindow(sessions, nowMs, policy.windowMs)
        val remainingMs = quotaMs - usedMs
        return if (remainingMs > 0) {
            Decision.Allowed(remainingMs = remainingMs, usedMs = usedMs, quotaMs = quotaMs)
        } else {
            Decision.Blocked(
                reason = BlockReason.QUOTA_EXHAUSTED,
                liftsAtMs = RollingQuota.nextRelease(sessions, nowMs, policy.windowMs, quotaMs),
            )
        }
    }
}
