package com.ezhou.rotblock.browser

/**
 * Requires two consecutive, agreeing [LinkConsensus] verdicts before a target may be *acquired*
 * from them.
 *
 * A single scan is an inference from a tree that may be mid-load -- a page still rendering its own
 * navigation could plausibly show a skewed sample for one read. Two scans about
 * [com.ezhou.rotblock.service.MIN_PAGE_SCAN_INTERVAL_MS] apart landing on the same host is the
 * confidence a false-positive-averse acquisition route needs, and it costs one extra scan's worth
 * of latency on acquisition -- which this app already accepts as "a few seconds of unmetered time
 * once per visit".
 *
 * Consecutive, not merely twice-ever: an offer that says something else, or says nothing at all,
 * replaces whatever was pending rather than being ignored, so agreement always has to come from the
 * two most recent scans.
 */
class LinkCorroboration(private val freshMs: Long = 30_000L) {

    private data class Pending(val host: String, val atMs: Long)

    private val pendingByPackage = HashMap<String, Pending>()

    /**
     * Returns [host] only when the previous offer for [pkg] named the same host within [freshMs].
     *
     * A null [host] -- this scan reached no consensus -- clears whatever was pending, so a stray
     * unanimous read cannot be corroborated by an agreement that happened long before it and is no
     * longer about the same page.
     */
    fun offer(pkg: String, host: String?, nowMs: Long): String? {
        if (host == null) {
            pendingByPackage.remove(pkg)
            return null
        }
        val prev = pendingByPackage[pkg]
        // Bounded the same way VisitMemory and SearchPageMemory are: in practice this only ever
        // holds the handful of browsers actually in use.
        if (pendingByPackage.size > 32 && !pendingByPackage.containsKey(pkg)) pendingByPackage.clear()
        pendingByPackage[pkg] = Pending(host, nowMs)
        if (prev == null || prev.host != host) return null
        if (nowMs - prev.atMs >= freshMs) return null
        return host
    }

    /** Diagnostic only -- what each package's most recent, not-yet-corroborated offer said. */
    fun snapshot(): Map<String, Pair<String, Long>> =
        pendingByPackage.mapValues { it.value.host to it.value.atMs }
}
