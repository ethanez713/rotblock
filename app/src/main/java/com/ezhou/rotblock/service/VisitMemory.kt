package com.ezhou.rotblock.service

import com.ezhou.rotblock.browser.SearchEngines

/**
 * How long a bar read that said "this is a search engine" keeps the link fallback switched off.
 *
 * It has to expire, because it is suppressing the mechanism that exists for pages whose bar cannot
 * be read: hold it forever and one visit to Google would blind the browser until its address bar
 * happened to be readable again. Two minutes is long enough to cover reading a results page and
 * scrolling it, and short enough that it cannot become the reason nothing is ever blocked.
 *
 * Leaving a results page does not normally need the timeout at all -- tapping a result navigates,
 * and browsers reveal the toolbar on navigation, so the next read replaces this within a second.
 */
private const val SEARCH_PAGE_TRUST_MS = 2 * 60 * 1000L

/**
 * What was on screen last time you were inside an app that has to be read to be understood.
 *
 * Switching apps clears the target, because the app you switched *to* is a different question.
 * Coming back then asks the question again -- and inside a browser the answer usually cannot be
 * read: you left the page scrolled, so the address bar is hidden, so the read is `Unreadable`, so
 * the target stays whatever the app switch left it as. Which is nothing.
 *
 * That is a fail-open with no visible symptom at all. Reddit is on screen, the quota is not
 * filling, no block is coming, and nothing anywhere says so -- the app simply stops noticing the
 * site for as long as you keep scrolling. Every trip out of the browser and back re-opened it:
 * the pause screen, the home screen, a notification, a glance at another app.
 *
 * So the last target established *by a read* is remembered per package and restored on re-entry.
 * Restoring can only be wrong for about a second: the app resumes, emits content changes, and the
 * next successful read either confirms it or replaces it. Wrong for a second in the direction of
 * blocking is the trade this app makes everywhere else, and it is the same reasoning as
 * `seedForeground` restoring a visit across a service rebind.
 *
 * The memory is only ever written by a read, never by a restore, so it cannot bootstrap itself
 * into holding a target no read ever established.
 */
class VisitMemory {

    private val byPackage = HashMap<String, String>()

    /** A read established [targetId] inside [pkg]. */
    fun remember(pkg: String, targetId: String) {
        // Bounded, though in practice this only ever holds browsers and apps with feeds.
        if (byPackage.size > 32 && !byPackage.containsKey(pkg)) byPackage.clear()
        byPackage[pkg] = targetId
    }

    /**
     * A read proved [pkg] is showing nothing a policy covers.
     *
     * Only a *successful* read may call this. Forgetting on an unreadable one would throw away
     * the memory in exactly the case it exists for.
     */
    fun forget(pkg: String) {
        byPackage.remove(pkg)
    }

    /** What to enforce on re-entry to [pkg], until a read says otherwise. */
    fun resume(pkg: String): String? = byPackage[pkg]

    /** Diagnostic only -- a copy, so nothing outside this class can mutate the live map. */
    fun snapshot(): Map<String, String> = byPackage.toMap()
}

/**
 * Which packages a bar read recently found sitting on a search engine.
 *
 * Kept apart from [VisitMemory], and not merged into it, because of who touches it: [VisitMemory]
 * is written from the main thread as targets change, this is written from the inspector coroutine
 * as reads land. Two plain HashMaps on two threads is a data race with no symptom until it is a
 * crash, and a crash here is a silently unblocked phone.
 *
 * What it is for: a positive identification switches the link fallback off. Once the bar has said
 * "you are on google.com", the page's links are not evidence about anything -- they are the results
 * -- and consulting them puts a reddit block on a search for reddit. See
 * [com.ezhou.rotblock.browser.SearchEngines]. Scrolling then hides the bar, but what it said stays
 * true for a while afterwards, which is exactly the window the fallback would otherwise fill in.
 */
class SearchPageMemory {

    private val since = HashMap<String, Long>()

    /**
     * A bar read found [pkg] showing [host]. Only a bar may call this: a guess about the page must
     * not be able to switch off the mechanism that does the guessing.
     */
    fun noteReadHost(pkg: String, host: String, nowMs: Long) {
        if (SearchEngines.isSearchHost(host)) {
            if (since.size > 32 && !since.containsKey(pkg)) since.clear()
            since[pkg] = nowMs
        } else {
            // Any other page read clears it at once: the bar has said where we actually are.
            since.remove(pkg)
        }
    }

    /** Whether a read recently found [pkg] on a search engine. See [SEARCH_PAGE_TRUST_MS]. */
    fun isOnSearchPage(pkg: String, nowMs: Long): Boolean {
        val at = since[pkg] ?: return false
        if (nowMs - at >= SEARCH_PAGE_TRUST_MS) {
            since.remove(pkg)
            return false
        }
        return true
    }

    /** Diagnostic only -- a copy, so nothing outside this class can mutate the live map. */
    fun snapshot(): Map<String, Long> = since.toMap()
}
