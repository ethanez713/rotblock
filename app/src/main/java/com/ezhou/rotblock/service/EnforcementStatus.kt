package com.ezhou.rotblock.service

/**
 * What enforcement currently thinks, and how it got there.
 *
 * Exists because the failures this app has had all look identical from the outside: the app is on
 * screen, nothing is blocked, and no quota is filling. "It is not registering reddit" could be a
 * dropped target, a service that is not bound, an address bar that never reads, a paused policy,
 * or an interstitial gate stuck shut -- and from the block screen's absence you cannot tell which.
 * The audit log answers "what did you do to me"; this answers "what do you believe right now".
 *
 * In memory only, and deliberately so. Writing a diagnostic trace to disk would put a write on a
 * path that runs several times a second, for a number nobody reads unless something is wrong.
 * A rebind clears it; the audit log is what records that the rebind happened.
 */
object EnforcementStatus {

    /**
     * A diagnostic capture (see [com.ezhou.rotblock.diag.Diagnostics]) is taken *after* something
     * has already gone wrong, which means the useful part of the trail is whatever happened before
     * the user noticed and went looking for the Quick Settings tile -- so this has to hold minutes,
     * not seconds. A few hundred short strings is a few KB, and nothing on the path that writes
     * them gets any heavier for it.
     */
    private const val CAPACITY = 300

    data class Entry(val atMs: Long, val text: String, val repeats: Int = 1)

    data class Snapshot(
        val connected: Boolean,
        val currentPackage: String?,
        val currentTargetId: String?,
        val accruingTargetId: String?,
        val interstitialShowing: Boolean,
        val lastReadAtMs: Long,
        val lastRead: String?,
        /**
         * What the node reads have cost, since this instance connected.
         *
         * The one number no `dumpsys` can produce. A tree read is an IPC plus a traversal *in the
         * foreground app's process*, so the CPU it burns is charged to that app's battery budget,
         * not to rotblock's -- which means "is this making my phone slow" is invisible from the
         * outside no matter how carefully rotblock's own usage is measured. This is the inside view.
         */
        val readCount: Int = 0,
        val readTotalMs: Long = 0L,
        val readMaxMs: Long = 0L,
        val trail: List<Entry>,
        /** Null when workout mode is not live. The deadline it lapses at otherwise. */
        val workoutUntilMs: Long?,
        /** How the live workout was established -- "notification", "manual", or null. */
        val workoutSource: String?,
    )

    @Volatile private var connected: Boolean = false
    @Volatile private var currentPackage: String? = null
    @Volatile private var currentTargetId: String? = null
    @Volatile private var accruingTargetId: String? = null
    @Volatile private var interstitialShowing: Boolean = false
    @Volatile private var lastReadAtMs: Long = 0L
    @Volatile private var lastRead: String? = null
    @Volatile private var readCount: Int = 0
    @Volatile private var readTotalMs: Long = 0L
    @Volatile private var readMaxMs: Long = 0L
    @Volatile private var workoutUntilMs: Long? = null
    @Volatile private var workoutSource: String? = null

    private val trail = ArrayDeque<Entry>()

    fun setConnected(value: Boolean) {
        connected = value
        note(if (value) "service connected" else "service disconnected")
    }

    /** Called from the service's main-thread paths on every evaluation. */
    fun update(
        pkg: String?,
        targetId: String?,
        accruing: String?,
        interstitial: Boolean,
        workoutUntil: Long?,
        workoutSource: String?,
    ) {
        currentPackage = pkg
        currentTargetId = targetId
        accruingTargetId = accruing
        interstitialShowing = interstitial
        this.workoutUntilMs = workoutUntil
        this.workoutSource = workoutSource
    }

    fun noteRead(
        pkg: String,
        url: String?,
        feedKey: String?,
        result: Inspected,
        linkUrls: Int = 0,
        signature: String? = null,
        durationMs: Long = 0L,
    ) {
        lastReadAtMs = System.currentTimeMillis()
        readCount++
        readTotalMs += durationMs
        if (durationMs > readMaxMs) readMaxMs = durationMs
        lastRead = buildString {
            append(pkg.substringAfterLast('.'))
            if (url != null) append(" bar=\"$url\"")
            if (feedKey != null) append(" feed=$feedKey")
            // Distinguishes "the bar was gone and the page's links identified it" from a normal
            // read, which is the difference between the two ways a website target gets acquired.
            if (linkUrls > 0) append(" links=$linkUrls")
            // A third route, weaker than both: PageSignature matched structurally even though
            // neither the bar nor the links could. Shown whether or not a rule ended up covering
            // it, because "we saw the Shorts player but nothing blocked" is itself the answer to
            // why a rule looks like it is not firing.
            if (signature != null) append(" sig=$signature")
            append(" -> ")
            append(
                when (result) {
                    is Inspected.Target -> result.targetId
                    Inspected.AppOnly -> "app only"
                    Inspected.Unreadable -> "unreadable (target held)"
                },
            )
            // Only when it is worth reading. A read that took no measurable time is the common
            // case and saying so on every line would push the part that identifies the page off
            // the end of the Right now panel.
            if (durationMs > 0) append(" took=${durationMs}ms")
        }
    }

    /**
     * Adds a line to the trail, collapsing an immediate repeat into a count.
     *
     * Without the collapse a steady state -- one line a second saying the same thing -- would push
     * the transition that actually explains something off the end within half a minute.
     */
    fun note(text: String) {
        synchronized(trail) {
            val last = trail.lastOrNull()
            if (last != null && last.text == text) {
                trail[trail.size - 1] = last.copy(atMs = System.currentTimeMillis(), repeats = last.repeats + 1)
                return
            }
            trail.addLast(Entry(System.currentTimeMillis(), text))
            while (trail.size > CAPACITY) trail.removeFirst()
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        connected = connected,
        currentPackage = currentPackage,
        currentTargetId = currentTargetId,
        accruingTargetId = accruingTargetId,
        interstitialShowing = interstitialShowing,
        lastReadAtMs = lastReadAtMs,
        lastRead = lastRead,
        readCount = readCount,
        readTotalMs = readTotalMs,
        readMaxMs = readMaxMs,
        trail = synchronized(trail) { trail.toList().asReversed() },
        workoutUntilMs = workoutUntilMs,
        workoutSource = workoutSource,
    )
}
