package com.ezhou.rotblock.service

import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.browser.LinkConsensus
import com.ezhou.rotblock.browser.PageSignature
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
 * @param linkHost the host the page's own links have already agreed on and had that agreement
 *   corroborated by a second, consecutive scan -- see [LinkConsensus] and
 *   [com.ezhou.rotblock.browser.LinkCorroboration] -- or null if there is no such verdict yet. The
 *   vote itself is computed by the caller, not here: corroboration needs state across calls that a
 *   pure function cannot hold, and this repo has already had one bug from a decision computed in
 *   two places.
 * @param descriptions on-screen `contentDescription` text harvested alongside the link scan, used
 *   only by [PageSignature]; empty means that fallback did not run either.
 * @param title the text of the page's `WebView` node, if any -- a weak confirmation for
 *   [PageSignature] when the descriptions alone are ambiguous.
 * @param linkUrls every link destination the same walk harvested, unfiltered. Passed separately
 *   from [linkHost] because the two ask different questions of the same evidence: [linkHost] is a
 *   corroborated verdict about which *host* the page is on, while [PageSignature] reads these raw
 *   to ask whether the page's own links carry a *path* -- the half a host vote deliberately throws
 *   away. Empty means the walk did not run.
 * @param signatureScanned whether the walk behind [descriptions] and [linkUrls] actually ran on
 *   this read, as against being throttled out or not wanted. The same distinction
 *   [Inspected.Unreadable] draws against [Inspected.AppOnly], and it is needed for the same
 *   reason: a signature that was never consulted must not read as a signature that failed to
 *   match.
 */
fun classifyInspection(
    pkg: String,
    feedKey: String?,
    url: String?,
    websiteMatchers: List<String>,
    linkHost: WebMatcher.ParsedUrl? = null,
    descriptions: List<String> = emptyList(),
    title: String? = null,
    linkUrls: List<String> = emptyList(),
    signatureScanned: Boolean = true,
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
            // Tried first: it is a positive structural identification of the screen rather than an
            // inference from navigation, and unlike the links it can carry a path -- the whole
            // reason a Shorts rule can ever fire where the bar cannot be read at all. A miss falls
            // straight through to the weaker link vote, exactly as it would with no signature here.
            PageSignature.of(descriptions, title, linkUrls)?.let { fromSignature ->
                websiteMatchers.firstOrNull { WebMatcher.matches(fromSignature, it) }
                    ?.let { return Inspected.Target(Policy.targetId(TargetKind.WEBSITE, it)) }
            }
            val fromLinks = linkHost ?: return Inspected.Unreadable
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

        // The bar read something real and it matched no rule -- ordinarily the end of the
        // question. But Samsung Internet's bar reports host only (measured: no path, `m.` subdomain
        // stripped), so a path rule like youtube.com/shorts can never match through it. The
        // signature may *refine* a bar it agrees with on host (m.youtube.com -> youtube.com/shorts)
        // but never contradict one, which is why it is only ever consulted here with the parsed
        // host as a filter -- an embedded Shorts player on some other site must not borrow
        // youtube.com's identity from a bar that is not on youtube.com.
        PageSignature.of(descriptions, title, linkUrls, compatibleWith = parsed.host)?.let { fromSignature ->
            websiteMatchers.firstOrNull { WebMatcher.matches(fromSignature, it) }
                ?.let { return Inspected.Target(Policy.targetId(TargetKind.WEBSITE, it)) }
        }

        // The bar named a host a signature could sharpen into a path, and this read did not look.
        // Not looking is not the same as finding nothing: the bar cannot see a path at all on
        // Samsung Internet, so on its own it is no evidence whatsoever about whether you are still
        // on youtube.com/shorts, and AppOnly here means "you left". Reads land about once a second
        // and the walk behind a signature runs once every three, so answering AppOnly on the two
        // in between dropped the target and the next scan put it straight back -- the block
        // flapping on and off at the scan interval, measured on device at three seconds.
        //
        // A signature that *was* consulted and missed is a different thing and still answers
        // AppOnly below: that is a real read of a real page, and it is what lifts the block when
        // you tap out of a Short onto an ordinary watch page.
        if (!signatureScanned && PageSignature.couldRefine(parsed.host)) return Inspected.Unreadable
    }
    return Inspected.AppOnly
}

/**
 * The shade's own package, assumed rather than derived: there is no structural way to ask Android
 * "which package owns the notification shade", only a name that has held across every stock and
 * Samsung build this app has run on. A device shipping a different SystemUI package would have its
 * shade recorded in a capture as an ordinary window -- named once, here, so that assumption stays
 * greppable instead of quietly duplicated.
 */
const val ASSUMED_SHADE_PACKAGE = "com.android.systemui"

/**
 * Whether a window a diagnostic capture found is one worth reading.
 *
 * Only [ourPackage] and [shadePackage] are refused. Matching what the service believed was in
 * front is deliberately *not* required: a capture taken while the service has lost track of the
 * foreground, or mid-transition to some other window, is precisely the failure a capture exists to
 * investigate, and refusing to dump there would make the tool blindest exactly where it is needed.
 * The disagreement itself still has to reach the reader -- see [dumpStatus] -- because recording it
 * silently as if it agreed would be its own kind of misleading capture.
 */
fun canDumpWindow(
    rootPackage: String?,
    ourPackage: String,
    shadePackage: String = ASSUMED_SHADE_PACKAGE,
): Boolean = rootPackage != null && rootPackage != ourPackage && rootPackage != shadePackage

/**
 * Annotates a node dump's [status] with why the window read may not be the one the service
 * expected, so a reader can tell "this tree is the window the service thought was in front" from
 * "this tree is some other window, recorded because refusing would have given nothing at all."
 * [expectedPackage] is null when the service never established a foreground package in the first
 * place -- also worth recording, and also not a reason to refuse the read (see [canDumpWindow]).
 */
fun dumpStatus(status: String, expectedPackage: String?, rootPackage: String): String = when {
    expectedPackage == null -> "$status (service had no foreground package)"
    expectedPackage != rootPackage -> "$status (service expected $expectedPackage, window was $rootPackage)"
    else -> status
}

/**
 * Whether a content-change event disagreeing with [currentPackage] is worth verifying against
 * `rootInActiveWindow`.
 *
 * A content-change event's package name is structural -- the framework stamps it, not the app --
 * but a package *emitting* content changes is only heuristic evidence that it owns the foreground:
 * a background browser tab keeps rendering and firing these events while some other app is really
 * in front. That is why this never adopts [eventPackage] outright the way the `currentPackage ==
 * null` branch does; it only asks whether a foreground check is owed, and the caller settles the
 * question against the one structural signal that can, `rootInActiveWindow`.
 *
 * [currentPackage] null is excluded on purpose -- that case has nothing to lose and is handled by
 * adopting the event package directly, without a check. Here there is a competing belief already
 * on the books, so no answer may be returned without verification.
 *
 * [relevant] mirrors [RotblockAccessibilityService.isForegroundChange]'s asymmetry: an event from a
 * package with no policy, that is not a browser or feed app, and is not a real activity must never
 * trigger a check, or a churning toast could burn the throttle window a real recovery needs.
 *
 * Throttled to [minIntervalMs] because nothing here can tell "the real foreground changed and
 * `currentPackage` is now stale" apart from "a background tab is still rendering" without paying
 * for a `rootInActiveWindow` read -- so the only way to bound the cost is spacing the reads out
 * rather than judging them.
 */
fun shouldRecheckForeground(
    eventPackage: String,
    currentPackage: String?,
    relevant: Boolean,
    nowMs: Long,
    lastRecheckAtMs: Long,
    minIntervalMs: Long,
): Boolean {
    if (currentPackage == null) return false
    if (eventPackage == currentPackage) return false
    if (!relevant) return false
    return nowMs - lastRecheckAtMs >= minIntervalMs
}

/**
 * Whether a package read off the active window should replace [believedNow], when no event is
 * going to say so: after an unlock, and after a burst of window-state changes has settled.
 *
 * The settle exists because the event that *did* arrive is often not the app switch. The
 * framework's `notificationTimeout` hands over only the last window-state change of a burst, and on
 * One UI the launcher, the navigation-gesture overlay and Game Booster all post theirs alongside the
 * app's — so the switch into a game can be dropped outright, and a game rendering into a surface
 * emits nothing afterwards that would ever correct it.
 *
 * Unlocking is the other foreground change that arrives with no accessibility event attached to
 * it. `ACTION_SCREEN_OFF` leaves [RotblockAccessibilityService.currentPackage]
 * standing, and the phone can come back on a different app entirely -- Android restores the top
 * task, a notification was taken from the lock screen, the launcher is what appears. Nothing in
 * the event stream says so, and for an app that emits no content changes of its own -- a game
 * rendering into a surface, which is most of them -- nothing ever will: [shouldRecheckForeground]
 * is driven by content-change events, and `requestInspect` declines a package that is not a
 * browser or feed app. So a foreground that was stale at the moment of unlock stays stale until
 * the user happens to switch apps by hand, with enforcement running against whatever was on
 * screen last night.
 *
 * It fails in both directions at once, which is why it gets its own path rather than being left to
 * the ordinary event routes: unlocking *into* a blocked app enforces nothing, and unlocking *out*
 * of one goes on charging its quota from the launcher.
 *
 * Unlike [shouldRecheckForeground] there is no candidate package to weigh here and no throttle to
 * pay for. `rootInActiveWindow` read once the screen has settled is the same
 * structural evidence [RotblockAccessibilityService.seedForegroundFromScreen] already trusts to
 * establish a foreground from nothing; the only difference is that this one is allowed to
 * *correct* one. So the guards are about staleness of the answer rather than strength of it:
 *
 * - Nothing readable ([readPackage] null) concludes nothing. An unreadable window is not evidence
 *   that the app went away, exactly as [Inspected.Unreadable] is not [Inspected.AppOnly].
 * - A real accessibility event that landed while the read was in flight ([believedNow] having
 *   moved off [believedAtRequest]) knows better than a poll does, the same precedence
 *   `seedForeground` gives an event over its own seed.
 * - Agreeing with what is already believed is not a correction and must not be reported as one.
 */
fun shouldAdoptActiveWindow(
    readPackage: String?,
    believedAtRequest: String?,
    believedNow: String?,
): Boolean {
    if (readPackage == null) return false
    if (believedNow != believedAtRequest) return false
    return readPackage != believedNow
}

/**
 * Whether a held target with a dead ticker should be re-evaluated off an accessibility event.
 *
 * The ticker is the *only* thing that re-evaluates a plain app target. A browser or feed app has
 * the inspection loop, which ends in `applyTarget` on every read — but `requestInspect` returns
 * immediately for anything else, so once a game is established as the foreground, every event it
 * emits reaches `pkg == currentPackage` and stops there. Nothing else in the event path calls
 * `evaluateAndEnforce` at all.
 *
 * That is survivable while the ticker is armed, and the ticker is armed for every decision that has
 * a deadline. `ACTION_SCREEN_OFF` stops it, which is correct — a dark screen must charge nothing —
 * and `ACTION_USER_PRESENT` starts it again. The hole is a screen that comes back on with **no
 * keyguard to pass**: inside Samsung's lock-delay grace period no `USER_PRESENT` is broadcast, and
 * an app that was already in front fires no window-state change either. Measured on device: the
 * service alive with a 45-second-old heartbeat, `currentPackage` and `currentTargetId` both
 * correctly naming a blocked game, the node dump showing that game on screen, the ticker stopped
 * and the trail frozen — twenty-three minutes of a quota'd game enforcing nothing, with events
 * arriving the whole time and every one of them discarded.
 *
 * So this is the same bargain [shouldRecheckForeground] makes, one layer over: in the healthy case
 * a held target has an armed ticker and this never fires, and the cost only appears once the fault
 * has. The gates are what keep it honest:
 *
 * - **A held target only.** With nothing held there is nothing to re-evaluate, and adopting the
 *   event's package is a different question with a different answer.
 * - **An armed ticker means the deadline path is working**, so there is nothing to rescue.
 * - **Screen off charges nothing, ever.** Events can still arrive with the display dark, and
 *   resuming accrual on one would charge for time nobody spent — the direction this app may not
 *   fail in, and the reason `ACTION_SCREEN_ON` is not enough on its own.
 * - **Throttled**, because content-change events arrive continuously while an app renders.
 *
 * The case that pays without being broken is a hard block on an always-on rule: it has no deadline,
 * so the ticker is legitimately stopped and this fires every [minIntervalMs] for as long as you sit
 * on the block screen. That is a cheap fold and no node read, and it buys the block screen coming
 * back within one interval if it is ever lost — which is the right side of this trade.
 */
fun shouldRearmFromEvent(
    hasTarget: Boolean,
    tickerArmed: Boolean,
    screenOn: Boolean,
    nowMs: Long,
    lastRearmAtMs: Long,
    minIntervalMs: Long,
): Boolean {
    if (!hasTarget) return false
    if (tickerArmed) return false
    if (!screenOn) return false
    return nowMs - lastRearmAtMs >= minIntervalMs
}

/** What [verifyEscapeOutcome] concluded about an escape attempt. */
enum class EscapeVerdict { ESCAPED, REFUSED, UNCONFIRMED }

/**
 * Whether an escape actually took, without collapsing "nobody has looked yet" into "the browser
 * refused" -- the same [Inspected.Unreadable]-vs-[Inspected.AppOnly] distinction this file draws
 * everywhere else, applied to the one place it was still missing.
 *
 * The original check re-blocked whenever [currentTargetId] was still [escapedTargetId] a fixed
 * delay after the escape, whatever caused that: a browser that genuinely refused to navigate, or a
 * run of reads that never got past `window belongs to com.android.systemui` and so never looked at
 * the browser at all. A device capture showed the second case: four escapes in 24 seconds, every
 * verify window landing on a transient system window, five re-blocks, and the browser sitting on
 * the loopback URL the whole time -- the escape had worked every single time.
 *
 * [lastWindowReadAtMs] is the timestamp of the last read that got far enough to inspect the
 * package's own window -- see [RotblockAccessibilityService.resolveInspectedTarget] -- as opposed
 * to one that bounced off a null or foreign `rootInActiveWindow`. Only that counts as evidence:
 *
 * - The target having moved is conclusive on its own, however stale the read clock is: `ESCAPED`.
 * - A read that landed *after* the escape and still names [escapedTargetId] is positive evidence
 *   the page did not move: `REFUSED`.
 * - Nothing readable yet is not evidence of anything. Re-blocking on it would punish an escape that
 *   worked for the crime of nobody having looked, which is the exact bug this replaces -- so it
 *   answers `UNCONFIRMED` and waits for another window, up to [maxAttempts] of them.
 *
 * `UNCONFIRMED` is not a fail-open: it is bounded by [maxAttempts], the screen is on and the user
 * is standing in front of it, and once the bound is hit this answers `REFUSED` and the block comes
 * back regardless of what has or has not been readable. The alternative -- waiting forever for a
 * read that a broken registry or a permanently obstructed window might never produce -- is the
 * shape of failure this app may not have; the direction this trades away instead is a few extra
 * seconds of unmetered time on a page the user has, in every observed case, already left.
 *
 * A read landing at exactly [escapedAtMs] is not evidence about anything that happened *after* the
 * escape, so the comparison is strictly greater-than.
 */
fun verifyEscapeOutcome(
    escapedTargetId: String,
    currentTargetId: String?,
    escapedAtMs: Long,
    lastWindowReadAtMs: Long,
    attempt: Int,
    maxAttempts: Int,
): EscapeVerdict = when {
    currentTargetId != escapedTargetId -> EscapeVerdict.ESCAPED
    lastWindowReadAtMs > escapedAtMs -> EscapeVerdict.REFUSED
    attempt >= maxAttempts -> EscapeVerdict.REFUSED
    else -> EscapeVerdict.UNCONFIRMED
}
