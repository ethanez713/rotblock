package com.ezhou.rotblock.service

/**
 * Whether the target currently held is a fact a read established, or still an assumption a
 * restore made.
 *
 * A website or feed target reaches [com.ezhou.rotblock.service.RotblockAccessibilityService]'s
 * `applyTarget` from places that differ in exactly this way: a real node-tree read is evidence, but
 * [VisitMemory.resume] and the persisted visit restored across a rebind are both guesses about what
 * is still on screen. The asymmetry that matters is that a guess may still be *enforced* -- the
 * worst case is a block the next read lifts a second later, and losing that trade is the safe
 * direction everywhere else in this app -- but it must not drive the friction countdown. A
 * countdown for a page you are not on is a false positive that trains distrust in the whole
 * mechanism, and unlike a block it costs the wait before you can prove it wrong.
 *
 * So confirmation only ever goes up on a read, never down on a bare re-evaluation of the same
 * target: a `false` from a later assumption about an already-confirmed target must not un-confirm
 * it, or the countdown would be suppressed for the rest of the visit. Only a target change or the
 * policy going away resets it.
 */
class TargetConfidence {

    var confirmed: Boolean = false
        private set

    /** The held target's policy is gone, or there never was one. */
    fun clear() {
        confirmed = false
    }

    /**
     * The target just became -- or continues to be -- the one enforced.
     *
     * @param changed whether this call is the one that made the target current, as opposed to a
     *   re-evaluation of a target already held.
     * @param confirmedNow whether *this* call's source is a read, independent of [changed].
     */
    fun apply(changed: Boolean, confirmedNow: Boolean) {
        confirmed = if (changed) confirmedNow else confirmed || confirmedNow
    }
}
