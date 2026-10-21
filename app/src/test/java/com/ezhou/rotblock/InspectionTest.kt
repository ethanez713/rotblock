package com.ezhou.rotblock

import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.browser.WebMatcher
import com.ezhou.rotblock.service.ASSUMED_SHADE_PACKAGE
import com.ezhou.rotblock.service.Inspected
import com.ezhou.rotblock.service.canDumpWindow
import com.ezhou.rotblock.service.classifyInspection
import com.ezhou.rotblock.service.dumpStatus
import com.ezhou.rotblock.service.shouldAdoptActiveWindow
import com.ezhou.rotblock.service.shouldRearmFromEvent
import com.ezhou.rotblock.service.shouldRecheckForeground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the service concludes from a node-tree read.
 *
 * Every case here is really a question about which way it fails. The one that bit on device was
 * an unreadable address bar being treated as "no website on screen": it dropped the reddit target
 * mid-visit, which both looped the friction countdown and let a scrolled-into page back in
 * unblocked.
 */
class InspectionTest {

    private val sbrowser = "com.sec.android.app.sbrowser"
    private val matchers = listOf("reddit.com", "youtube.com/shorts")
    private val launcher = "com.sec.android.app.launcher"

    @Test
    fun `a matching url resolves to the website target`() {
        assertEquals(
            Inspected.Target("web:reddit.com"),
            classifyInspection(sbrowser, feedKey = null, url = "https://reddit.com/r/all", websiteMatchers = matchers),
        )
    }

    @Test
    fun `a readable url that matches nothing falls back to the app`() {
        assertEquals(
            Inspected.AppOnly,
            classifyInspection(sbrowser, feedKey = null, url = "https://wikipedia.org", websiteMatchers = matchers),
        )
    }

    @Test
    fun `an unreadable address bar holds the current target rather than clearing it`() {
        // Samsung Internet hides the bar as soon as you scroll. Answering AppOnly here is what
        // unblocked reddit mid-visit.
        assertEquals(
            Inspected.Unreadable,
            classifyInspection(sbrowser, feedKey = null, url = null, websiteMatchers = matchers),
        )
    }

    @Test
    fun `a browser with no website rules at all still reads as unreadable when the bar is hidden`() {
        // No matchers means nothing would have matched anyway, but the honest answer is still "I
        // could not see", and AppOnly must not be inferred from a blind read.
        assertEquals(
            Inspected.Unreadable,
            classifyInspection(sbrowser, feedKey = null, url = null, websiteMatchers = emptyList()),
        )
    }

    @Test
    fun `bar text that is not a url holds the target rather than clearing it`() {
        // A half-typed search term is not evidence that you left the site -- the page underneath
        // is still reddit. Reading it as "not on that site" dropped the target, and once dropped a
        // scrolled page never gives it back, because every read after that is unreadable.
        for (text in listOf("how to stop scrolling", "reddit", "about:blank", "   ")) {
            assertEquals(
                text,
                Inspected.Unreadable,
                classifyInspection(sbrowser, feedKey = null, url = text, websiteMatchers = matchers),
            )
        }
    }

    @Test
    fun `the escape destination reads as somewhere else, so leaving a site sticks`() {
        // The other half of the same rule: "Leave this site" has to land somewhere that parses, or
        // holding the target on unreadable text would re-block the page it just left, and tapping
        // the escape again would do the same thing forever.
        assertEquals(
            Inspected.AppOnly,
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = BrowserRegistry.ESCAPE_URL,
                websiteMatchers = matchers,
            ),
        )
    }

    @Test
    fun `a feed beats a website match on the same screen`() {
        assertEquals(
            Inspected.Target("feed:youtube_shorts"),
            classifyInspection(
                "com.google.android.youtube",
                feedKey = "youtube_shorts",
                url = "https://reddit.com",
                websiteMatchers = matchers,
            ),
        )
    }

    @Test
    fun `a non-browser with no feed on screen is just the app`() {
        assertEquals(
            Inspected.AppOnly,
            classifyInspection("com.instagram.android", feedKey = null, url = null, websiteMatchers = matchers),
        )
    }

    private fun hostOnly(host: String) = WebMatcher.ParsedUrl(host = host, path = "")

    @Test
    fun `an already-corroborated link host acquires a target the missing bar could not`() {
        // The eleven-hour failure on device: arrive on a scrolled page, so the bar is not in the
        // tree at all, so there is no target to hold and holding enforces nothing. The links are
        // the only thing left that knows where you are. classifyInspection takes the verdict
        // already computed and corroborated by the caller -- see LinkConsensusTest and
        // LinkCorroborationTest for how that verdict itself is reached.
        assertEquals(
            Inspected.Target("web:reddit.com"),
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = null,
                websiteMatchers = matchers,
                linkHost = hostOnly("reddit.com"),
            ),
        )
    }

    @Test
    fun `a link host with no rule still holds rather than clears`() {
        // The asymmetry that keeps this safe. Link evidence is an inference, so it is allowed to
        // say "you are on reddit" and never "you left it" -- only the address bar may do that.
        // Answering AppOnly here would hand a weaker signal the power that unblocked reddit
        // mid-visit in the first place.
        assertEquals(
            Inspected.Unreadable,
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = null,
                websiteMatchers = matchers,
                linkHost = hostOnly("wikipedia.org"),
            ),
        )
    }

    @Test
    fun `a readable bar always wins over the links`() {
        // The bar is a fact and the links are an inference, so a corroborated reddit host must not
        // override a bar that plainly says wikipedia. In practice the harvest does not even run
        // when the bar is readable; this pins the precedence anyway.
        assertEquals(
            Inspected.AppOnly,
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = "https://wikipedia.org",
                websiteMatchers = matchers,
                linkHost = hostOnly("reddit.com"),
            ),
        )
    }

    @Test
    fun `a path rule does not match through the link fallback`() {
        // Deliberate: link consensus reports a host and no path, so a Shorts rule degrades to
        // "not blocked" rather than blocking all of youtube.com.
        assertEquals(
            Inspected.Unreadable,
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = null,
                websiteMatchers = listOf("youtube.com/shorts"),
                linkHost = hostOnly("youtube.com"),
            ),
        )
    }

    @Test
    fun `a host rule still matches a subdomain the links agree on`() {
        assertEquals(
            Inspected.Target("web:reddit.com"),
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = null,
                websiteMatchers = matchers,
                linkHost = hostOnly("old.reddit.com"),
            ),
        )
    }

    @Test
    fun `no corroborated link host behaves exactly as before`() {
        // The fallback is skipped whenever a target is already held, or whenever no consensus has
        // yet been corroborated by a second, agreeing scan -- this is the common path.
        assertEquals(
            Inspected.Unreadable,
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = null,
                websiteMatchers = matchers,
                linkHost = null,
            ),
        )
    }

    // capture-20260818-093854, the real Shorts player: the surface neither the bar nor the link
    // vote can see on Samsung Internet at all. "Previous video" is deliberately absent -- the real
    // player does not have it, and requiring it is what made this signature fire on watch pages
    // instead. See PageSignatureTest for the watch page these are contrasted against.
    private val shortsDescriptions = listOf("Next video", "Back", "Search YouTube")
    private val shortsTitle = "Example Short Title - YouTube"
    private val shortsLinks = listOf(
        "https://m.youtube.com/@examplecreator/shorts",
        "https://m.youtube.com/watch?v=AAAAAAAAAAA",
        "https://m.youtube.com/source/BBBBBBBBBBB/shorts?bp=8gVDCjYSJwoLQkJCQkJCQkJCQkI",
    )

    @Test
    fun `bar absent, shorts signals, matching rule acquires the shorts target`() {
        assertEquals(
            Inspected.Target("web:youtube.com/shorts"),
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = null,
                websiteMatchers = matchers,
                descriptions = shortsDescriptions,
                title = shortsTitle,
                linkUrls = shortsLinks,
            ),
        )
    }

    @Test
    fun `a host-only bar is refined into the path rule by the signature`() {
        // The actual bug: Samsung Internet's bar reports m.youtube.com with no path at all, so the
        // bar alone can never match youtube.com-slash-shorts. The signature is allowed to refine a
        // bar it agrees with on host.
        assertEquals(
            Inspected.Target("web:youtube.com/shorts"),
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = "m.youtube.com",
                websiteMatchers = matchers,
                descriptions = shortsDescriptions,
                title = shortsTitle,
                linkUrls = shortsLinks,
            ),
        )
    }

    @Test
    fun `a host-only bar with no scan behind it holds the target rather than clearing it`() {
        // The flap, straight off a device capture: reads land about once a second and the walk a
        // signature needs runs once every three, so two reads in three arrive with no signature
        // evidence at all. Answering AppOnly on those said "you left youtube.com/shorts" -- on the
        // strength of a bar that cannot see a path -- and the block went on and off every three
        // seconds. A read that did not look may not say you left.
        assertEquals(
            Inspected.Unreadable,
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = "m.youtube.com",
                websiteMatchers = matchers,
                signatureScanned = false,
            ),
        )
    }

    @Test
    fun `a host-only bar whose scan found no signature does clear the target`() {
        // The other half, and the reason the two cases must be told apart: tapping out of a Short
        // onto an ordinary watch page is a real read of a real page, and it is what lifts the
        // block. Same bar, same absence of a signature -- the difference is only that this read
        // looked.
        assertEquals(
            Inspected.AppOnly,
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = "m.youtube.com",
                websiteMatchers = matchers,
                signatureScanned = true,
            ),
        )
    }

    @Test
    fun `an unscanned bar on a host no signature could refine still clears the target`() {
        // The hold above is scoped to hosts a registered signature could sharpen. Everywhere else
        // the bar is the whole answer and a missing scan changes nothing, or an unreadable verdict
        // would spread to every page in the browser.
        assertEquals(
            Inspected.AppOnly,
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = "wikipedia.org",
                websiteMatchers = matchers,
                signatureScanned = false,
            ),
        )
    }

    @Test
    fun `a bar on an unrelated host is not overridden by the signature`() {
        // The signature must never contradict a bar it disagrees with on host -- an embedded
        // Shorts-shaped player on some other site must not borrow youtube.com's identity. With a
        // reddit rule present too, the bar's own match wins and nothing here claims Shorts.
        assertEquals(
            Inspected.Target("web:reddit.com"),
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = "reddit.com",
                websiteMatchers = matchers,
                descriptions = shortsDescriptions,
                title = shortsTitle,
                linkUrls = shortsLinks,
            ),
        )
    }

    @Test
    fun `bar absent, shorts signals, no matching rule stays unreadable`() {
        // Structurally identified, but nothing covers it -- this must never invent a target, the
        // same rule as every other signal in this file.
        assertEquals(
            Inspected.Unreadable,
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = null,
                websiteMatchers = listOf("reddit.com"),
                descriptions = shortsDescriptions,
                title = shortsTitle,
                linkUrls = shortsLinks,
            ),
        )
    }

    @Test
    fun `a host-only bar with only an unrelated rule falls back to app only`() {
        assertEquals(
            Inspected.AppOnly,
            classifyInspection(
                sbrowser,
                feedKey = null,
                url = "m.youtube.com",
                websiteMatchers = listOf("reddit.com"),
                descriptions = shortsDescriptions,
                title = shortsTitle,
                linkUrls = shortsLinks,
            ),
        )
    }

    @Test
    fun `a feed still beats a shorts signature on the same screen`() {
        assertEquals(
            Inspected.Target("feed:youtube_shorts"),
            classifyInspection(
                "com.google.android.youtube",
                feedKey = "youtube_shorts",
                url = null,
                websiteMatchers = matchers,
                descriptions = shortsDescriptions,
                title = shortsTitle,
                linkUrls = shortsLinks,
            ),
        )
    }

    private val us = "com.ezhou.rotblock"

    @Test
    fun `our own window is never dumpable`() {
        assertFalse(canDumpWindow(us, us))
    }

    @Test
    fun `the shade is never dumpable`() {
        assertFalse(canDumpWindow(ASSUMED_SHADE_PACKAGE, us))
    }

    @Test
    fun `no root at all is never dumpable`() {
        assertFalse(canDumpWindow(null, us))
    }

    @Test
    fun `any window that is not ours or the shade is dumpable`() {
        // canDumpWindow does not even take the service's foreground package -- agreeing with it is
        // recorded (see dumpStatus below), never required. A capture taken while the service has
        // lost track of, or is mid-transition away from, what it thought was in front is exactly
        // the failure worth capturing; refusing here is what made the tool blindest where it is
        // needed most.
        assertTrue(canDumpWindow(sbrowser, us))
    }

    @Test
    fun `an agreeing window produces a status with no disagreement note`() {
        assertEquals("ok", dumpStatus("ok", expectedPackage = sbrowser, rootPackage = sbrowser))
    }

    @Test
    fun `a disagreeing window names both packages in the status`() {
        assertEquals(
            "ok (service expected com.instagram.android, window was $sbrowser)",
            dumpStatus("ok", expectedPackage = "com.instagram.android", rootPackage = sbrowser),
        )
    }

    @Test
    fun `no foreground package known still notes that, as before`() {
        assertEquals(
            "ok (service had no foreground package)",
            dumpStatus("ok", expectedPackage = null, rootPackage = sbrowser),
        )
    }

    // shouldRecheckForeground: whether a content-change event disagreeing with currentPackage is
    // worth verifying against rootInActiveWindow. The currentPackage == null case is deliberately
    // out of scope -- that is handled by adopting the event package outright, with nothing to lose.

    @Test
    fun `a null currentPackage never asks for a recheck, that case adopts directly instead`() {
        assertFalse(
            shouldRecheckForeground(
                eventPackage = sbrowser,
                currentPackage = null,
                relevant = true,
                nowMs = 10_000L,
                lastRecheckAtMs = 0L,
                minIntervalMs = 2_000L,
            ),
        )
    }

    @Test
    fun `an event package matching currentPackage has nothing to verify`() {
        assertFalse(
            shouldRecheckForeground(
                eventPackage = sbrowser,
                currentPackage = sbrowser,
                relevant = true,
                nowMs = 10_000L,
                lastRecheckAtMs = 0L,
                minIntervalMs = 2_000L,
            ),
        )
    }

    @Test
    fun `an irrelevant package never triggers a recheck, however stale currentPackage is`() {
        assertFalse(
            shouldRecheckForeground(
                eventPackage = "com.some.toast",
                currentPackage = sbrowser,
                relevant = false,
                nowMs = 10_000L,
                lastRecheckAtMs = 0L,
                minIntervalMs = 2_000L,
            ),
        )
    }

    @Test
    fun `a relevant package disagreeing outside the throttle asks for a recheck`() {
        assertTrue(
            shouldRecheckForeground(
                eventPackage = sbrowser,
                currentPackage = "com.android.launcher",
                relevant = true,
                nowMs = 10_000L,
                lastRecheckAtMs = 0L,
                minIntervalMs = 2_000L,
            ),
        )
    }

    @Test
    fun `the same disagreement inside the throttle window is refused`() {
        assertFalse(
            shouldRecheckForeground(
                eventPackage = sbrowser,
                currentPackage = "com.android.launcher",
                relevant = true,
                nowMs = 10_000L,
                lastRecheckAtMs = 8_500L,
                minIntervalMs = 2_000L,
            ),
        )
    }

    @Test
    fun `exactly minIntervalMs elapsed clears the throttle`() {
        assertTrue(
            shouldRecheckForeground(
                eventPackage = sbrowser,
                currentPackage = "com.android.launcher",
                relevant = true,
                nowMs = 10_000L,
                lastRecheckAtMs = 8_000L,
                minIntervalMs = 2_000L,
            ),
        )
    }

    // shouldAdoptActiveWindow: an unlock, or a switch whose event was coalesced away, has no event
    // behind it, and for an app that emits no content changes there is no other route back at all.

    @Test
    fun `unlocking onto a different app corrects the foreground`() {
        assertTrue(
            shouldAdoptActiveWindow(
                readPackage = "com.necromerger",
                believedAtRequest = launcher,
                believedNow = launcher,
            )
        )
    }

    @Test
    fun `unlocking back onto the same app is not a correction`() {
        assertFalse(
            shouldAdoptActiveWindow(
                readPackage = "com.necromerger",
                believedAtRequest = "com.necromerger",
                believedNow = "com.necromerger",
            )
        )
    }

    @Test
    fun `nothing readable concludes nothing, it does not mean the app went away`() {
        assertFalse(
            shouldAdoptActiveWindow(
                readPackage = null,
                believedAtRequest = "com.necromerger",
                believedNow = "com.necromerger",
            )
        )
    }

    @Test
    fun `an event that landed while the read was in flight wins over the read`() {
        assertFalse(
            shouldAdoptActiveWindow(
                readPackage = "com.necromerger",
                believedAtRequest = launcher,
                believedNow = sbrowser,
            )
        )
    }

    @Test
    fun `the direction that over-charges is corrected too, not only the one that fails open`() {
        // Locked inside a quota'd app, unlocked onto the launcher: believing the app is still in
        // front keeps charging its quota from a screen it is not on.
        assertTrue(
            shouldAdoptActiveWindow(
                readPackage = launcher,
                believedAtRequest = "com.necromerger",
                believedNow = "com.necromerger",
            )
        )
    }

    @Test
    fun `a foreground established from nothing while the read was in flight is not overwritten`() {
        assertFalse(
            shouldAdoptActiveWindow(
                readPackage = launcher,
                believedAtRequest = null,
                believedNow = "com.necromerger",
            )
        )
    }


    // shouldRearmFromEvent: the ticker is the only thing that re-evaluates a plain app target, and
    // screen-off stops it. Measured on device: 23 minutes of a blocked game enforcing nothing,
    // service alive, events arriving, every one of them discarded.

    private fun rearm(
        hasTarget: Boolean = true,
        tickerArmed: Boolean = false,
        screenOn: Boolean = true,
        nowMs: Long = 100_000L,
        lastRearmAtMs: Long = 0L,
    ) = shouldRearmFromEvent(
        hasTarget = hasTarget,
        tickerArmed = tickerArmed,
        screenOn = screenOn,
        nowMs = nowMs,
        lastRearmAtMs = lastRearmAtMs,
        minIntervalMs = 5_000L,
    )

    @Test
    fun `a held target with a stopped ticker is rescued`() {
        assertTrue(rearm())
    }

    @Test
    fun `an armed ticker means the deadline path is working, so this never runs`() {
        assertFalse(rearm(tickerArmed = true))
    }

    @Test
    fun `nothing held is a different question and this does not answer it`() {
        assertFalse(rearm(hasTarget = false))
    }

    @Test
    fun `a dark screen charges nothing, however many events arrive`() {
        assertFalse(rearm(screenOn = false))
    }

    @Test
    fun `throttled, because content changes arrive continuously while an app renders`() {
        assertFalse(rearm(nowMs = 4_000L, lastRearmAtMs = 0L))
        assertTrue(rearm(nowMs = 5_000L, lastRearmAtMs = 0L))
    }

}
