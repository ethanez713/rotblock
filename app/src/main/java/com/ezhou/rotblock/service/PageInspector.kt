package com.ezhou.rotblock.service

import android.os.Build
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.browser.AddressBar
import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.browser.BrowserSpec
import com.ezhou.rotblock.browser.LinkConsensus
import com.ezhou.rotblock.browser.LinkCorroboration
import com.ezhou.rotblock.browser.PageSignature
import com.ezhou.rotblock.browser.WebMatcher
import com.ezhou.rotblock.browser.shouldAttemptReveal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Coalescing window for content-change bursts before the node tree is read. */
private const val INSPECT_DEBOUNCE_MS = 250L

/**
 * Floor on the gap between two node-tree reads.
 *
 * Scrolling a feed emits content-change events without pause, and the debounce alone still let
 * that turn into four full tree searches a second, every one of them an IPC into the app being
 * scrolled. Nothing we read from the tree -- the address bar, a feed container -- changes that
 * fast, so the worst this costs is a second of latency on noticing a new URL.
 */
private const val MIN_INSPECT_INTERVAL_MS = 1_000L

/**
 * Bounds on the page-signal scan, which now collects two things in one walk: the link-target
 * fallback (see [com.ezhou.rotblock.browser.LinkConsensus]) and the page-signature fallback (see
 * [com.ezhou.rotblock.browser.PageSignature]).
 *
 * [MAX_LINK_SCAN_NODES] is the only thing that bounds the cost of the walk. It used to look like
 * [MAX_LINK_URLS_RETAINED] was a second, cheaper stopping point for a links-only walk -- but
 * `wantLinks` requires the bar to be unreadable, and an unreadable bar makes `wantSignature` true
 * as well (its own condition is satisfied outright once the bar said nothing), so a links-only
 * walk that skips the signature collection never actually happens. Every walk that runs for links
 * already pays the full [MAX_LINK_SCAN_NODES] budget collecting descriptions too, whether or not
 * anything ends up wanting them. So [MAX_LINK_URLS_RETAINED] is a cap on memory only: how many of
 * the links a full walk turns up are worth holding on to once past it there is nothing left to
 * learn from more.
 *
 * That matters because the old sample cap was silently discarding the evidence
 * [com.ezhou.rotblock.browser.LinkConsensus] needs most: on a search-results page the walk had
 * already visited every node in its budget, but kept only the first twelve links, which on a
 * "reddit" search could all be results -- so the veto that should have discarded the vote for
 * seeing a search engine's own navigation link never got to look at the rest of the page. Raising
 * the cap to the walk's actual node budget costs nothing extra; it stops throwing evidence away
 * that the walk had already paid to collect.
 *
 * A page's own navigation links still cluster at the top of its tree -- measured on device, ten of
 * them sit within thirty nodes of a depth-first walk -- so 64 is generous headroom over what a real
 * page needs while staying well under [MAX_LINK_SCAN_NODES]. [MAX_DESCRIPTION_SAMPLES] bounds the
 * description collection the same way -- a real Shorts player was measured at ~37 nodes total, so
 * 60 is headroom, not a tight fit.
 *
 * The interval is a floor on top of [MIN_INSPECT_INTERVAL_MS] rather than a substitute for it,
 * because a sustained scroll re-arms the read every second and none of those reads can see
 * anything new. Acquiring a target up to [MIN_PAGE_SCAN_INTERVAL_MS] late costs a few seconds
 * of unmetered time once per visit; paying for the scan every second costs it forever.
 */
internal const val MAX_LINK_SCAN_NODES = 120
private const val MAX_LINK_URLS_RETAINED = 64
private const val MAX_DESCRIPTION_SAMPLES = 60
private const val MIN_PAGE_SCAN_INTERVAL_MS = 3_000L

/**
 * How long a reveal tap is given before the next read is requested. Long enough for the toolbar
 * animation to finish and settle; short enough that the visit is not left unread for a full tick.
 */
private const val REVEAL_FOLLOWUP_DELAY_MS = 500L

class PageInspector(
    private val handler: Handler,
    private val actions: AccessibilityActions,
    private val belief: ForegroundBelief,
    private val settings: SettingsMirror,
    private val serviceGuard: ServiceGuard,
    private val coverage: CoverageIndex,
    private val coordination: InspectCoordination,
    private val sink: InspectedTargetSink,
) {
    private fun rootInActiveWindow() = actions.rootInActiveWindow()
    private fun currentPackage() = belief.currentPackage
    private fun currentTargetId() = belief.currentTargetId
    private fun ignoredPackages() = coverage.ignoredPackages
    private fun websiteMatchers() = coverage.websiteMatchers
    private fun revealEnabled() = settings.revealAddressBarEnabled
    private fun revealAttemptedThisVisit() = coordination.revealAttemptedThisVisit
    private fun markRevealAttempted() = coordination.markRevealAttempted()
    private fun foregroundSettlePending() = coordination.foregroundSettlePending()
    private fun scheduleForegroundSettle() = coordination.scheduleForegroundSettle()
    private fun guard(where: String, block: () -> Unit) = serviceGuard.guard(where, block)
    private fun trace(message: String) = serviceGuard.trace(message)
    private fun noteDetectionLook(pkg: String, foundFeedKey: String?) = sink.noteDetectionLook(pkg, foundFeedKey)
    private fun rememberVisit(pkg: String, targetId: String) = sink.rememberVisit(pkg, targetId)
    private fun forgetVisit(pkg: String) = sink.forgetVisit(pkg)
    private fun appTargetFor(pkg: String) = sink.appTargetFor(pkg)
    private fun applyTarget(targetId: String?, nowMs: Long, confirmed: Boolean) =
        sink.applyTarget(targetId, nowMs, confirmed)

    private val inspectRequests = Channel<String>(Channel.CONFLATED)

    /**
     * Written on the inspector coroutine the moment a read gets far enough to inspect the target
     * package's own window -- past the null-root and wrong-package guards in
     * [resolveInspectedTarget], whatever it goes on to conclude. Read on the main thread by
     * [EscapeController.verifyEscaped], hence volatile.
     */
    @Volatile private var lastWindowReadAtMs = 0L

    fun lastWindowReadAtMs(): Long = lastWindowReadAtMs

    /** Inspector coroutine only. A duplicated scan would be harmless, so no synchronisation. */
    private var lastPageScanMs = 0L

    /** Inspector coroutine only, like [lastPageScanMs]. See [SearchPageMemory]. */
    private val searchPages = SearchPageMemory()

    /** Inspector coroutine only, like [lastPageScanMs]. See [LinkCorroboration]. */
    private val linkCorroboration = LinkCorroboration()

    /** Inspector coroutine only. Last reveal-tap time per package, for [shouldAttemptReveal]'s cooldown. */
    private val revealLastAttemptMs = HashMap<String, Long>()

    fun searchPageSnapshot(): Map<String, Long> = searchPages.snapshot()

    fun linkCorroborationSnapshot(): Map<String, Pair<String, Long>> = linkCorroboration.snapshot()

    /**
     * Queues a node-tree read, if one could tell us anything.
     *
     * Deliberately *not* gated on [Interstitial.isShowing], though skipping reads that can only
     * see our own window is tempting and was tried. Two reasons it has to stay ungated:
     *
     * - The app underneath resumes before our activity stops, so its window-state change — the one
     *   event that reliably prompts a re-read — always lands while the gate is still shut. On a
     *   static page nothing else follows, and the target is left as whatever the blinded read made
     *   of it. Measured at 345ms of overlap on device; it let reddit straight through.
     * - A gate keyed on a count that an activity killed without `onStop` never decrements would
     *   suppress every website and feed read for the life of the service. Stuck shut is a far worse
     *   failure than a few wasted reads.
     *
     * The read itself already declines to draw conclusions from the wrong window, which is where
     * that protection belongs.
     */
    fun requestInspect(pkg: String) {
        if (pkg !in inspectablePackages) return
        inspectRequests.trySend(pkg)
    }

    private val inspectablePackages: Set<String> = BrowserRegistry.packages + FeedRegistry.packages

    /**
     * Reads the node tree off the event callback.
     *
     * The channel is conflated and the loop both debounces and rate-limits, so a burst of a hundred
     * content-change events during a scroll collapses into one tree read, and a sustained scroll
     * costs at most one read a second.
     */
    suspend fun runInspector() {
        var lastReadMs = 0L
        for (pkg in inspectRequests) {
            delay(INSPECT_DEBOUNCE_MS)
            val sinceLastRead = System.currentTimeMillis() - lastReadMs
            if (sinceLastRead < MIN_INSPECT_INTERVAL_MS) delay(MIN_INSPECT_INTERVAL_MS - sinceLastRead)
            lastReadMs = System.currentTimeMillis()

            val resolved = try {
                resolveInspectedTarget(pkg)
            } catch (t: Throwable) {
                sink.recordInspectError(t)
                Inspected.Unreadable
            }
            withContext(Dispatchers.Main) {
                guard("applyInspected") {
                    trace("inspected $pkg -> $resolved (current=${currentPackage()})")
                    // Discard if the user has moved on since the read started.
                    if (pkg != currentPackage()) return@guard
                    val targetId = when (resolved) {
                        is Inspected.Target -> {
                            rememberVisit(pkg, resolved.targetId)
                            resolved.targetId
                        }
                        // A successful read is the only thing allowed to say "nothing here".
                        Inspected.AppOnly -> {
                            forgetVisit(pkg)
                            appTargetFor(pkg)
                        }
                        // Keep enforcing whatever was last established. Every other outcome of a
                        // blind read weakens a block; this one cannot.
                        Inspected.Unreadable -> return@guard
                    }
                    // Both branches above are a read that actually landed -- Unreadable already
                    // returned above without reaching here -- so this is exactly the evidence
                    // [TargetConfidence] exists to require before the friction countdown may fire.
                    applyTarget(targetId, System.currentTimeMillis(), true)
                }
            }
        }
    }

    /**
     * Reads the screen and hands what it found to [classifyInspection], which decides what it
     * means. Everything node-shaped lives here; every fail-open judgement lives there.
     */
    fun resolveInspectedTarget(pkg: String): Inspected {
        // Started before rootInActiveWindow, which is itself an IPC and part of what this costs.
        // Elapsed wall time rather than CPU on purpose: the traversal runs in the *other* app's
        // process, so the number that matters is how long that app was held up, not how much of
        // this process's own budget went on it. Nothing outside rotblock can measure that.
        val readStartNs = System.nanoTime()
        val root = rootInActiveWindow()
        if (root == null) {
            trace("read($pkg): rootInActiveWindow null")
            EnforcementStatus.note("read $pkg: no active window (target held)")
            return Inspected.Unreadable
        }
        try {
            // The active window is often not the app we were asked about -- most often it is one of
            // rotblock's own full-screen screens sitting on top of it, while the app underneath
            // keeps emitting content-change events as its page loads. Anything concluded from the
            // wrong window is noise, and acting on it drops a live target.
            if (root.packageName?.toString() != pkg) {
                trace("read($pkg): active window is ${root.packageName}")
                EnforcementStatus.note("read $pkg: window belongs to ${root.packageName} (target held)")
                // Not adopted from here: mid-transition this read still sees the app being left.
                // A settle waits for the transition to finish and then reads again.
                val owner = root.packageName?.toString()
                if (owner != null && owner !in ignoredPackages()) {
                    handler.post {
                        if (owner != currentPackage() && !foregroundSettlePending()) {
                            scheduleForegroundSettle()
                        }
                    }
                }
                return Inspected.Unreadable
            }

            lastWindowReadAtMs = System.currentTimeMillis()

            val feedSpecs = FeedRegistry.specsFor(pkg)
            val feedKey = feedSpecs
                .firstOrNull { spec -> spec.viewIdHints.any { root.hasViewId(it) } }
                ?.key
            if (feedSpecs.isNotEmpty()) noteDetectionLook(pkg, feedKey)
            val browserSpec = BrowserRegistry.specFor(pkg)
            val bar = browserSpec?.let { root.readAddressBar(it) }
            val url = (bar as? AddressBar.Read.Text)?.value
            // A screen offering a choice of URLs is not a page, so nothing on it is evidence about
            // where you are -- not the row that happens to be first, and not the page's links
            // either. Suppressing the harvest as well is what keeps this from being a fix that only
            // covers the door it came in through.
            val chooser = bar is AddressBar.Read.Chooser

            // A bar that parsed is a positive identification of the page, and it is remembered for
            // one reason: to say when the links must not be consulted. See [SearchPageMemory].
            val now = System.currentTimeMillis()
            val parsedBar = url?.let { WebMatcher.parse(it) }
            parsedBar?.let { searchPages.noteReadHost(pkg, it.host, now) }

            val isBrowser = browserSpec != null
            // Only when the bar said nothing *and* nothing is currently being enforced here. Once a
            // target is held there is nothing to acquire, so the scan cannot contradict a live
            // target -- and the common case, scrolling a page we already recognised, never pays
            // for it at all.
            val wantLinks = url == null &&
                !chooser &&
                currentTargetId() == null &&
                isBrowser &&
                websiteMatchers().isNotEmpty() &&
                // And not while the last thing the bar said was "this is a search engine". Its
                // links are the results; they describe where you are not.
                !searchPages.isOnSearchPage(pkg, now)
            // A signature may only ever refine a bar it agrees with on host, so it is only worth
            // running where that is even possible: the bar said nothing at all, or it said
            // something a registered signature could plausibly extend (m.youtube.com ->
            // youtube.com/shorts). A readable bar on any other page runs nothing extra at all --
            // the whole reason this stays free everywhere it does not apply.
            //
            // Whether a target is already held decides it only in the first of those two cases,
            // and the split is the point. With nothing readable in the bar, classifyInspection
            // already holds whatever is held, so a scan buys nothing and the common case --
            // scrolling a page we recognised long ago -- must not pay for one. With a readable bar
            // it is the opposite: the bar names youtube.com, matches no path rule, and would
            // otherwise say "you left youtube.com/shorts" on every read after the one that
            // acquired it. The signature is the only signal here that can carry a path, so it has
            // to be allowed to say so again and again for as long as the Short is on screen; gated
            // to acquisition it could name a target but never hold one, and the block flapped on
            // and off every three seconds. Nothing is lost by running it: its safety has never come
            // from the target being absent, it comes from `compatibleWith` refusing a bar that
            // disagrees on host.
            val wantSignature = !chooser &&
                isBrowser &&
                websiteMatchers().isNotEmpty() &&
                if (url == null) {
                    currentTargetId() == null
                } else {
                    parsedBar != null && PageSignature.couldRefine(parsedBar.host)
                }

            val signals = if (wantLinks || wantSignature) {
                harvestPageSignals(root, collectSignature = wantSignature)
            } else {
                null
            }
            val linkUrls = if (wantLinks) signals?.linkUrls.orEmpty() else emptyList()
            val descriptions = if (wantSignature) signals?.descriptions.orEmpty() else emptyList()
            val title = if (wantSignature) signals?.title else null
            // Gated on wantSignature rather than wantLinks, because the two want the same harvest
            // for opposite reasons and are not on at the same times. A bar that read
            // `m.youtube.com` switches the link *vote* off -- there is nothing left to acquire --
            // while leaving the signature on to refine that host into a path, and a signature that
            // needs its links would otherwise be handed an empty list in exactly the case it was
            // added for.
            val signatureLinkUrls = if (wantSignature) signals?.linkUrls.orEmpty() else emptyList()

            // The decision lives here and nowhere else -- classifyInspection takes the already-
            // corroborated verdict rather than re-deriving one from linkUrls, so there is exactly
            // one place a link vote can be computed. Corroboration needs cross-call state (the
            // previous scan's verdict for this package), which a pure function cannot hold, hence
            // computing it up here rather than down in classifyInspection.
            //
            // Only a read whose walk actually ran may vote, or clear a pending vote -- hence the
            // null check on [signals] rather than on [wantLinks] alone. A read throttled by
            // MIN_PAGE_SCAN_INTERVAL_MS did not look, and on a page being scrolled two out of every
            // three reads are exactly that. Letting those offer "no consensus" would clear the
            // pending verdict between every pair of real scans, so no two consecutive votes could
            // ever agree, and the link fallback -- the fix for a browser that sat on a quota'd site
            // for eleven hours -- would silently never fire again. It is the same distinction
            // [Inspected.Unreadable] draws against [Inspected.AppOnly], one layer down: not looking
            // is not the same as finding nothing.
            val scanned = signals != null
            // title is safe to pass here unconditionally: wantLinks requires url == null, which
            // alone satisfies wantSignature's condition, so a read that wants links always
            // harvested the title too.
            val linkConsensusHost = if (wantLinks && scanned) LinkConsensus.of(linkUrls, title)?.host else null
            val corroboratedHost = if (wantLinks && scanned) {
                linkCorroboration.offer(pkg, linkConsensusHost, now)
            } else {
                null
            }
            if (linkConsensusHost != null && corroboratedHost == null) {
                // The one state with no other symptom: the links agreed, and nothing acted on it
                // yet because the previous scan did not agree with this one. The very next matching
                // scan, a few seconds away, is what turns this into an acquisition.
                EnforcementStatus.note("read $pkg: links agree on $linkConsensusHost, awaiting a second scan")
            }
            val linkHost = corroboratedHost?.let { WebMatcher.ParsedUrl(host = it, path = "") }

            val barNote = if (chooser) " bar=chooser(${(bar as AddressBar.Read.Chooser).offered})" else ""
            trace(
                "read($pkg): feed=$feedKey url=${url?.let { "\"$it\"" }}$barNote " +
                    "links=${linkUrls.size} corroboratedHost=$corroboratedHost descriptions=${descriptions.size}",
            )
            if (chooser) EnforcementStatus.note("read $pkg: several urls on offer, not a page (target held)")

            val result = classifyInspection(
                pkg, feedKey, url, websiteMatchers(), linkHost, descriptions, title, signatureLinkUrls,
                signatureScanned = wantSignature && scanned,
            )
            val signature = if (wantSignature) {
                PageSignature.keyOf(descriptions, title, signatureLinkUrls, parsedBar?.host)
            } else {
                null
            }
            EnforcementStatus.noteRead(
                pkg, url, feedKey, result, linkUrls.size, signature,
                durationMs = (System.nanoTime() - readStartNs) / 1_000_000,
            )

            // Last resort, after everything above -- including link consensus and the page
            // signature -- has already had its turn and come up with nothing. The reveal never
            // produces a target of its own; it only asks the browser to put the bar back so the
            // *next* read has a chance to.
            if (result == Inspected.Unreadable) {
                maybeAttemptReveal(pkg, root, browserSpec, url, chooser, now)
            }
            return result
        } finally {
            root.recycleCompat()
        }
    }

    /**
     * Taps [BrowserSpec.revealBarId], if [shouldAttemptReveal] says it is worth it, to try to bring
     * a scrolled-away address bar back for the next read.
     *
     * This is a real click on the user's screen, not a read -- the one exception to this whole
     * inspector reading and never acting. Every failure mode degrades silently on purpose: no id
     * for this browser, no node found, or [AccessibilityNodeInfo.ACTION_CLICK] refused all fall
     * through to `return` with no audit event and no retry this visit, the same as a stale id in
     * [FeedRegistry] or [BrowserRegistry] degrades to "not blocked" rather than a crash.
     */
    private fun maybeAttemptReveal(
        pkg: String,
        root: AccessibilityNodeInfo,
        browserSpec: BrowserSpec?,
        url: String?,
        chooser: Boolean,
        nowMs: Long,
    ) {
        val revealId = browserSpec?.revealBarId
        if (!shouldAttemptReveal(
                enabled = revealEnabled(),
                revealId = revealId,
                url = url,
                chooser = chooser,
                heldTarget = currentTargetId(),
                matcherCount = websiteMatchers().size,
                lastAttemptMs = revealLastAttemptMs[pkg] ?: 0L,
                attemptedThisVisit = revealAttemptedThisVisit(),
                nowMs = nowMs,
            )
        ) {
            return
        }
        // Counted against the cooldown and the once-per-visit ceiling regardless of what happens
        // below -- a browser whose reveal control never actually works must not be clicked once a
        // minute forever, and a missing node this time is not evidence it will appear next time.
        revealLastAttemptMs[pkg] = nowMs
        markRevealAttempted()
        val nodes = revealId?.let { root.findAccessibilityNodeInfosByViewId(it) }
        try {
            // Noted whatever happens, including nothing. "The reveal was tried and the control
            // refused" and "the reveal never ran" look identical from inside the app, and telling
            // those apart is the entire job of the Right now panel.
            val clicked = nodes?.firstOrNull()
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            EnforcementStatus.note(
                if (clicked) "reveal tapped for $pkg" else "reveal for $pkg found nothing to tap",
            )
            if (clicked) handler.postDelayed({ requestInspect(pkg) }, REVEAL_FOLLOWUP_DELAY_MS)
        } finally {
            nodes?.forEach { it.recycleCompat() }
        }
    }

    /** What one node-tree walk found, feeding both [LinkConsensus] and [PageSignature]. */
    private data class PageSignals(
        val linkUrls: List<String>,
        val descriptions: List<String>,
        val title: String?,
    )

    /**
     * Collects link destinations and, when [collectSignature] is set, the page's `contentDescription`
     * text and its `WebView` title -- one depth-first, bounded walk for both.
     *
     * Depth-first and not breadth-first because a page's own navigation sits at the start of its
     * DOM, so document order reaches links soonest -- and because breadth-first would sweep a
     * whole level of a feed before descending into anything.
     *
     * [MAX_LINK_SCAN_NODES] is the only thing that bounds this walk; [MAX_LINK_URLS_RETAINED] only
     * bounds how many of the links found along the way are kept. There used to be a second, cheaper
     * stopping point for a links-only walk, but nothing ever actually calls this with
     * [collectSignature] false -- the caller only wants links when the bar is unreadable, and an
     * unreadable bar makes it want the signature too, so every walk that runs for links already
     * pays the full node budget. Capping the retained list separately, rather than stopping the walk
     * early, is what stopped that cap from quietly discarding evidence the walk had already found:
     * a search-results page can have a dozen results before its own navigation links appear, and a
     * link cap that doubled as a scan limit meant [com.ezhou.rotblock.browser.LinkConsensus] never
     * even saw them.
     *
     * Returns null when [MIN_PAGE_SCAN_INTERVAL_MS] has not elapsed, rather than an empty result.
     * The two are not the same thing and the caller cannot tell them apart from empty lists: one
     * says this page offered nothing, the other says nobody looked. Corroborating a link vote turns
     * on that distinction -- see the caller -- and returning empty for both is what would let two
     * throttled reads erase the verdict of the real scan sitting between them.
     *
     * Every node fetched here is recycled on the way out. The root is not: it belongs to the
     * caller, which recycles it in its own `finally`.
     */
    private fun harvestPageSignals(root: AccessibilityNodeInfo, collectSignature: Boolean): PageSignals? {
        val now = System.currentTimeMillis()
        if (now - lastPageScanMs < MIN_PAGE_SCAN_INTERVAL_MS) return null
        lastPageScanMs = now

        val urls = ArrayList<String>(MAX_LINK_URLS_RETAINED)
        val descriptions = ArrayList<String>(MAX_DESCRIPTION_SAMPLES)
        var title: String? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        pushChildren(root, stack)
        var visited = 0
        try {
            while (stack.isNotEmpty() && visited < MAX_LINK_SCAN_NODES) {
                val node = stack.removeLast()
                visited++
                try {
                    // getCharSequence, not getString: Bundle.getString returns null when the value
                    // was stored as a CharSequence, and Chromium does exactly that in some
                    // versions. A String is a CharSequence, so this reads both.
                    runCatching { node.extras?.getCharSequence(LinkConsensus.EXTRA_TARGET_URL) }
                        .getOrNull()
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { if (urls.size < MAX_LINK_URLS_RETAINED) urls.add(it) }
                    if (collectSignature) {
                        if (descriptions.size < MAX_DESCRIPTION_SAMPLES) {
                            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                                ?.let { descriptions.add(it) }
                        }
                        if (title == null && node.className?.toString() == "android.webkit.WebView") {
                            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { title = it }
                        }
                    }
                    pushChildren(node, stack)
                } finally {
                    node.recycleCompat()
                }
            }
        } finally {
            stack.forEach { it.recycleCompat() }
        }
        trace(
            "harvest: visited=$visited urls=${urls.size} descriptions=${descriptions.size} " +
                "title=${title != null} unexplored=${stack.size}",
        )
        return PageSignals(urls, descriptions, title)
    }

    /** Pushes children in reverse so the stack pops them in document order. */
    private fun pushChildren(node: AccessibilityNodeInfo, stack: ArrayDeque<AccessibilityNodeInfo>) {
        for (i in node.childCount - 1 downTo 0) {
            runCatching { node.getChild(i) }.getOrNull()?.let { stack.addLast(it) }
        }
    }
}

/** recycle() is a deprecated no-op from API 33; still worth calling below that. */
internal fun AccessibilityNodeInfo.recycleCompat() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        runCatching { recycle() }
    }
}

internal fun AccessibilityNodeInfo.hasViewId(viewId: String): Boolean {
    val nodes = findAccessibilityNodeInfosByViewId(viewId) ?: return false
    val found = nodes.isNotEmpty()
    nodes.forEach { it.recycleCompat() }
    return found
}

/**
 * Reads the address bar, trying this browser's view ids in order.
 *
 * A [AddressBar.Read.Chooser] ends the whole read rather than falling through to the next id:
 * the ids are alternative spellings of the same bar, so once a screen has been identified as a
 * list of URLs, a later id finding a lone node on it is not a bar either.
 */
internal fun AccessibilityNodeInfo.readAddressBar(spec: BrowserSpec): AddressBar.Read {
    for (viewId in spec.urlBarIds) {
        when (val read = readAddressBarId(viewId)) {
            is AddressBar.Read.Text -> return read
            is AddressBar.Read.Chooser -> return read
            AddressBar.Read.Absent -> Unit
        }
    }
    return AddressBar.Read.Absent
}

internal fun AccessibilityNodeInfo.readAddressBarId(viewId: String): AddressBar.Read {
    val nodes = findAccessibilityNodeInfosByViewId(viewId) ?: return AddressBar.Read.Absent
    try {
        return AddressBar.read(
            nodes.map { node ->
                AddressBar.Candidate(
                    text = node.text?.toString() ?: node.contentDescription?.toString(),
                    visible = runCatching { node.isVisibleToUser }.getOrDefault(true),
                )
            },
        )
    } finally {
        nodes.forEach { it.recycleCompat() }
    }
}

/**
 * Re-finds the address-bar node itself, for [typeEscape] rather than a read.
 *
 * A node from the block screen is stale by the time an escape is tapped, so this looks again
 * from a fresh [rootInActiveWindow] instead of holding one across that gap.
 *
 * Which node counts as *the* bar is [AddressBar.read]'s decision and is asked here rather than
 * re-derived, so this cannot drift away from what a read would have concluded on the same
 * screen. A [AddressBar.Read.Chooser] ends the search rather than falling through to the next
 * id, for the same reason it does there: a screen offering a list of URLs has no bar on it to
 * type into.
 */
internal fun AccessibilityNodeInfo.findAddressBarNode(spec: BrowserSpec): AccessibilityNodeInfo? {
    for (viewId in spec.urlBarIds) {
        val nodes = findAccessibilityNodeInfosByViewId(viewId) ?: continue
        val candidates = nodes.map { node ->
            AddressBar.Candidate(
                text = node.text?.toString() ?: node.contentDescription?.toString(),
                visible = runCatching { node.isVisibleToUser }.getOrDefault(true),
            )
        }
        val read = AddressBar.read(candidates)
        // Two nodes can carry the same text, and a visible one is the one read() resolved to.
        val winner = (read as? AddressBar.Read.Text)?.let { text ->
            nodes.indices
                .filter { candidates[it].text == text.value }
                .minByOrNull { if (candidates[it].visible) 0 else 1 }
                ?.let { nodes[it] }
        }
        nodes.filter { it !== winner }.forEach { it.recycleCompat() }
        if (winner != null) return winner
        if (read is AddressBar.Read.Chooser) return null
    }
    return null
}
