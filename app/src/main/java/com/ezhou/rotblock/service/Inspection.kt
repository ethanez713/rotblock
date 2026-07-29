package com.ezhou.rotblock.service

import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.browser.LinkConsensus
import com.ezhou.rotblock.browser.WebMatcher
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind

/**
 * What a node-tree read concluded about the screen.
 *
 * [Unreadable] is the important case, and it is emphatically not the same as [AppOnly]. Collapsing
 * "I could not see" into "nothing specific is on screen" is what made the friction countdown loop
 * on Samsung Internet: the countdown covers the browser, the browser keeps emitting content-change
 * events as the page loads, the read finds rotblock's own window instead of an address bar, and
 * the website target is dropped. Dismiss the countdown and the browser resolves to reddit again,
 * looks like a fresh visit, and shows the countdown again. Forever.
 */
sealed interface Inspected {
    /** A feed or website rule matched what is on screen. */
    data class Target(val targetId: String) : Inspected

    /** Read successfully; nothing more specific than the app itself is showing. */
    data object AppOnly : Inspected

    /** Nothing could be read. Whatever was being enforced stays being enforced. */
    data object Unreadable : Inspected
}

/**
 * Turns what was found in the tree into a target, with no node handling of its own.
 *
 * Split out from the service purely so this can be tested without a device, because the direction
 * each of these answers fails in is the whole fail-closed invariant.
 *
 * Precedence is feeds, then websites, then the app: a Shorts rule should win over a YouTube-app
 * rule.
 *
 * @param feedKey the key of a [com.ezhou.rotblock.block.FeedRegistry] spec whose view ids were
 *   found on screen, or null if none were.
 * @param url the text of the address bar, or null if this package has no address bar *or* the bar
 *   was not in the tree.
 * @param linkUrls destinations harvested from the page's own link nodes, used only to acquire a
 *   target when [url] told us nothing. See [LinkConsensus]; empty means the fallback did not run.
 */
fun classifyInspection(
    pkg: String,
    feedKey: String?,
    url: String?,
    websiteMatchers: List<String>,
    linkUrls: List<String> = emptyList(),
): Inspected {
    if (feedKey != null) return Inspected.Target(Policy.targetId(TargetKind.FEED, feedKey))

    if (BrowserRegistry.specFor(pkg) != null) {
        // Samsung Internet and Chrome both hide the address bar once you scroll down, so an
        // unreadable bar happens constantly on exactly the pages worth blocking. It says nothing
        // about where you are, so it must not clear a website target -- that is what let a
        // scrolled-into reddit come back unblocked after a countdown.
        //
        // This can only ever hold a match already read, never invent one, so a view id that an app
        // update renamed still degrades to "not blocked" rather than sticking a block on the wrong
        // page.
        //
        // Holding is enough only when there is something to hold. When the bar was never readable
        // in the first place -- you arrived on a page already scrolled, or came back to one -- the
        // target was never acquired, and holding nothing enforces nothing, silently, for as long
        // as you stay. The page's own links are the second opinion, and they carry the weaker
        // half of the same asymmetry: they may say "you are on reddit", never "you left it".
        if (url == null) {
            val fromLinks = LinkConsensus.of(linkUrls) ?: return Inspected.Unreadable
            val matched = websiteMatchers.firstOrNull { WebMatcher.matches(fromLinks, it) }
                ?: return Inspected.Unreadable
            return Inspected.Target(Policy.targetId(TargetKind.WEBSITE, matched))
        }

        // Bar text that is not a URL is the same "I could not see" in a different costume, and
        // reading it as "you are not on that site" is the second half of the same fail-open: a
        // half-typed search term, a placeholder, or the `about:blank` a rejected escape left
        // sitting in the bar all dropped a live reddit target, and once dropped a scrolled page
        // never gets it back because every read after that is unreadable.
        //
        // Only a bar that parses as a real URL is allowed to say you left. That is a claim about
        // the page you are on; anything else is a claim about the address bar.
        val parsed = WebMatcher.parse(url) ?: return Inspected.Unreadable
        val matcher = websiteMatchers.firstOrNull { WebMatcher.matches(parsed, it) }
        if (matcher != null) return Inspected.Target(Policy.targetId(TargetKind.WEBSITE, matcher))
    }
    return Inspected.AppOnly
}
