package com.ezhou.rotblock.policy

/** How the Blocks screen lists its rules. Presentation only — nothing here affects enforcement. */
enum class PolicySort(val label: String) {
    /** Hardest rules first, by how much of the week they actually take the target away. */
    STRICTEST("Strictest"),

    /** Rules that have blocked most often first, counted over the ledger's retention window. */
    MOST_BLOCKED("Most blocked"),

    /** By name, the order the policy table has always returned. */
    NAME("A–Z"),
    ;

    companion object {
        val DEFAULT = NAME

        /** Decodes a stored name, falling back to [DEFAULT] rather than throwing on a stale value. */
        fun decode(name: String?): PolicySort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * How much of the week this rule keeps its target unavailable, as a fraction of 1.
 *
 * One number, so two rules written in different currencies — an hour-a-day hard block and a
 * three-minutes-an-hour allowance that never lapses — can be ranked against each other at all. It
 * is the fraction of the week the schedule is live, times the share of that time the rule refuses:
 *
 *   hard block, always on      1.0 * (1 - 0)      = 1.0
 *   3 min in any hour, always  1.0 * (1 - 0.05)   = 0.95
 *   hard block, 9am-5pm daily  0.33 * (1 - 0)     = 0.33
 *
 * This ranks rules; it does not predict them. A rolling quota does not spend itself evenly, and a
 * rule you never trip costs you nothing at all — but the ordering only has to be defensible and
 * stable, and "how much of your week does this rule stand between you and the thing" is the
 * question the list is actually being asked.
 *
 * Disabled is not zero-restrictive here; it is handled ahead of the score by [PolicySort.STRICTEST]
 * so an off rule sorts last even when a live one has an allowance wide enough to score 0.
 */
fun Policy.restrictionScore(): Double {
    // Coerced because a custom allowance can exceed its own window (9999 min out of any 30), which
    // is a rule that forbids nothing rather than one that hands time back.
    val allowance = quotaSeconds?.let { (it.toDouble() / windowSeconds).coerceIn(0.0, 1.0) } ?: 0.0
    return schedule.coverageFraction() * (1.0 - allowance)
}

private val byName: Comparator<Policy> =
    compareBy(String.CASE_INSENSITIVE_ORDER, Policy::label).thenBy(Policy::id)

/**
 * Strictest first: live rules before disabled ones, then by [restrictionScore].
 *
 * The tiebreakers exist so the order is total — two rules that score the same must not swap places
 * between refreshes, and the score ties often (every always-on hard block scores 1.0). A hard block
 * outranks a quota it ties with, a smaller allowance outranks a larger one, a countdown outranks
 * none, and the name settles the rest.
 */
private val byStrictness: Comparator<Policy> = compareByDescending<Policy> { it.enabled }
    .thenByDescending { it.restrictionScore() }
    .thenByDescending { it.quotaSeconds == null }
    .thenBy { it.quotaSeconds ?: 0 }
    .thenByDescending { it.frictionSeconds }
    .then(byName)

private fun byMostBlocked(blocksShown: Map<String, Int>): Comparator<Policy> =
    compareByDescending<Policy> { blocksShown[it.id] ?: 0 }.then(byName)

fun List<Policy>.sortedFor(sort: PolicySort, blocksShown: Map<String, Int>): List<Policy> = when (sort) {
    PolicySort.STRICTEST -> sortedWith(byStrictness)
    PolicySort.MOST_BLOCKED -> sortedWith(byMostBlocked(blocksShown))
    PolicySort.NAME -> sortedWith(byName)
}
