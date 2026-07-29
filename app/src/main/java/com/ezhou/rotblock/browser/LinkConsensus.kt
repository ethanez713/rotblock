package com.ezhou.rotblock.browser

/**
 * Works out what host a page is on from the links the page itself contains.
 *
 * This is the second way of answering "where am I", and it exists because the first one goes away.
 * Samsung Internet does not merely hide the address bar when you scroll -- it removes the whole
 * toolbar from the accessibility tree, leaving the WebView filling the screen. Observed on device:
 * a browser sat on a quota'd site for eleven hours, every read returning nothing, nothing enforced,
 * no symptom anywhere. Holding a target across an unreadable bar fixed the case where a target had
 * already been established; it can do nothing for the case where one never was.
 *
 * Chromium annotates every link node with `AccessibilityNodeInfo.targetUrl`, and a page's links are
 * overwhelmingly to itself -- measured at 164 of 164 on a Reddit post. So the host the links agree
 * on is the host you are on, and unlike the toolbar it is part of the content, which never scrolls
 * away.
 *
 * It is deliberately *weaker* evidence than the address bar, and treated that way everywhere:
 *
 * - It never overrides a bar that was read. The bar is a fact; this is an inference.
 * - It only ever runs to *acquire* a target, never while one is held, so it cannot contradict one.
 * - It reports a host and nothing else. Chromium resolves a same-page `href="#x"` into the full
 *   current URL, so a path is tantalisingly close to available -- but a fragment link to a
 *   *different* page on the same host is indistinguishable from one to this page, and guessing
 *   wrong would put a block on a page no rule covers. A path rule like `youtube.com/shorts`
 *   therefore does not match through this route at all; it degrades to "not blocked", which is the
 *   direction a guess is allowed to fail in.
 * - A split verdict is no verdict. Anything short of [MIN_AGREEMENT] answers null.
 *
 * Subdomains are counted as written, so a page mixing `www.example.com` and `old.example.com`
 * links may fail to reach agreement and simply not match. A miss, not a false block.
 */
object LinkConsensus {

    /**
     * How many parsed link targets are needed before their agreement means anything.
     *
     * Below this a handful of navigation links on an otherwise unrelated page could carry the vote.
     * Real pages clear it easily -- the measured Reddit post had 164.
     */
    const val MIN_SAMPLES = 5

    /** Share of the sample one host must hold. Well above what an outbound-link block reaches. */
    private const val MIN_AGREEMENT = 0.8

    /** The extras key Chromium puts a link's destination under. */
    const val EXTRA_TARGET_URL = "AccessibilityNodeInfo.targetUrl"

    /**
     * The host [urls] agree on, as a path-less [WebMatcher.ParsedUrl], or null if they do not.
     *
     * Parsing goes through [WebMatcher.parse] rather than a URL library so that hosts are
     * normalised exactly the way address-bar text is -- same scheme stripping, same `www.`
     * handling, same refusal to guess at things that are not URLs.
     */
    fun of(urls: List<String>): WebMatcher.ParsedUrl? {
        val hosts = urls.mapNotNull { WebMatcher.parse(it)?.host }
        if (hosts.size < MIN_SAMPLES) return null

        val (host, count) = hosts.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        if (count.toDouble() / hosts.size < MIN_AGREEMENT) return null

        return WebMatcher.ParsedUrl(host = host, path = "")
    }
}
