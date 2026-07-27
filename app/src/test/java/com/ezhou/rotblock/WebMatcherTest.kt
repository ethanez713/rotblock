package com.ezhou.rotblock

import com.ezhou.rotblock.browser.WebMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun matches(url: String, rule: String): Boolean {
    val parsed = WebMatcher.parse(url) ?: return false
    return WebMatcher.matches(parsed, rule)
}

class WebMatcherParseTest {

    @Test
    fun `strips scheme and www`() {
        val parsed = WebMatcher.parse("https://www.reddit.com/r/all")!!
        assertEquals("reddit.com", parsed.host)
        assertEquals("/r/all", parsed.path)
    }

    @Test
    fun `handles a bare host as Chrome displays it`() {
        val parsed = WebMatcher.parse("reddit.com")!!
        assertEquals("reddit.com", parsed.host)
        assertEquals("", parsed.path)
    }

    @Test
    fun `is case insensitive`() {
        assertEquals("reddit.com", WebMatcher.parse("HTTPS://Reddit.COM")!!.host)
    }

    @Test
    fun `drops the fragment`() {
        assertEquals("/watch", WebMatcher.parse("youtube.com/watch#t=30")!!.path)
    }

    @Test
    fun `a search query is not a URL`() {
        // The address bar shows search terms too. Guessing here would block unrelated pages.
        assertNull(WebMatcher.parse("how to stop scrolling"))
        assertNull(WebMatcher.parse("reddit"))
        assertNull(WebMatcher.parse(""))
        assertNull(WebMatcher.parse(null))
    }

    @Test
    fun `a page title with a dot but spaces is not a URL`() {
        assertNull(WebMatcher.parse("Node.js is great for servers"))
    }
}

class WebMatcherRuleTest {

    @Test
    fun `host rule matches any path`() {
        assertTrue(matches("reddit.com", "reddit.com"))
        assertTrue(matches("https://reddit.com/r/all", "reddit.com"))
    }

    @Test
    fun `host rule matches subdomains`() {
        assertTrue(matches("old.reddit.com/r/all", "reddit.com"))
        assertTrue(matches("https://www.old.reddit.com/", "reddit.com"))
    }

    @Test
    fun `host rule does not match a lookalike domain`() {
        // notreddit.com must not be caught by a reddit.com rule.
        assertFalse(matches("notreddit.com", "reddit.com"))
        assertFalse(matches("reddit.com.evil.example", "reddit.com"))
    }

    @Test
    fun `path rule blocks shorts but leaves the rest of youtube alone`() {
        // The whole point of path rules: kill the feed, keep the site.
        assertTrue(matches("youtube.com/shorts/abc123", "youtube.com/shorts"))
        assertTrue(matches("https://www.youtube.com/shorts", "youtube.com/shorts"))
        assertFalse(matches("youtube.com/watch?v=abc", "youtube.com/shorts"))
        assertFalse(matches("youtube.com", "youtube.com/shorts"))
    }

    @Test
    fun `path rule does not match a longer path segment`() {
        assertFalse(matches("youtube.com/shortstories", "youtube.com/shorts"))
    }

    @Test
    fun `path rule matches when a query string follows`() {
        assertTrue(matches("youtube.com/shorts?x=1", "youtube.com/shorts"))
    }

    @Test
    fun `keyword rule matches anywhere in the url`() {
        assertTrue(matches("example.com/watch?v=brainrot", "kw:brainrot"))
        assertTrue(matches("brainrot.example.com", "kw:brainrot"))
        assertFalse(matches("example.com/fine", "kw:brainrot"))
    }

    @Test
    fun `rule with scheme and www still works`() {
        assertTrue(matches("reddit.com/r/all", "https://www.reddit.com"))
    }

    @Test
    fun `empty rule matches nothing`() {
        assertFalse(matches("reddit.com", ""))
        assertFalse(matches("reddit.com", "kw:"))
    }

    @Test
    fun `firstMatch returns the matching rule and ignores non-urls`() {
        val rules = listOf("youtube.com/shorts", "reddit.com")
        assertEquals("reddit.com", WebMatcher.firstMatch("old.reddit.com/r/all", rules))
        assertEquals("youtube.com/shorts", WebMatcher.firstMatch("youtube.com/shorts/x", rules))
        assertNull(WebMatcher.firstMatch("youtube.com/watch?v=x", rules))
        assertNull(WebMatcher.firstMatch("just a search", rules))
    }
}
