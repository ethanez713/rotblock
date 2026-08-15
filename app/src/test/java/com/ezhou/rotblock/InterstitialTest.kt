package com.ezhou.rotblock

import com.ezhou.rotblock.block.Interstitial
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * When the gate that covers a rotblock screen goes up and comes down.
 *
 * [Interstitial] is a singleton shared by every activity that owns one of our screens, so its
 * state carries between tests unless reset -- there is no production reset, since nothing but a
 * test ever needs one.
 */
class InterstitialTest {

    private val t0 = 1_700_000_000_000L

    @Before
    fun resetState() {
        Interstitial.onShown = null
        Interstitial.onCleared = null
        repeat(5) { Interstitial.exited() }
    }

    @After
    fun clearCallbacks() {
        Interstitial.onShown = null
        Interstitial.onCleared = null
    }

    @Test
    fun `onShown fires on the first entry and not on a nested one`() {
        var shownCount = 0
        Interstitial.onShown = { shownCount++ }
        Interstitial.entered(t0)
        assertEquals(1, shownCount)
        // Friction handing off to the pause flow -- one continuous cover, not a fresh entry.
        Interstitial.entered(t0 + 100)
        assertEquals(1, shownCount)
    }

    @Test
    fun `onCleared fires only when the last exit lands`() {
        var clearedCount = 0
        Interstitial.onCleared = { clearedCount++ }
        Interstitial.entered(t0)
        Interstitial.entered(t0 + 100)
        Interstitial.exited()
        assertEquals(0, clearedCount)
        Interstitial.exited()
        assertEquals(1, clearedCount)
    }

    @Test
    fun `a gate left shut past MAX_SHOWING_MS self-heals and can be re-entered`() {
        var shownCount = 0
        Interstitial.onShown = { shownCount++ }
        Interstitial.entered(t0)
        assertEquals(1, shownCount)
        assertTrue(Interstitial.isShowing(t0 + 1_000))

        val pastHealing = t0 + 15 * 60 * 1_000L + 1
        assertFalse(Interstitial.isShowing(pastHealing))

        // A fresh entry after the self-heal must fire onShown again -- the lost exited() must not
        // permanently block the gate from ever reporting shown again.
        Interstitial.entered(pastHealing)
        assertEquals(2, shownCount)
        assertTrue(Interstitial.isShowing(pastHealing))
    }
}
