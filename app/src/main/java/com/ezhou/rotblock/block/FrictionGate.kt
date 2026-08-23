package com.ezhou.rotblock.block

/** How long a launch has to produce a screen before it is assumed to be going nowhere. */
private const val APPEAR_GRACE_MS = 5_000L

/**
 * How long a relaunch is held off after the screen goes away, once it has appeared at least once.
 *
 * A backstop, not the primary guard -- [FrictionGate.onScreenFor] is what actually distinguishes a
 * declined countdown from the screen merely blinking. This exists because the blink's cause is not
 * fully understood: a device capture showed `Interstitial`'s depth flipping 1<->0 at 20-60Hz with
 * the wait never served, which is far faster than any real user action, and there is no proof that
 * is the only way a transient false reading can arrive. A genuine re-show follows a user action --
 * back, or a fresh visit -- which takes far longer than a second, so this costs the healthy case
 * nothing while bounding the damage from whatever causes the flip until that cause is found.
 */
private const val MIN_RELAUNCH_INTERVAL_MS = 1_000L

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
 * There is no cap on a genuine re-show, because re-showing costs a user action every time -- back,
 * or a fresh visit. What that argument does not cover is a *transient* reading of the screen being
 * gone that no user action produced: a device capture caught `Interstitial`'s depth flipping
 * 1<->0 at 20-60Hz with the wait never served, and naively relaunching on every depth-0 reading
 * restarted the countdown before it could ever reach zero, which is a loop in fact whatever the
 * original reasoning here claimed. [onScreenFor] and [MIN_RELAUNCH_INTERVAL_MS] are what closes
 * that: the screen's own report of being on screen is trusted over a momentary gate reading, and a
 * relaunch is throttled for a beat after the screen last appeared regardless. Separately,
 * [APPEAR_GRACE_MS] answers the *other* loop this class has to refuse -- a launch that never
 * produces a screen at all. A launch that never reports [appeared] within the grace is treated as
 * broken and the visit gives up, because retrying it on every redraw of a busy page would be far
 * worse than skipping one countdown.
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
     * The target whose countdown is actually on screen right now, reported by the screen itself --
     * the only thing that can see itself. Set by [appeared], cleared by [hidden]. `shouldLaunch`
     * refuses to relaunch while this holds the target being asked about, which is what stops a
     * transient false reading of [interstitialShowing] from restarting a countdown that is still
     * up; see the class doc.
     */
    private var onScreenFor: String? = null

    /**
     * @param interstitialShowing whether one of our own screens is already covering the app; the
     *   countdown must not stack on top of itself or on top of the pause flow.
     */
    fun shouldLaunch(targetId: String, interstitialShowing: Boolean, nowMs: Long): Boolean {
        if (servedFor == targetId) return false
        if (interstitialShowing) return false
        if (onScreenFor == targetId) return false
        if (brokenFor == targetId) return false
        if (launchedFor == targetId && !appeared) {
            // Either it is on its way up -- a busy page evaluates several times a second and the
            // activity's onStart has not run yet -- or it is never coming. Both look the same from
            // here, so wait out the grace before deciding which.
            if (nowMs - launchedAtMs < APPEAR_GRACE_MS) return false
            brokenFor = targetId
            return false
        }
        if (appeared && launchedFor == targetId && nowMs - launchedAtMs < MIN_RELAUNCH_INTERVAL_MS) {
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
        onScreenFor = targetId
    }

    /** The countdown left the screen without being declined or served -- a transient gate flip. */
    fun hidden(targetId: String) {
        if (onScreenFor == targetId) onScreenFor = null
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
        onScreenFor = null
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

        /**
         * The countdown left the screen -- `onStop`, reported the same way `onShown` is reported
         * from `onStart`. This is what lets [shouldLaunch] tell a screen that is still up from one
         * that merely read as up for an instant; see the class doc.
         */
        @Volatile
        var onHidden: ((String) -> Unit)? = null

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
