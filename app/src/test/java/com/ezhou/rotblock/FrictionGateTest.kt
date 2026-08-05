package com.ezhou.rotblock

import com.ezhou.rotblock.block.FrictionGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the breathing countdown is owed, and when it has been paid.
 *
 * The case that bit on device: press back on the countdown and the app underneath resumes with the
 * wait recorded as done. Nothing fires to say so -- our own package is excluded from foreground
 * tracking -- so there was no second chance. Everything here is about the difference between a
 * countdown having been *put up* and one having been *sat through*.
 */
class FrictionGateTest {

    private val target = "web:reddit.com"

    @Test
    fun `the countdown is owed on arrival`() {
        assertTrue(FrictionGate().shouldLaunch(target, interstitialShowing = false, nowMs = 0))
    }

    @Test
    fun `a countdown on screen is not launched again`() {
        val gate = FrictionGate()
        gate.launched(target, 0)
        gate.appeared(target)
        assertFalse(gate.shouldLaunch(target, interstitialShowing = true, nowMs = 100))
    }

    @Test
    fun `a launch in flight is not launched again`() {
        // A busy page evaluates several times a second; the activity's onStart has not run yet.
        val gate = FrictionGate()
        gate.launched(target, 0)
        assertFalse(gate.shouldLaunch(target, interstitialShowing = false, nowMs = 50))
    }

    @Test
    fun `backing out of the countdown owes it again`() {
        val gate = FrictionGate()
        gate.launched(target, 0)
        gate.appeared(target)
        // Back pressed: the screen is gone and the wait was never served.
        assertTrue(gate.shouldLaunch(target, interstitialShowing = false, nowMs = 2_000))
    }

    @Test
    fun `backing out repeatedly never buys the visit`() {
        val gate = FrictionGate()
        var now = 0L
        repeat(20) {
            assertTrue(gate.shouldLaunch(target, interstitialShowing = false, nowMs = now))
            gate.launched(target, now)
            gate.appeared(target)
            now += 2_000
        }
        assertTrue(gate.shouldLaunch(target, interstitialShowing = false, nowMs = now))
    }

    @Test
    fun `serving the wait spends it for the visit`() {
        val gate = FrictionGate()
        gate.launched(target, 0)
        gate.appeared(target)
        gate.served(target)
        assertFalse(gate.shouldLaunch(target, interstitialShowing = false, nowMs = 10_000))
    }

    @Test
    fun `waiting it out then backing out still counts as waiting`() {
        val gate = FrictionGate()
        gate.launched(target, 0)
        gate.appeared(target)
        gate.served(target) // countdown reached zero
        // Back instead of "Go ahead": the gap between the reflex and the feed was spent either way.
        assertFalse(gate.shouldLaunch(target, interstitialShowing = false, nowMs = 6_000))
    }

    @Test
    fun `a launch that never appears gives up rather than looping`() {
        val gate = FrictionGate()
        gate.launched(target, 0)
        assertFalse(gate.shouldLaunch(target, interstitialShowing = false, nowMs = 4_999))
        // Past the grace with nothing on screen: the launch is going nowhere, and retrying it on
        // every redraw of a busy page would be worse than skipping one countdown.
        assertFalse(gate.shouldLaunch(target, interstitialShowing = false, nowMs = 5_000))
        assertFalse(gate.shouldLaunch(target, interstitialShowing = false, nowMs = 60_000))
    }

    @Test
    fun `a new visit earns the countdown again`() {
        val gate = FrictionGate()
        gate.launched(target, 0)
        gate.appeared(target)
        gate.served(target)
        gate.endVisit()
        assertNull(gate.servedFor)
        assertTrue(gate.shouldLaunch(target, interstitialShowing = false, nowMs = 10_000))
    }

    @Test
    fun `a new visit un-breaks a launch that had given up`() {
        val gate = FrictionGate()
        gate.launched(target, 0)
        gate.shouldLaunch(target, interstitialShowing = false, nowMs = 10_000) // marks it broken
        gate.endVisit()
        assertTrue(gate.shouldLaunch(target, interstitialShowing = false, nowMs = 11_000))
    }

    @Test
    fun `a different target is owed its own countdown`() {
        val gate = FrictionGate()
        gate.launched(target, 0)
        gate.appeared(target)
        gate.served(target)
        assertTrue(gate.shouldLaunch("app:com.instagram.android", false, nowMs = 1_000))
    }

    @Test
    fun `a rebind restores the served mark but not a launch in flight`() {
        // Losing the launch resolves toward showing the countdown again, which is the safe way to
        // be wrong about it.
        val restored = FrictionGate()
        restored.restoreServed(target)
        assertFalse(restored.shouldLaunch(target, interstitialShowing = false, nowMs = 0))

        val fresh = FrictionGate()
        fresh.restoreServed(null)
        assertTrue(fresh.shouldLaunch(target, interstitialShowing = false, nowMs = 0))
    }

    @Test
    fun `the pause flow does not get a countdown stacked on it`() {
        val gate = FrictionGate()
        assertFalse(gate.shouldLaunch(target, interstitialShowing = true, nowMs = 0))
    }
}
