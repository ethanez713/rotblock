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
}
