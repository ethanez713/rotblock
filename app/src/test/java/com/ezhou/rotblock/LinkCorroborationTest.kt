package com.ezhou.rotblock

import com.ezhou.rotblock.browser.LinkCorroboration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Requiring two consecutive, agreeing link-consensus scans before a target may be acquired from
 * them -- one scan is an inference from a tree that may be mid-load, and two about three seconds
 * apart landing on the same host is the confidence an acquisition-only route needs.
 */
class LinkCorroborationTest {

    private val t0 = 1_700_000_000_000L
    private val pkg = "com.sec.android.app.sbrowser"

    @Test
    fun `a first offer is never corroborated`() {
        val corroboration = LinkCorroboration()
        assertNull(corroboration.offer(pkg, "reddit.com", t0))
    }

    @Test
    fun `a second matching offer within the window is corroborated`() {
        val corroboration = LinkCorroboration()
        corroboration.offer(pkg, "reddit.com", t0)
        assertEquals("reddit.com", corroboration.offer(pkg, "reddit.com", t0 + 3_000))
    }

    @Test
    fun `a differing offer replaces what was pending rather than being ignored`() {
        val corroboration = LinkCorroboration()
        corroboration.offer(pkg, "reddit.com", t0)
        // Disagrees with the pending host: not corroborated, and the state moves on to this offer
        // rather than clinging to the stale first one.
        assertNull(corroboration.offer(pkg, "wikipedia.org", t0 + 3_000))
        assertEquals("wikipedia.org", corroboration.offer(pkg, "wikipedia.org", t0 + 6_000))
    }

    @Test
    fun `an intervening null offer resets rather than being ignored`() {
        val corroboration = LinkCorroboration()
        corroboration.offer(pkg, "reddit.com", t0)
        assertNull(corroboration.offer(pkg, null, t0 + 3_000))
        // Agreement has to be consecutive: the reddit read from before the null must not be allowed
        // to corroborate a reddit read that comes after it.
        assertNull(corroboration.offer(pkg, "reddit.com", t0 + 6_000))
        assertEquals("reddit.com", corroboration.offer(pkg, "reddit.com", t0 + 9_000))
    }

    @Test
    fun `an offer past freshMs starts over`() {
        val corroboration = LinkCorroboration(freshMs = 30_000L)
        corroboration.offer(pkg, "reddit.com", t0)
        assertNull(corroboration.offer(pkg, "reddit.com", t0 + 31_000))
        assertEquals("reddit.com", corroboration.offer(pkg, "reddit.com", t0 + 34_000))
    }

    @Test
    fun `packages are tracked separately`() {
        val corroboration = LinkCorroboration()
        corroboration.offer(pkg, "reddit.com", t0)
        assertNull(corroboration.offer("com.android.chrome", "reddit.com", t0 + 1_000))
    }
}
