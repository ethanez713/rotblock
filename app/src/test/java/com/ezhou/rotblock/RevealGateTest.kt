package com.ezhou.rotblock

import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.browser.shouldAttemptReveal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate on tapping a browser's toolbar-reveal control to bring a scrolled-away address bar
 * back. This is a real click on the user's screen rather than a read, so every condition here
 * narrows toward "there is nothing left to lose" -- each one is tested failing on its own, with
 * every other condition satisfied, so a change that quietly loosens one shows up as a single
 * broken test rather than a gap nobody notices.
 */
class RevealGateTest {

    private fun allPass(
        enabled: Boolean = true,
        revealId: String? = "reveal_id",
        url: String? = null,
        chooser: Boolean = false,
        heldTarget: String? = null,
        matcherCount: Int = 1,
        lastAttemptMs: Long = 0L,
        attemptedThisVisit: Boolean = false,
        nowMs: Long = BrowserRegistry.REVEAL_COOLDOWN_MS,
    ) = shouldAttemptReveal(
        enabled = enabled,
        revealId = revealId,
        url = url,
        chooser = chooser,
        heldTarget = heldTarget,
        matcherCount = matcherCount,
        lastAttemptMs = lastAttemptMs,
        attemptedThisVisit = attemptedThisVisit,
        nowMs = nowMs,
    )

    @Test
    fun `fires when every condition holds`() {
        assertTrue(allPass())
    }

    @Test
    fun `setting off suppresses it`() {
        assertFalse(allPass(enabled = false))
    }

    @Test
    fun `no reveal id for this browser suppresses it`() {
        assertFalse(allPass(revealId = null))
    }

    @Test
    fun `a bar that already read a url suppresses it`() {
        assertFalse(allPass(url = "https://reddit.com"))
    }

    @Test
    fun `a chooser screen suppresses it`() {
        assertFalse(allPass(chooser = true))
    }

    @Test
    fun `a target already held suppresses it`() {
        // This acquires; it must never contradict a target already in force.
        assertFalse(allPass(heldTarget = "reddit.com"))
    }

    @Test
    fun `no website rule to acquire toward suppresses it`() {
        assertFalse(allPass(matcherCount = 0))
    }

    @Test
    fun `already tried once this visit suppresses it`() {
        assertFalse(allPass(attemptedThisVisit = true))
    }

    @Test
    fun `inside the cooldown suppresses it`() {
        assertFalse(allPass(lastAttemptMs = 0L, nowMs = BrowserRegistry.REVEAL_COOLDOWN_MS - 1))
    }

    @Test
    fun `exactly at the cooldown boundary it fires`() {
        assertTrue(allPass(lastAttemptMs = 0L, nowMs = BrowserRegistry.REVEAL_COOLDOWN_MS))
    }
}
