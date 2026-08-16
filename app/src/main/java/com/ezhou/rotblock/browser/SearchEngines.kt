package com.ezhou.rotblock.browser

/**
 * Hosts that are search engines, and therefore pages whose links are about somewhere else.
 *
 * [LinkConsensus] rests on one assumption: a page's links overwhelmingly point at the page's own
 * host. A results page is the one page on the web built to break that assumption -- search for
 * anything and most of what is on screen links somewhere you are not. Search "reddit" and every
 * result is a reddit.com link, so the consensus is unanimous, confident, and wrong, and the block
 * lands on a search page no rule covers. Observed exactly that way.
 *
 * There is no clever fix, because the evidence genuinely says reddit: the only thing that
 * distinguishes the page is *which site it is*, and that is the thing we could not read. So it is
 * hardcoded, which is also the only thing that stays true when the address bar is gone.
 *
 * Matching is by brand label anywhere in the host, so `google.com`, `www.google.co.uk`,
 * `news.google.com` and `google.de` are all covered without enumerating a hundred country domains.
 * That over-matches -- `sites.google.com` is not a search engine -- and over-matching is free here:
 * every consequence is "do not conclude anything from the links", which degrades to not blocked.
 * The only real cost is that a rule *on* a search engine cannot be acquired from links alone, and
 * has to wait for an address bar it can read.
 */
object SearchEngines {

    /**
     * The distinctive label of each engine's domain. Not full hosts: `google` has to catch every
     * ccTLD, and `search.brave.com` and `search.marcia.com`-style subdomains vary.
     *
     * Deliberately not `ask` or `aol` -- too short and too generic to be worth the collisions,
     * against engines nobody sends traffic through any more.
     */
    private val BRANDS = setOf(
        "google",
        "bing",
        "duckduckgo",
        "yahoo",
        "yandex",
        "baidu",
        "ecosia",
        "startpage",
        "qwant",
        "mojeek",
        "searx",
        "kagi",
        "perplexity",
        "naver",
        "seznam",
        "brave",
    )

    /** True if [host] belongs to a search engine. [host] is a parsed, lowercased host. */
    fun isSearchHost(host: String): Boolean =
        host.split('.').any { it in BRANDS }

    /**
     * Separators a document title uses before its site's own branding: `"query - Google Search"`,
     * `"query at DuckDuckGo"`, `"query - Brave Search"`, `"query : r/subreddit"`. Ordered longest
     * dash-like variants first only because it reads that way; matching is by last-occurrence, not
     * by trying these in order.
     */
    private val TITLE_SEPARATORS = listOf(" - ", " – ", " — ", " | ", " · ", ": ", " at ")

    /** Held rather than built per call: this runs on the inspector coroutine, on every page scan. */
    private val NON_BRAND_CHARS = Regex("[^a-z0-9]+")

    /**
     * True if [title] names a search engine in its own branding slot -- the tail after the last
     * separator, never the query or subject that makes up the rest of the title.
     *
     * This is the fallback for [isSearchHost] on the page that broke it: a Samsung Internet search
     * results page with no google.* host anywhere in its own links, because the results all pointed
     * to reddit.com, youtube.com and other third parties. `"how do magnets work - Google
     * Search"` was the measured title. A results page still carries its own title even with the
     * address bar scrolled away, so this is evidence [LinkConsensus] can reach when the per-link
     * veto has nothing to look at.
     *
     * Titles are strings other products chose, in whatever language the device is set to --
     * `"- Google Search"` becomes `"- Google Suche"` in German -- and a redesign can reshuffle the
     * format overnight with no warning here. So this over-matches exactly like [isSearchHost], and
     * a page whose tail happens to name a brand goes with it: a thread in `r/google` reads back as
     * the tail `r/Google` and is vetoed, as is anything titled `"Look at Google Maps"`. Such a page
     * cannot be acquired from links and has to wait for a readable address bar. That is the whole
     * cost, and it degrades to "not blocked" the same way every miss in this package does.
     *
     * Only the tail is ever tested, and a title with no separator at all gets no verdict, because a
     * reddit post titled "Why is Google bad?" is entirely tail -- testing the whole string would
     * fail open on a real reddit page, which is the direction that must not happen.
     */
    fun isSearchPageTitle(title: String): Boolean {
        val (start, separator) = TITLE_SEPARATORS
            .mapNotNull { sep -> title.lastIndexOf(sep).takeIf { it >= 0 }?.let { it to sep } }
            .maxByOrNull { (start, _) -> start } ?: return false
        val tail = title.substring(start + separator.length)
        return tail.lowercase().split(NON_BRAND_CHARS).any { it in BRANDS }
    }
}
