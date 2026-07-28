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

    /** Kept small: this is the last few seconds of reasoning, not a history. */
    private const val CAPACITY = 40

    data class Entry(val atMs: Long, val text: String, val repeats: Int = 1)

    data class Snapshot(
        val connected: Boolean,
        val currentPackage: String?,
        val currentTargetId: String?,
        val accruingTargetId: String?,
        val interstitialShowing: Boolean,
        val lastReadAtMs: Long,
        val lastRead: String?,
        val trail: List<Entry>,
    )

    @Volatile private var connected: Boolean = false
    @Volatile private var currentPackage: String? = null
    @Volatile private var currentTargetId: String? = null
    @Volatile private var accruingTargetId: String? = null
    @Volatile private var interstitialShowing: Boolean = false
    @Volatile private var lastReadAtMs: Long = 0L
    @Volatile private var lastRead: String? = null

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
    ) {
        currentPackage = pkg
        currentTargetId = targetId
        accruingTargetId = accruing
        interstitialShowing = interstitial
    }

    fun noteRead(pkg: String, url: String?, feedKey: String?, result: Inspected) {
        lastReadAtMs = System.currentTimeMillis()
        lastRead = buildString {
            append(pkg.substringAfterLast('.'))
            if (url != null) append(" bar=\"$url\"")
            if (feedKey != null) append(" feed=$feedKey")
            append(" -> ")
            append(
                when (result) {
                    is Inspected.Target -> result.targetId
                    Inspected.AppOnly -> "app only"
                    Inspected.Unreadable -> "unreadable (target held)"
                },
            )
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
        trail = synchronized(trail) { trail.toList().asReversed() },
    )
}
