package com.ezhou.rotblock.policy

enum class ChangeDirection { STRENGTHENING, WEAKENING, NEUTRAL }

/**
 * Decides whether a policy edit tightens the rules or loosens them.
 *
 * This is the asymmetry the whole app turns on: tightening applies instantly, loosening waits.
 * That way a decision made calmly takes effect immediately, and a decision made while you are
 * itching to scroll has to survive an hour before it counts for anything.
 *
 * Two deliberate choices:
 * - **Any weakening dimension makes the whole edit a weakening.** Raising a quota while also
 *   adding friction is still delayed. Mixed edits fail closed.
 * - **Disabling is the most permissive state there is**, more so than any quota, because it turns
 *   the rule off entirely.
 */
object PolicyChange {

    /**
     * Fraction of time the policy lets through: 0.0 for a hard block, 1.0 for "always allowed",
     * and [Double.MAX_VALUE] for a disabled rule. Higher means more permissive.
     */
    fun permissiveness(policy: Policy): Double {
        if (!policy.enabled) return Double.MAX_VALUE
        val quota = policy.quotaSeconds ?: return 0.0
        if (policy.windowSeconds <= 0) return 0.0
        return quota.toDouble() / policy.windowSeconds.toDouble()
    }

    fun classify(old: Policy?, new: Policy?): ChangeDirection {
        if (old == null && new == null) return ChangeDirection.NEUTRAL
        // Adding a rule where there was none can only tighten things.
        if (old == null) return ChangeDirection.STRENGTHENING
        // Removing a rule is the largest weakening available.
        if (new == null) return ChangeDirection.WEAKENING

        val oldPerm = permissiveness(old)
        val newPerm = permissiveness(new)

        val weakens = newPerm > oldPerm ||
            new.frictionSeconds < old.frictionSeconds ||
            (old.grayscale && !new.grayscale)
        if (weakens) return ChangeDirection.WEAKENING

        val strengthens = newPerm < oldPerm ||
            new.frictionSeconds > old.frictionSeconds ||
            (!old.grayscale && new.grayscale)
        return if (strengthens) ChangeDirection.STRENGTHENING else ChangeDirection.NEUTRAL
    }

    /** A short human description of an edit, for the queue UI and the audit log. */
    fun describe(old: Policy?, new: Policy?): String = when {
        old != null && new == null -> "Remove the policy for ${old.label}"
        old == null && new != null -> "Add a policy for ${new.label}"
        old != null && new != null && old.enabled && !new.enabled -> "Turn off ${new.label}"
        old != null && new != null && !old.enabled && new.enabled -> "Turn on ${new.label}"
        new != null -> "Change ${new.label} to ${describeAllowance(new)}"
        else -> "Change a policy"
    }

    private fun describeAllowance(policy: Policy): String {
        val quota = policy.quotaSeconds ?: return "a full block"
        return "${quota / 60} min per ${policy.windowSeconds / 60} min"
    }
}
