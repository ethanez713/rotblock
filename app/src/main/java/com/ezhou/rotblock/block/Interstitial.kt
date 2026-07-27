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

    val isShowing: Boolean
        get() = depth > 0 && System.currentTimeMillis() - shutSinceMs < MAX_SHOWING_MS

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

    /** Call from `onStart`. Main thread only. */
    fun entered() {
        if (depth == 0) shutSinceMs = System.currentTimeMillis()
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
            android.util.Log.d("rotblockFG", "Interstitial depth=$depth")
        }
    }
}
