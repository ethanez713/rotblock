package com.ezhou.rotblock

import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.browser.LinkConsensus
import com.ezhou.rotblock.browser.SearchEngines
import com.ezhou.rotblock.browser.WebMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page's own links as a second opinion on where you are.
 *
 * Every case here is about the one risk this signal carries that the address bar does not: it is an
 * inference, and an inference that goes wrong puts a block on a page no rule covers. A false block
 * is worse than a miss, because it is what trains someone to switch the whole app off -- so
 * anything short of near-unanimity has to answer "I don't know".
 */
class LinkConsensusTest {

    private fun reddit(n: Int) = List(n) { "https://www.reddit.com/r/movies/comments/$it/" }

    @Test
    fun `a page whose links all point at itself resolves to that host`() {
        val consensus = LinkConsensus.of(reddit(15), null)
        assertEquals("reddit.com", consensus?.host)
    }

    @Test
    fun `the host is reported without a path`() {
        // Chromium resolves a same-page fragment into the full current URL, so a path is nearly
        // available -- but a fragment link to another page on the same host looks identical, and
        // guessing wrong would block a page no rule covers. Path rules miss instead.
        val consensus = LinkConsensus.of(reddit(15), null)
        assertEquals("", consensus?.path)
    }

    @Test
    fun `too few links is not evidence`() {
        // A handful of nav links on an unrelated page must not carry the vote.
        for (n in 0 until LinkConsensus.MIN_SAMPLES) {
            assertNull("$n links should not be enough", LinkConsensus.of(reddit(n), null))
        }
        assertEquals("reddit.com", LinkConsensus.of(reddit(LinkConsensus.MIN_SAMPLES), null)?.host)
    }

    @Test
    fun `a split verdict is no verdict`() {
        // An article linking heavily to one other site is exactly the shape that would produce a
        // false block, so a simple plurality must not be enough.
        val mixed = reddit(6) + List(6) { "https://example.org/page$it" }
        assertNull(LinkConsensus.of(mixed, null))
    }

    @Test
    fun `a clear majority short of the threshold still answers nothing`() {
        // 11 of 13 is a strong plurality and still not enough.
        val mostly = reddit(11) + List(2) { "https://example.org/page$it" }
        assertNull(LinkConsensus.of(mostly, null))
    }

    @Test
    fun `a few outbound links do not break a real page`() {
        // The realistic case: a reddit thread linking out to a couple of articles.
        val realistic = reddit(18) + listOf("https://example.org/a", "https://imgur.com/b")
        assertEquals("reddit.com", LinkConsensus.of(realistic, null)?.host)
    }

    @Test
    fun `junk and relative hrefs are ignored rather than counted`() {
        // Anything that does not parse as a URL must not dilute the sample, or a page full of
        // "#", "javascript:void(0)" and "/relative" links would never reach agreement.
        val noisy = reddit(12) + listOf("#", "javascript:void(0)", "/r/all", "", "   ")
        assertEquals("reddit.com", LinkConsensus.of(noisy, null)?.host)
    }

    @Test
    fun `an all-junk sample resolves to nothing`() {
        assertNull(LinkConsensus.of(listOf("#", "javascript:void(0)", "/r/all", "mailto:a@b.c", ""), null))
    }

    @Test
    fun `hosts are normalised the same way address bar text is`() {
        // www is stripped and case is folded, so these are one host and not three.
        val mixedForms = listOf(
            "https://www.reddit.com/a",
            "http://Reddit.com/b",
            "https://REDDIT.com/c",
            "https://www.reddit.com/d",
            "https://reddit.com/e",
            "https://WWW.Reddit.com/f",
            "http://www.reddit.com/g",
            "https://reddit.com/h",
            "https://www.reddit.com/i",
            "http://Reddit.com/j",
            "https://REDDIT.com/k",
            "https://reddit.com/l",
        )
        assertEquals("reddit.com", LinkConsensus.of(mixedForms, null)?.host)
    }

    @Test
    fun `a results page full of links to one site is not that site`() {
        // The case this was getting wrong on a phone: search for "reddit", scroll so the address
        // bar goes away, and every link on screen agrees on reddit.com. The consensus is unanimous
        // and the page is Google. Nothing in the links can tell you that, so the engine is named.
        val results = reddit(14) + listOf("https://www.google.com/preferences")
        assertNull(LinkConsensus.of(results, null))
    }

    @Test
    fun `a widened sample still lets the search engine's own nav link veto the vote`() {
        // Before the retained-link cap tracked the walk's actual node budget rather than bounding
        // it, a truncated sample could hold a dozen results and end right where the page's own
        // search-engine navigation was about to appear. Nothing here is truncated, so a veto link
        // arriving after what used to be the cutoff still discards the vote.
        val widened = reddit(13) + "https://www.google.com/preferences"
        assertNull(LinkConsensus.of(widened, null))
    }

    @Test
    fun `one search engine link anywhere discards the vote`() {
        // Any, not the winner: a results page's own links are the minority on it by design, so a
        // test on the majority host would never fire.
        for (engine in listOf(
            "https://www.google.co.uk/imghp",
            "https://news.google.com/topics/x",
            "https://duckduckgo.com/?q=reddit",
            "https://search.brave.com/search?q=reddit",
            "https://www.bing.com/images",
        )) {
            assertNull(engine, LinkConsensus.of(reddit(12) + engine, null))
        }
    }

    @Test
    fun `an engine's own results page is not identified as the engine either`() {
        // Consistent with everything else here: this route may say where you are, never where you
        // are not, and a rule on a search engine has to wait for an address bar it can read.
        val serp = List(15) { "https://www.google.com/search?q=$it" }
        assertNull(LinkConsensus.of(serp, null))
    }

    @Test
    fun `a site whose name merely contains an engine's is unaffected`() {
        // Brand matching is per label, so googleblog.com is not google.com. Over-matching here
        // would only ever cost an acquisition, but there is no reason to spend one.
        val links = List(15) { "https://notgoogle.example.com/page$it" }
        assertEquals("notgoogle.example.com", LinkConsensus.of(links, null)?.host)
    }

    @Test
    fun `a subdomain is reported as itself so rule matching decides`() {
        // Left for WebMatcher, which already treats a subdomain as covered by its parent rule.
        val old = List(15) { "https://old.reddit.com/r/movies/$it" }
        assertEquals("old.reddit.com", LinkConsensus.of(old, null)?.host)
    }

    @Test
    fun `a search-branded title discards the vote even with no search-engine host in the links`() {
        // The actual capture that motivated this: a Google results page whose retained links were
        // exclusively youtube.com, reddit.com and fandom.com --
        // never a google.* host, so isSearchHost had nothing to veto on. The title is what was left.
        val results = List(18) { "https://www.reddit.com/r/television/comments/$it/" } +
            listOf(
                "https://www.youtube.com/watch?v=1",
                "https://magnets.fandom.com/wiki/Neodymium",
            )
        assertNull(LinkConsensus.of(results, "how do magnets work - Google Search"))
    }

    @Test
    fun `the same sample without a title still resolves from the links alone`() {
        // Proves the veto above is doing the work, and nothing else about link tallying changed.
        val results = List(18) { "https://www.reddit.com/r/television/comments/$it/" } +
            listOf(
                "https://www.youtube.com/watch?v=1",
                "https://magnets.fandom.com/wiki/Neodymium",
            )
        assertEquals("reddit.com", LinkConsensus.of(results, null)?.host)
    }

    @Test
    fun `a genuine reddit title is not vetoed`() {
        val title = "Someone remind me - how do magnets actually work? : r/television"
        assertEquals("reddit.com", LinkConsensus.of(reddit(15), title)?.host)
    }

    @Test
    fun `a title with no separator is never vetoed`() {
        // Entirely tail: a real reddit page titled this way must not lose the vote just because
        // it names a search engine somewhere in ordinary prose.
        assertFalse(SearchEngines.isSearchPageTitle("Why is Google bad?"))
    }

    @Test
    fun `brand tokens after the last separator are recognised across engines`() {
        assertTrue(SearchEngines.isSearchPageTitle("query at DuckDuckGo"))
        assertTrue(SearchEngines.isSearchPageTitle("query - Brave Search"))
        assertTrue(SearchEngines.isSearchPageTitle("query - Yahoo Search Results"))
        assertTrue(SearchEngines.isSearchPageTitle("query - Startpage.com"))
    }

    @Test
    fun `a localized brand word in the tail still vetoes`() {
        // Both of these carry the veto under this implementation, because the brand token
        // ("google") is present in the tail regardless of the surrounding language -- the
        // over-matching this package accepts everywhere else, applied to titles too.
        assertTrue(SearchEngines.isSearchPageTitle("magnet - Google Suche"))
        assertTrue(SearchEngines.isSearchPageTitle("consulta - Búsqueda de Google"))
    }

    @Test
    fun `the loopback escape page matches no rule`() {
        // Whatever the escape lands on must never be identifiable as somewhere with a rule, or
        // leaving a site would immediately re-block it. Loopback does parse as a host -- it has to,
        // or the address bar could not read it back either -- so the property that matters is that
        // it matches nothing.
        val consensus = LinkConsensus.of(List(15) { BrowserRegistry.ESCAPE_URL }, null)
        assertEquals("127.0.0.1", consensus?.host)
        for (rule in listOf("reddit.com", "youtube.com/shorts", "kw:onlyfans")) {
            assertFalse(
                "escape page must not match $rule",
                WebMatcher.matches(consensus!!, rule),
            )
        }
    }
}
