package com.ezhou.rotblock.quota

/** A closed-open interval of foreground time, `[startMs, endMs)`. */
data class UsageSession(
    val startMs: Long,
    val endMs: Long,
    /**
     * False for time recorded while a pause was in effect. The interstitial mirror still
     * counts it; quota must not, or a pause would over-charge.
     */
    val countsTowardQuota: Boolean = true,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Rolling-window quota arithmetic.
 *
 * A quota of "3 minutes an hour" means 3 minutes of use out of *any* trailing 60 minutes, not
 * 3 minutes per clock hour. So time is not handed back in a lump at :00 — it trickles back as
 * individual old sessions age out of the window, which is what [nextRelease] computes.
 *
 * Everything here is pure: no clock, no database, no Android. Callers pass `nowMs`.
 */
object RollingQuota {

    /**
     * Sorts by start time and merges overlapping or touching sessions.
     *
     * Sessions written by the foreground ticker should never overlap, but a crash mid-session or
     * a clock adjustment can produce one. Merging keeps overlap from being counted twice, which
     * would otherwise make the user *lose* quota they never spent.
     */
    fun normalize(sessions: List<UsageSession>): List<UsageSession> {
        if (sessions.size <= 1) return sessions.filter { it.durationMs > 0 }
        val sorted = sessions.filter { it.durationMs > 0 }.sortedBy { it.startMs }
        val merged = ArrayList<UsageSession>(sorted.size)
        for (s in sorted) {
            val last = merged.lastOrNull()
            if (last != null && s.startMs <= last.endMs) {
                if (s.endMs > last.endMs) merged[merged.size - 1] = last.copy(endMs = s.endMs)
            } else {
                merged.add(s)
            }
        }
        return merged
    }

    /** Total foreground time inside the window `[nowMs - windowMs, nowMs]`. */
    fun usedInWindow(sessions: List<UsageSession>, nowMs: Long, windowMs: Long): Long {
        val windowStart = nowMs - windowMs
        var total = 0L
        for (s in normalize(sessions)) {
            val lo = maxOf(s.startMs, windowStart)
            val hi = minOf(s.endMs, nowMs)
            if (hi > lo) total += hi - lo
        }
        return total
    }

    /**
     * The first instant at or after [nowMs] when the target becomes usable again, or null if it
     * is usable right now.
     *
     * Assumes no session extends past [nowMs] — the caller closes the in-progress session first.
     * Under that assumption usage in the window only ever decreases as time passes, so this walks
     * sessions oldest-first and finds where enough of them have slid out of the window.
     */
    fun nextRelease(
        sessions: List<UsageSession>,
        nowMs: Long,
        windowMs: Long,
        quotaMs: Long,
    ): Long? {
        val normalized = normalize(sessions)
        val used = usedInWindow(normalized, nowMs, windowMs)
        if (used < quotaMs) return null

        // Milliseconds of old usage that must age out before `used` drops strictly below quota.
        var toErode = used - quotaMs + 1
        val windowStart = nowMs - windowMs

        for (s in normalized) {
            val lo = maxOf(s.startMs, windowStart)
            val hi = minOf(s.endMs, nowMs)
            val clipped = hi - lo
            if (clipped <= 0) continue
            if (clipped >= toErode) {
                // The window start only has to advance `toErode` into this session.
                return lo + toErode + windowMs
            }
            toErode -= clipped
        }

        // Should be unreachable: eroding every session in the window erodes exactly `used` ms,
        // and toErode <= used. Fall back to the whole window clearing out.
        return (normalized.lastOrNull()?.endMs ?: nowMs) + windowMs
    }
}
