package com.ezhou.rotblock

import com.ezhou.rotblock.service.TargetConfidence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a held target is a fact a read established or still a guess a restore made.
 *
 * The rule that has to survive here: confirmation only ever rises on a read, never falls on a bare
 * re-evaluation of the same target. Getting that wrong either nags on every restored visit (never
 * confirming sticks) or, worse, suppresses the countdown for the rest of a visit that a read never
 * actually confirmed (a stray downgrade).
 */
class TargetConfidenceTest {

    @Test
    fun `a target change starts unconfirmed when its source is a guess`() {
        val confidence = TargetConfidence()
        confidence.apply(changed = true, confirmedNow = false)
        assertFalse(confidence.confirmed)
    }

    @Test
    fun `a target change starts confirmed when its source is a read`() {
        val confidence = TargetConfidence()
        confidence.apply(changed = true, confirmedNow = true)
        assertTrue(confidence.confirmed)
    }

    @Test
    fun `a read on the still-current target confirms it`() {
        val confidence = TargetConfidence()
        confidence.apply(changed = true, confirmedNow = false)
        confidence.apply(changed = false, confirmedNow = true)
        assertTrue(confidence.confirmed)
    }

    @Test
    fun `a later guess about an already-confirmed target does not downgrade it`() {
        val confidence = TargetConfidence()
        confidence.apply(changed = true, confirmedNow = true)
        confidence.apply(changed = false, confirmedNow = false)
        assertTrue(confidence.confirmed)
    }

    @Test
    fun `the policy going away clears confirmation`() {
        val confidence = TargetConfidence()
        confidence.apply(changed = true, confirmedNow = true)
        confidence.clear()
        assertFalse(confidence.confirmed)
    }

    @Test
    fun `a fresh instance starts unconfirmed`() {
        assertFalse(TargetConfidence().confirmed)
    }
}
