package com.ezhou.rotblock.block

/** How long a launch has to produce a screen before it is assumed to be going nowhere. */
private const val APPEAR_GRACE_MS = 5_000L

/**
 * Whether the breathing countdown is still owed for this visit.
 *
 * The distinction this exists for is **shown versus served**. Marking the visit spent at the
 * moment the screen was launched is what made it bypassable: back out of the countdown and the
 * app underneath resumes, and because rotblock's own package is excluded from foreground tracking
 * nothing fires to re-arm it -- no foreground change, no target change, no event at all. You were
 * simply in, with the wait recorded as done. It is the same shape as every other failure here:
 * something hid a gate on the strength of an action having *started*, and nothing was left to
 * notice it never finished.
 *
 * So only the countdown reaching zero serves it, and that fact can only come from the countdown
 * itself -- see [onServed]. Anything else that takes the screen away (back, recents, a killed
 * activity, a dead process) leaves the wait owed, and the next evaluation puts it up again.
 *
 * There is no cap on that. Re-showing costs a user action every time, so it cannot become a loop;
 * the loop the original code feared is a *launch that produces nothing*, which is a different
 * thing and is what [APPEAR_GRACE_MS] answers. A launch that never reports [appeared] within the
 * grace is treated as broken and the visit gives up, because retrying it on every redraw of a
 * busy page would be far worse than skipping one countdown.
 *
 * Pure and main-thread-only: the service owns the instance and the clock.
 */
class FrictionGate {

    /** The target whose countdown has actually been sat through this visit, if any. */
    var servedFor: String? = null
        private set

    private var launchedFor: String? = null
    private var launchedAtMs = 0L
    private var appeared = false
    private var brokenFor: String? = null

    /**
     * @param interstitialShowing whether one of our own screens is already covering the app; the
     *   countdown must not stack on top of itself or on top of the pause flow.
     */
    fun shouldLaunch(targetId: String, interstitialShowing: Boolean, nowMs: Long): Boolean {
        if (servedFor == targetId) return false
        if (interstitialShowing) return false
        if (brokenFor == targetId) return false
        if (launchedFor == targetId && !appeared) {
            // Either it is on its way up -- a busy page evaluates several times a second and the
            // activity's onStart has not run yet -- or it is never coming. Both look the same from
            // here, so wait out the grace before deciding which.
            if (nowMs - launchedAtMs < APPEAR_GRACE_MS) return false
            brokenFor = targetId
            return false
        }
        return true
    }

    fun launched(targetId: String, nowMs: Long) {
        launchedFor = targetId
        launchedAtMs = nowMs
        appeared = false
    }

    /** The countdown is on screen. Reported by the countdown, because nothing else can see it. */
    fun appeared(targetId: String) {
        if (launchedFor == targetId) appeared = true
    }

    /** The countdown reached zero. The only thing that spends the wait. */
    fun served(targetId: String) {
        servedFor = targetId
    }

    /** The visit ended -- a foreground change, a target change, or the screen going off. */
    fun endVisit() {
        servedFor = null
        launchedFor = null
        launchedAtMs = 0L
        appeared = false
        brokenFor = null
    }

    /**
     * Restores a served mark across a rebind. Only the mark: a launch that was in flight when the
     * process went away resolves toward showing the countdown again, which is the safe direction.
     */
    fun restoreServed(targetId: String?) {
        endVisit()
        servedFor = targetId
    }

    companion object {
        /**
         * How the countdown reports back to the service. Both are called on the main thread by
         * `FrictionActivity`, and both are cleared on teardown -- a signal lost across a rebind
         * costs one extra countdown, never a skipped one.
         */
        @Volatile
        var onShown: ((String) -> Unit)? = null

        @Volatile
        var onServed: ((String) -> Unit)? = null

        /**
         * The countdown was declined -- "Not really" or back -- rather than waited out or left to
         * time out. Reported with the target it covered so the service can treat the page it moved
         * away from as stale rather than merely unconfirmed; see
         * [com.ezhou.rotblock.service.RotblockAccessibilityService]'s `noteEscaped`.
         */
        @Volatile
        var onDeclined: ((String) -> Unit)? = null
    }
}
