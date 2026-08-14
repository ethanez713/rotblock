package com.ezhou.rotblock.setup

import com.ezhou.rotblock.block.FeedSpec

/**
 * Noticing that a `FeedRegistry` view id has stopped matching.
 *
 * The registry is the most brittle surface in the app: app updates rename view ids, and a miss
 * degrades to "not blocked" on purpose — silently, and for as long as the rule stays written. There
 * is no way to tell that from the outside, because "not blocked" is also what a rule you simply have
 * not exercised looks like.
 *
 * The signal is **"we looked and never found"**, counted where the looking already happens. Note
 * what it deliberately is not built from: rotblock's own usage ledger only records targets that have
 * an enabled policy, and there is no `UsageStatsManager` integration, so "the host app was used a
 * lot" is not a fact this app has access to. `looks` is the denominator instead, and it is a real
 * one because a feed spec names its host package.
 *
 * Pure and Android-free for the same reason [requirementsFor] is: the interesting part is which
 * combination of counts means rot rather than disuse, and that should be testable without a device.
 */

/** One feed's tally: how often its package was inspected, and how often its ids were found. */
data class FeedProbe(val feedKey: String, val label: String, val looks: Int, val hits: Int)

/**
 * How many inspections of the host app must go by with no match before a feed is called rotted.
 *
 * Inspections are debounced and rate-limited, so 50 is a good many visits rather than a good many
 * seconds of one.
 */
const val MIN_LOOKS = 50

/** How old the counting window must be before its silence means anything. */
const val MIN_WINDOW_MS = 3 * 24 * 60 * 60 * 1000L

/**
 * How long a window runs before the whole tally is thrown away and started again.
 *
 * Bounded so that a registry id fixed months ago cannot keep an alarm up on the strength of misses
 * recorded before the fix, and so the stored string cannot grow without limit.
 */
const val WINDOW_RESET_MS = 14 * 24 * 60 * 60 * 1000L

/**
 * The feeds that look rotted.
 *
 * All three conditions are load-bearing and each rules out a different innocent explanation: below
 * [MIN_LOOKS] the app has barely been opened, a single hit proves the id still matches, and inside
 * [MIN_WINDOW_MS] a fresh install has not had time to see anything. This is a heuristic, and the
 * requirement it raises says so by being RECOMMENDED rather than REQUIRED.
 */
fun rottedFeeds(probes: List<FeedProbe>, windowAgeMs: Long): List<FeedProbe> {
    if (windowAgeMs < MIN_WINDOW_MS) return emptyList()
    return probes.filter { it.looks >= MIN_LOOKS && it.hits == 0 }
}

/**
 * The two counters the service keeps, and their window.
 *
 * `looks` is per package because that is what gets inspected; `hits` is per feed key because two
 * feeds can share a package and rot independently.
 */
data class DetectionCounters(
    val windowStartMs: Long = 0L,
    val looks: Map<String, Int> = emptyMap(),
    val hits: Map<String, Int> = emptyMap(),
) {
    fun withLook(packageName: String) =
        copy(looks = looks + (packageName to (looks[packageName] ?: 0) + 1))

    fun withHit(feedKey: String) =
        copy(hits = hits + (feedKey to (hits[feedKey] ?: 0) + 1))

    /** Starts a fresh window when the current one has run its course, or has never been started. */
    fun rolled(nowMs: Long): DetectionCounters =
        if (windowStartMs == 0L || nowMs - windowStartMs >= WINDOW_RESET_MS) {
            DetectionCounters(windowStartMs = nowMs)
        } else {
            this
        }

    fun probes(specs: List<FeedSpec>): List<FeedProbe> = specs.map { spec ->
        FeedProbe(
            feedKey = spec.key,
            label = spec.label,
            looks = looks[spec.packageName] ?: 0,
            hits = hits[spec.key] ?: 0,
        )
    }

    fun encode(): String = listOf(
        windowStartMs.toString(),
        looks.entries.joinToString(ENTRY) { "${it.key}$PAIR${it.value}" },
        hits.entries.joinToString(ENTRY) { "${it.key}$PAIR${it.value}" },
    ).joinToString(FIELD)

    companion object {
        /**
         * Anything unreadable decodes to an empty tally, which means "no alarm" — the same
         * direction every other degenerate case in this app resolves to, and the safe one here:
         * these counters are diagnostic and a lost window costs nothing but a later warning.
         */
        fun decode(encoded: String?): DetectionCounters {
            if (encoded.isNullOrBlank()) return DetectionCounters()
            val fields = encoded.split(FIELD)
            if (fields.size != 3) return DetectionCounters()
            val start = fields[0].toLongOrNull() ?: return DetectionCounters()
            return DetectionCounters(
                windowStartMs = start,
                looks = decodeCounts(fields[1]),
                hits = decodeCounts(fields[2]),
            )
        }

        private fun decodeCounts(field: String): Map<String, Int> =
            field.split(ENTRY)
                .mapNotNull { entry ->
                    val key = entry.substringBefore(PAIR, "")
                    val count = entry.substringAfter(PAIR, "").toIntOrNull()
                    if (key.isBlank() || count == null) null else key to count
                }
                .toMap()
    }
}

private const val FIELD = "|"
private const val ENTRY = ","
private const val PAIR = "="
