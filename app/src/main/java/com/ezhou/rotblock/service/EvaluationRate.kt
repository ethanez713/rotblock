package com.ezhou.rotblock.service

/**
 * How many evaluations inside one second are more than anything on the phone can legitimately
 * cause.
 *
 * Everything that drives [RotblockAccessibilityService.evaluateAndEnforce] is bounded. The ticker
 * floors at `MIN_TICK_MS`, one a second. The framework coalesces accessibility events at the
 * `notificationTimeout` of 200ms, per event type, and this service subscribes to two -- about ten
 * a second between them. Interstitial and friction callbacks add a handful per screen transition,
 * and each of those costs a user action.
 *
 * Twenty is clear of all of that together, and far below the sixty-two a second measured on device
 * during the storms this exists to catch.
 */
const val STORM_EVALUATIONS_PER_SECOND = 20

private const val WINDOW_MS = 1_000L

/**
 * Notices the enforcement loop re-evaluating faster than any user action can explain.
 *
 * Observed on device across three days: `Interstitial.isShowing` flipping at roughly 30Hz, so the
 * `Decision.Allowed` branch alternated between opening and closing a ledger session about sixty
 * times a second, for up to 102 seconds at a stretch. It cost an INSERT and an UPDATE per cycle,
 * filled the ledger with 26ms sessions -- 88% of every row in the database came from 5.3 minutes
 * of this -- and, worst of all, destroyed the evidence: the [EnforcementStatus] trail holds 300
 * entries, the storm writes two per cycle, and the two alternating texts never collapse into a
 * repeat count. So a capture taken during a storm contains nothing but the storm, and the minutes
 * before it -- the part a capture exists to hold -- were already gone.
 *
 * Hence a detector rather than a fix. The trigger is still unknown and is state-dependent (it
 * started and stopped with no code change at either boundary), so the thing to do is catch it in
 * the act with the state that preceded it, which is what the audit event at [Verdict.Began]
 * carries. Recording once at onset and once at the end is the whole budget: a storm that logged
 * per evaluation would be the same mistake one layer down.
 *
 * Pure and Android-free, like [com.ezhou.rotblock.block.FrictionGate] and [LinkCorroboration], so
 * the interesting part -- which rate means a storm and how one is bracketed -- is testable off
 * device. The service owns the instance and the clock; main thread only.
 */
class EvaluationRate(private val thresholdPerSecond: Int = STORM_EVALUATIONS_PER_SECOND) {

    sealed interface Verdict {
        /** Nothing to report; the overwhelmingly common answer. */
        data object Quiet : Verdict

        /** The rate just crossed the threshold. [perSecond] is the count that tripped it. */
        data class Began(val perSecond: Int) : Verdict

        /** A storm that had begun is over. */
        data class Ended(val durationMs: Long, val evaluations: Int) : Verdict
    }

    /** Null until the first evaluation. Not 0L: that is a legitimate clock value. */
    private var windowStartMs: Long? = null
    private var inWindow = 0
    private var stormBeganAtMs: Long? = null
    private var stormEvaluations = 0

    /** Whether a storm is currently in progress, for diagnostics. */
    val storming: Boolean get() = stormBeganAtMs != null

    /**
     * Counts one evaluation and says whether that changed anything.
     *
     * The storm is declared the moment the count crosses inside a window rather than when the
     * window closes, and that is the point: at the observed rate a whole second of storming is
     * another 62 trail entries, so waiting for the window to close would spend a fifth of the
     * buffer that the onset event is being written to preserve.
     */
    fun evaluated(nowMs: Long): Verdict {
        // A clock that went backwards restarts the window *and* its count. Resetting only the
        // start would leave the tally intact while the window can never close, so an NTP
        // correction during ordinary use would accumulate its way to the threshold and report a
        // storm that never happened -- an instrument inventing its own finding.
        val started = windowStartMs
        val windowStart = if (started == null || nowMs < started) {
            inWindow = 0
            windowStartMs = nowMs
            nowMs
        } else {
            started
        }

        if (nowMs - windowStart >= WINDOW_MS) {
            // Only a *completed* window may end a storm. A window still filling has not yet had
            // its chance to cross the threshold, and ending on it would bracket every storm into
            // one-second fragments.
            val began = stormBeganAtMs
            if (began != null && inWindow < thresholdPerSecond) {
                val ended = Verdict.Ended(nowMs - began, stormEvaluations)
                stormBeganAtMs = null
                stormEvaluations = 0
                windowStartMs = nowMs
                inWindow = 1
                return ended
            }
            windowStartMs = nowMs
            inWindow = 0
        }

        inWindow++
        if (stormBeganAtMs != null) {
            stormEvaluations++
            return Verdict.Quiet
        }
        if (inWindow >= thresholdPerSecond) {
            stormBeganAtMs = nowMs
            stormEvaluations = inWindow
            return Verdict.Began(inWindow)
        }
        return Verdict.Quiet
    }

    /**
     * Closes off a storm that stopped because evaluation stopped altogether -- the target was
     * dropped, the screen went off, the service was torn down.
     *
     * Without it a storm ended by its target going away would leave a [Verdict.Began] in the audit
     * log with nothing bracketing it, and the duration -- the one number that says how bad it got
     * -- would never be written down.
     */
    fun settle(nowMs: Long): Verdict {
        val began = stormBeganAtMs ?: return Verdict.Quiet
        val ended = Verdict.Ended(nowMs - began, stormEvaluations)
        stormBeganAtMs = null
        stormEvaluations = 0
        windowStartMs = null
        inWindow = 0
        return ended
    }
}
