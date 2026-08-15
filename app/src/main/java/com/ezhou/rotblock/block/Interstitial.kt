package com.ezhou.rotblock.block

/**
 * Tracks whether one of rotblock's own full-screen screens is covering whatever you were using --
 * the friction countdown, or the pause flow.
 *
 * These run in rotblock's process, and rotblock's own package is excluded from foreground
 * tracking (otherwise the block overlay would look like an app switch). So without this the
 * service would happily keep charging the *target* for time you spent staring at a rotblock
 * screen: twenty seconds of friction eating twenty seconds of a three-minute quota, and a
 * sixty-second cooldown eating a third of it.
 *
 * Ownership sits with the activities' start/stop callbacks rather than with whoever launches
 * them. If an activity fails to launch, or is killed, or the process dies, the count is never
 * left stuck -- and every one of those failures resolves toward *resuming* accrual, which is the
 * safe direction. A gate stuck open would silently stop enforcing quotas.
 *
 * Counted rather than boolean because the friction screen can hand off to the pause flow.
 */
object Interstitial {

    /**
     * How long the gate may stay shut before it is assumed broken.
     *
     * Longer than anything legitimate -- the friction countdown caps at 120s and the pause cooldown
     * at 600s -- and short enough that a lost `exited()` costs a bounded amount of enforcement
     * rather than all of it. The gate stopping accrual is the one failure this object cannot detect
     * on its own: it looks exactly like an app that is never used, so quotas silently stop filling
     * and nothing is ever blocked again. Expressed as a deadline compared to the clock, like every
     * other relaxation in this app, so it heals itself.
     */
    private const val MAX_SHOWING_MS = 15 * 60 * 1000L

    @Volatile
    private var depth: Int = 0

    /** When the gate last went from open to shut, for [MAX_SHOWING_MS]. */
    @Volatile
    private var shutSinceMs: Long = 0L

    fun isShowing(nowMs: Long = System.currentTimeMillis()): Boolean =
        depth > 0 && nowMs - shutSinceMs < MAX_SHOWING_MS

    /**
     * Fired on the main thread the moment the last of our screens goes away.
     *
     * A screen of ours covering an app blinds every node read taken while it is up, and the app
     * underneath resumes *before* our activity stops -- so the window-state change that would
     * normally prompt a fresh read happens while we can still only see ourselves. Without this
     * there is no second chance: a static page emits nothing further, and the target stays
     * whatever the blinded read left it as.
     */
    @Volatile
    var onCleared: (() -> Unit)? = null

    /**
     * Fired on the main thread the moment the gate goes from open to shut.
     *
     * Symmetric with [onCleared], and exists for the same reason: nothing else tells the service
     * one of our screens just went up. Without it a friction countdown or a pause cooldown keeps
     * charging the target's quota, and the on-screen ring keeps counting down over a screen that
     * is not the target, until the next tick happens to notice -- up to [MAX_TICK_MS] away.
     *
     * Only on the 0 -> 1 transition: the friction screen handing off to the pause flow is one
     * continuous cover, not a fresh entry, and must not re-fire this.
     */
    @Volatile
    var onShown: (() -> Unit)? = null

    /** Call from `onStart`. Main thread only. */
    fun entered(nowMs: Long = System.currentTimeMillis()) {
        // A gate left shut past MAX_SHOWING_MS has already self-healed via [isShowing] while depth
        // was left non-zero -- some exited() call was lost. Treating this as a fresh entry resets
        // shutSinceMs so the new gate is not born already-expired, which would otherwise mean the
        // matching exited() could never bring depth back to 0 and onCleared would never fire again
        // for the life of the process.
        if (depth > 0 && nowMs - shutSinceMs >= MAX_SHOWING_MS) depth = 0
        if (depth == 0) {
            shutSinceMs = nowMs
            onShown?.invoke()
        }
        depth++
        trace()
    }

    /** Call from `onStop`. Main thread only. */
    fun exited() {
        depth = (depth - 1).coerceAtLeast(0)
        trace()
        if (depth == 0) onCleared?.invoke()
    }

    private fun trace() {
        if (com.ezhou.rotblock.BuildConfig.DEBUG) {
            // Swallowed rather than left to throw: android.util.Log is unmocked under
            // testDebugUnitTest, and a debug trace line must never be what keeps this object from
            // being tested as the pure JVM logic it otherwise is.
            runCatching { android.util.Log.d("rotblockFG", "Interstitial depth=$depth") }
        }
    }
}
