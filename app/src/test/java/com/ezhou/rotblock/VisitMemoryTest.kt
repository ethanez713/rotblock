package com.ezhou.rotblock

import com.ezhou.rotblock.service.SearchPageMemory
import com.ezhou.rotblock.service.VisitMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re-entering an app that has to be read to be understood.
 *
 * The bug this exists for had no symptom: leave a browser sitting on a blocked site, go anywhere
 * else, come back, and the target was gone. The app switch cleared it, and the read that should
 * have re-established it could not see a thing, because the page was scrolled and the address bar
 * was hidden. Reddit then sat on screen filling no quota and heading for no block, silently.
 */
class VisitMemoryTest {

    private val sbrowser = "com.sec.android.app.sbrowser"

    @Test
    fun `a target established by a read comes back on re-entry`() {
        val memory = VisitMemory()
        memory.remember(sbrowser, "web:reddit.com")
        assertEquals("web:reddit.com", memory.resume(sbrowser))
    }

    @Test
    fun `an app never read holds nothing`() {
        // The memory may only ever repeat a read, never invent one -- otherwise a browser you have
        // never opened a blocked site in could arrive already blocked.
        assertNull(VisitMemory().resume(sbrowser))
    }

    @Test
    fun `a read that finds nothing covered clears it`() {
        val memory = VisitMemory()
        memory.remember(sbrowser, "web:reddit.com")
        memory.forget(sbrowser)
        assertNull(memory.resume(sbrowser))
    }

    @Test
    fun `the newest read wins`() {
        val memory = VisitMemory()
        memory.remember(sbrowser, "web:reddit.com")
        memory.remember(sbrowser, "web:youtube.com")
        assertEquals("web:youtube.com", memory.resume(sbrowser))
    }

    @Test
    fun `apps are remembered separately`() {
        val memory = VisitMemory()
        memory.remember(sbrowser, "web:reddit.com")
        memory.remember("com.google.android.youtube", "feed:youtube_shorts")
        assertEquals("web:reddit.com", memory.resume(sbrowser))
        assertEquals("feed:youtube_shorts", memory.resume("com.google.android.youtube"))
    }

    @Test
    fun `it stays bounded without losing the app being used`() {
        val memory = VisitMemory()
        repeat(200) { memory.remember("pkg$it", "web:site$it.com") }
        memory.remember(sbrowser, "web:reddit.com")
        assertEquals("web:reddit.com", memory.resume(sbrowser))
    }
}

/**
 * When a bar read switches the link fallback off.
 *
 * A results page is the one page whose links are guaranteed to be about somewhere else, so once the
 * address bar has positively said "this is Google", the links must not get a vote for as long as
 * that answer is plausibly still true. The failure it prevents is a block landing on a search for
 * reddit; the failure it must not cause is a browser that stops acquiring anything, which is why
 * both a different page and enough time clear it.
 */
class SearchPageMemoryTest {

    private val sbrowser = "com.sec.android.app.sbrowser"
    private val t0 = 1_700_000_000_000L

    @Test
    fun `a bar read on a search engine suppresses the links`() {
        val memory = SearchPageMemory()
        memory.noteReadHost(sbrowser, "google.com", t0)
        assertTrue(memory.isOnSearchPage(sbrowser, t0 + 1_000))
    }

    @Test
    fun `an ordinary page never suppresses anything`() {
        val memory = SearchPageMemory()
        memory.noteReadHost(sbrowser, "reddit.com", t0)
        assertFalse(memory.isOnSearchPage(sbrowser, t0 + 1_000))
    }

    @Test
    fun `reading any other page clears it at once`() {
        // Tapping a result navigates, and browsers show the toolbar on navigation -- so the read
        // that lands a second later is the one that has to hand the fallback back.
        val memory = SearchPageMemory()
        memory.noteReadHost(sbrowser, "google.com", t0)
        memory.noteReadHost(sbrowser, "reddit.com", t0 + 500)
        assertFalse(memory.isOnSearchPage(sbrowser, t0 + 1_000))
    }

    @Test
    fun `it expires so a visit to google cannot blind the browser`() {
        // Suppression is switching off the mechanism that covers an unreadable bar. Held forever,
        // one search would mean nothing is ever acquired again, which is a fail-open.
        val memory = SearchPageMemory()
        memory.noteReadHost(sbrowser, "google.com", t0)
        assertTrue(memory.isOnSearchPage(sbrowser, t0 + 119_000))
        assertFalse(memory.isOnSearchPage(sbrowser, t0 + 121_000))
    }

    @Test
    fun `browsers are tracked separately`() {
        val memory = SearchPageMemory()
        memory.noteReadHost(sbrowser, "bing.com", t0)
        assertFalse(memory.isOnSearchPage("com.android.chrome", t0))
    }
}
