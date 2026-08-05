package com.ezhou.rotblock

import com.ezhou.rotblock.browser.AddressBar
import com.ezhou.rotblock.browser.AddressBar.Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which node under an address-bar view id is the address bar.
 *
 * The case that bit on device: Samsung Internet's tab switcher labels every card with that tab's
 * URL, under the same view id as the bar itself. Taking the first node with text blocked the tab
 * list because one of the *other* tabs was on reddit -- a screen with no page on it at all.
 */
class AddressBarTest {

    @Test
    fun `a lone bar reads as itself`() {
        assertEquals(
            AddressBar.Read.Text("reddit.com"),
            AddressBar.read(listOf(Candidate("reddit.com", visible = true))),
        )
    }

    @Test
    fun `a bar scrolled off screen is still a read`() {
        // Chrome keeps the toolbar in the tree as it slides away, holding the current page's URL.
        // Filtering on visibility would drop a good read, which loses a target rather than gaining
        // one -- the direction nothing here may fail in.
        assertEquals(
            AddressBar.Read.Text("reddit.com"),
            AddressBar.read(listOf(Candidate("reddit.com", visible = false))),
        )
    }

    @Test
    fun `nothing with text is absent, not a chooser`() {
        assertEquals(AddressBar.Read.Absent, AddressBar.read(emptyList()))
        assertEquals(
            AddressBar.Read.Absent,
            AddressBar.read(listOf(Candidate(null, visible = true), Candidate("  ", visible = true))),
        )
    }

    @Test
    fun `a tab list is a chooser, not the first tab in it`() {
        val tabs = listOf(
            Candidate("wikipedia.org", visible = true),
            Candidate("reddit.com/r/all", visible = true),
            Candidate("news.ycombinator.com", visible = true),
        )
        assertEquals(AddressBar.Read.Chooser(3), AddressBar.read(tabs))
    }

    @Test
    fun `tabs all on the same site are still a chooser`() {
        // Agreement is not evidence here. Three reddit tabs on screen say what is open, not what
        // is being read, and the screen they are on is the tab switcher either way.
        val tabs = List(3) { Candidate("reddit.com", visible = true) }
        assertEquals(AddressBar.Read.Chooser(3), AddressBar.read(tabs))
    }

    @Test
    fun `a suggestion drop-down over the bar is a chooser`() {
        // Typing into the bar puts the typed text and every suggested URL on screen together. The
        // typed text is a real read, but it is indistinguishable from the rows around it, and the
        // rows are what autocomplete would have blocked on before it was even a page.
        val screen = listOf(
            Candidate("redd", visible = true),
            Candidate("reddit.com", visible = true),
            Candidate("reddit.com/r/all", visible = true),
        )
        assertEquals(AddressBar.Read.Chooser(3), AddressBar.read(screen))
    }

    @Test
    fun `one visible bar above hidden duplicates still reads`() {
        // Browsers keep an off-screen copy of the bar for the editing state. One thing on screen is
        // not a choice of URLs, so this must not be given up on.
        assertEquals(
            AddressBar.Read.Text("reddit.com"),
            AddressBar.read(
                listOf(
                    Candidate("about:blank", visible = false),
                    Candidate("reddit.com", visible = true),
                ),
            ),
        )
    }
}
