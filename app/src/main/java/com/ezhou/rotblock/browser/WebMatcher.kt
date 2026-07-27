package com.ezhou.rotblock.browser

/**
 * Matches what a browser's address bar is showing against a website rule.
 *
 * The text in an address bar is not a reliable URL: it may have no scheme, may be a search query,
 * may be the page title, and Chrome hides the scheme and `www.` entirely. So this parses
 * leniently and refuses to guess -- text that does not look like a URL matches nothing, because a
 * false positive here blocks an unrelated page and trains you to switch the whole thing off.
 *
 * Rule syntax:
 *   `reddit.com`              host and any subdomain, any path
 *   `youtube.com/shorts`      host plus a path prefix -- the case that makes Shorts blockable
 *                             without blocking YouTube
 *   `kw:onlyfans`             substring anywhere in the URL
 */
object WebMatcher {

    private const val KEYWORD_PREFIX = "kw:"

    /** A host, a dot, and at least two more characters before any slash. */
    private val LOOKS_LIKE_URL = Regex("^[^/\\s]+\\.[^/\\s]{2,}(/.*)?$")

    data class ParsedUrl(val host: String, val path: String) {
        val full: String get() = if (path.isEmpty()) host else "$host$path"
    }

    /** Returns null when the text is not a URL -- a search term, a page title, or empty. */
    fun parse(raw: String?): ParsedUrl? {
        val text = raw?.trim()?.lowercase() ?: return null
        if (text.isEmpty()) return null

        val withoutScheme = text
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('#')

        if (!LOOKS_LIKE_URL.matches(withoutScheme)) return null

        val slash = withoutScheme.indexOf('/')
        return if (slash < 0) {
            ParsedUrl(host = withoutScheme, path = "")
        } else {
            ParsedUrl(
                host = withoutScheme.substring(0, slash),
                path = withoutScheme.substring(slash),
            )
        }
    }

    /** True if [matcher] applies to [url]. */
    fun matches(url: ParsedUrl, matcher: String): Boolean {
        val rule = matcher.trim().lowercase()
        if (rule.isEmpty()) return false

        if (rule.startsWith(KEYWORD_PREFIX)) {
            val keyword = rule.removePrefix(KEYWORD_PREFIX).trim()
            return keyword.isNotEmpty() && url.full.contains(keyword)
        }

        val normalized = rule.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val slash = normalized.indexOf('/')
        val ruleHost = if (slash < 0) normalized else normalized.substring(0, slash)
        val rulePath = if (slash < 0) "" else normalized.substring(slash)

        val hostMatches = url.host == ruleHost || url.host.endsWith(".$ruleHost")
        if (!hostMatches) return false
        if (rulePath.isEmpty()) return true

        // A path rule matches the prefix, so `/shorts` catches `/shorts/abc123` but not
        // `/shortstories`.
        return url.path == rulePath ||
            url.path.startsWith("$rulePath/") ||
            url.path.startsWith("$rulePath?")
    }

    /** The first matching rule from [matchers], or null. */
    fun firstMatch(raw: String?, matchers: Collection<String>): String? {
        val url = parse(raw) ?: return null
        return matchers.firstOrNull { matches(url, it) }
    }
}
