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

    @Volatile
    private var depth: Int = 0

    val isShowing: Boolean get() = depth > 0

    /** Call from `onStart`. Main thread only. */
    fun entered() {
        depth++
    }

    /** Call from `onStop`. Main thread only. */
    fun exited() {
        depth = (depth - 1).coerceAtLeast(0)
    }
}
