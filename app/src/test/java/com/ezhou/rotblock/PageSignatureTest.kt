package com.ezhou.rotblock

import com.ezhou.rotblock.browser.PageSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The third and weakest way of identifying a page, for screens where neither the address bar nor
 * [com.ezhou.rotblock.browser.LinkConsensus] can see anything at all -- Samsung Internet's Shorts
 * player is both: no toolbar in the tree, and too few link nodes to reach
 * [com.ezhou.rotblock.browser.LinkConsensus.MIN_SAMPLES].
 *
 * Every fixture below is copied from a real device capture rather than imagined, because the first
 * version of this signature was written from a guess about what a Shorts player looks like and got
 * it exactly backwards -- it fired on watch pages and never once matched a Short. The two YouTube
 * screens are the contrast that matters, so they are held side by side here.
 */
class PageSignatureTest {

    // capture-20260818-093854: the real Shorts player, 36 nodes. Note what is NOT here --
    // "Previous video". The signature used to require it.
    private val shortsDescriptions = listOf(
        "Next video",
        "More actions",
        "Go to channel @examplecreator",
        "@examplecreator",
        "Subscribe to @examplecreator.",
        "View 311 comments",
        "Share this video",
        "See more videos using this sound",
        "Back",
        "Search YouTube",
    )
    private val shortsTitle = "Example Short Title - YouTube"
    private val shortsLinks = listOf(
        "https://m.youtube.com/@examplecreator/shorts",
        "https://m.youtube.com/watch?v=AAAAAAAAAAA",
        "https://m.youtube.com/source/BBBBBBBBBBB/shorts?bp=8gVDCjYSJwoLQkJCQkJCQkJCQkI",
    )

    // capture-20260818-094446: a normal watch page, 93 nodes. The capture held twenty-five links
    // and not one was a /shorts path; a representative half of them is kept here. With the
    // controls raised it also grows "Previous video" and "Next video", which is precisely how it
    // used to be misread as a Short.
    private val watchPageDescriptions = listOf(
        "Show player controls",
        "YouTube Premium Home",
        "Search YouTube",
        "Subscribe to ChannelTwo.",
        "Share",
        "Comments",
        "Go to channel ChannelThree",
        "Action menu",
    )
    private val watchPageTitle =
        "an example long-form video title used by the test - YouTube"
    private val watchPageLinks = listOf(
        "https://m.youtube.com/@channeltwo",
        "https://m.youtube.com/@ChannelThree",
        "https://m.youtube.com/watch?v=CCCCCCCCCCC",
        "https://m.youtube.com/@channelfour",
        "https://m.youtube.com/watch?v=DDDDDDDDDDD&t=1962s",
        "https://m.youtube.com/@channelfive",
        "https://m.youtube.com/watch?v=EEEEEEEEEEE",
        "https://m.youtube.com/@channelsix",
        "https://m.youtube.com/watch?v=FFFFFFFFFFF",
        "https://m.youtube.com/@channelseven",
        "https://m.youtube.com/watch?v=GGGGGGGGGGG",
        "https://m.youtube.com/@channeleight",
    )

    @Test
    fun `the real shorts player resolves to youtube-com slash shorts`() {
        val result = PageSignature.of(shortsDescriptions, shortsTitle, shortsLinks)
        assertEquals("youtube.com", result?.host)
        assertEquals("/shorts", result?.path)
    }

    @Test
    fun `the real watch page does not match`() {
        assertNull(PageSignature.of(watchPageDescriptions, watchPageTitle, watchPageLinks))
    }

    @Test
    fun `a watch page with its controls up still does not match`() {
        // The reported bug, and the one this signature exists to get right: tapping the video
        // raises Previous/Next-video controls, which is the whole of what the old signature keyed
        // on. Its links are unchanged by the tap and none of them is a Short.
        val tapped = watchPageDescriptions + listOf("Previous video", "Next video")
        assertNull(PageSignature.of(tapped, watchPageTitle, watchPageLinks))
    }

    @Test
    fun `shorts labels without shorts links match nothing`() {
        // Half the evidence is not evidence: the labels say what kind of screen this is, the links
        // say which part of the site it belongs to, and a verdict needs both.
        assertNull(PageSignature.of(shortsDescriptions, shortsTitle, watchPageLinks))
    }

    @Test
    fun `shorts links without the player labels match nothing`() {
        // A feed scrolled to a Shorts shelf: the links are Shorts, the screen is not a player.
        assertNull(PageSignature.of(watchPageDescriptions, watchPageTitle, shortsLinks))
    }

    @Test
    fun `one shorts link among many cannot carry the vote`() {
        // A watch page may show a Shorts shelf. A share, not a bare "at least one", is what stops
        // a single shelf link from calling the page a Short.
        val mostlyWatch = watchPageLinks + "https://m.youtube.com/shorts/abc123"
        assertNull(PageSignature.of(shortsDescriptions, shortsTitle, mostlyWatch))
    }

    @Test
    fun `too few of the page's own links is no verdict at all`() {
        assertNull(
            PageSignature.of(shortsDescriptions, shortsTitle, listOf("https://m.youtube.com/shorts/a")),
        )
    }

    @Test
    fun `links to other sites neither dilute nor inflate the share`() {
        val withOutbound = shortsLinks + listOf(
            "https://reddit.com/r/whatever",
            "https://example.com/shorts",
        )
        assertEquals("youtube.com", PageSignature.of(shortsDescriptions, shortsTitle, withOutbound)?.host)
    }

    @Test
    fun `no site marker matches nothing`() {
        // Some other app could plausibly have a "Next video" control; without a YouTube marker
        // this must not claim the page is Shorts.
        val descriptions = listOf("Next video", "Back")
        assertNull(PageSignature.of(descriptions, title = "A completely unrelated app", linkUrls = shortsLinks))
        assertNull(PageSignature.of(descriptions, title = null, linkUrls = shortsLinks))
    }

    @Test
    fun `the title alone confirms the site when Search YouTube is absent`() {
        val descriptions = listOf("Next video", "Back")
        assertEquals(
            "youtube.com",
            PageSignature.of(descriptions, shortsTitle, shortsLinks)?.host,
        )
    }

    @Test
    fun `a title that does not end with the marker is not a confirmation`() {
        val descriptions = listOf("Next video", "Back")
        assertNull(
            PageSignature.of(descriptions, title = "YouTube - some unrelated caption", linkUrls = shortsLinks),
        )
    }

    @Test
    fun `matching is exact, not a substring`() {
        // "Next video in queue" contains "next video" but is not equal to it -- a wording change
        // must degrade to "not blocked", never a false match on a near-miss.
        val descriptions = listOf("Next video in queue", "Search YouTube")
        assertNull(PageSignature.of(descriptions, title = null, linkUrls = shortsLinks))
    }

    @Test
    fun `matching is case and whitespace insensitive`() {
        val descriptions = listOf("  NEXT VIDEO  ", "search youtube")
        assertEquals(
            "youtube.com",
            PageSignature.of(descriptions, title = null, linkUrls = shortsLinks)?.host,
        )
    }

    @Test
    fun `host filtering allows youtube-com and its subdomains`() {
        assertEquals(
            "youtube.com",
            PageSignature.of(shortsDescriptions, shortsTitle, shortsLinks, compatibleWith = "youtube.com")?.host,
        )
        assertEquals(
            "youtube.com",
            PageSignature.of(shortsDescriptions, shortsTitle, shortsLinks, compatibleWith = "m.youtube.com")?.host,
        )
    }

    @Test
    fun `host filtering rejects an unrelated or merely similar host`() {
        assertNull(
            PageSignature.of(shortsDescriptions, shortsTitle, shortsLinks, compatibleWith = "reddit.com"),
        )
        // Not a subdomain -- "notyoutube.com" must not pass a suffix check that is too loose.
        assertNull(
            PageSignature.of(shortsDescriptions, shortsTitle, shortsLinks, compatibleWith = "notyoutube.com"),
        )
    }

    @Test
    fun `couldRefine agrees with the host filter`() {
        assertTrue(PageSignature.couldRefine("youtube.com"))
        assertTrue(PageSignature.couldRefine("m.youtube.com"))
        assertFalse(PageSignature.couldRefine("reddit.com"))
        assertFalse(PageSignature.couldRefine("notyoutube.com"))
    }

    @Test
    fun `keyOf reports the signature that matched regardless of any rule`() {
        assertEquals(
            "youtube_shorts_web",
            PageSignature.keyOf(shortsDescriptions, shortsTitle, shortsLinks),
        )
        assertNull(PageSignature.keyOf(watchPageDescriptions, watchPageTitle, watchPageLinks))
    }
}
