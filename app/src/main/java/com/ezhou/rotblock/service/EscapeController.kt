package com.ezhou.rotblock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import com.ezhou.rotblock.block.Escape
import com.ezhou.rotblock.block.escapeLabelFor
import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.browser.escapeIntent
import com.ezhou.rotblock.browser.typeEscape
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind

/**
 * How long an escape gets to prove it worked before the block comes back.
 *
 * Long enough for the app to navigate and for a read to land -- [INSPECT_DEBOUNCE_MS] plus
 * [MIN_INSPECT_INTERVAL_MS] plus room for the page -- and short enough that a failed escape is a
 * blink rather than a browsing session. Being early only costs a block that the next read lifts
 * again; being late costs unmetered time on the target.
 */
private const val ESCAPE_VERIFY_MS = 3_000L

/**
 * How many [ESCAPE_VERIFY_MS] windows [verifyEscaped] will wait for a read that actually inspected
 * the browser's own window before it gives up and re-blocks with nothing readable at all.
 *
 * Three windows is nine seconds of worst-case unmetered time on a page that, in every observed
 * case, had already been left -- weighed against a false re-block the user cannot get past by
 * doing anything right. [ESCAPE_VERIFY_MS] already makes this trade in the other direction (early
 * costs a block the next read lifts again; late costs unmetered time); this is the same trade,
 * bounded, for the case that check never anticipated: nobody looking at all.
 */
private const val ESCAPE_VERIFY_ATTEMPTS = 3

class EscapeController(
    private val handler: Handler,
    private val serviceGuard: ServiceGuard,
    private val belief: ForegroundBelief,
    private val actions: AccessibilityActions,
    private val visits: VisitMemoryOps,
    private val confidence: HeldConfidence,
    private val followUp: EscapeFollowUp,
) {
    private fun guard(where: String, block: () -> Unit) = serviceGuard.guard(where, block)
    private fun trace(message: String) = serviceGuard.trace(message)
    private fun currentPackage() = belief.currentPackage
    private fun currentTargetId() = belief.currentTargetId
    private fun lastWindowReadAtMs() = belief.lastWindowReadAtMs
    private fun requestInspect(pkg: String) = followUp.requestInspect(pkg)
    private fun evaluateAndEnforce(targetId: String, nowMs: Long) = followUp.evaluateAndEnforce(targetId, nowMs)
    private fun forgetVisit(pkg: String) = visits.forget(pkg)
    private fun clearConfidence() = confidence.clear()
    private fun persistVisit() = followUp.persistVisit()
    private fun rootInActiveWindow() = actions.rootInActiveWindow()
    private fun performGlobalAction(action: Int) = actions.performGlobalAction(action)
    private fun startActivity(intent: Intent) = actions.startActivity(intent)


    /**
     * How to get out of a block that covers more than it forbids.
     *
     * An app-level block needs nothing: the app *is* the target, so going home is a complete
     * answer. A website or feed block is different -- it covers a browser or an app you are still
     * allowed to use, including the address bar or the back gesture you would use to leave the
     * blocked part. Reopening restores the same page and blocks again, and the only remaining way
     * out is disabling the policy, which is the failure this whole app exists to prevent.
     */
    fun escapeFor(targetId: String): Escape? {
        val kind = Policy.kindOf(targetId) ?: return null
        val label = escapeLabelFor(kind) ?: return null
        val leave: () -> Unit = when (kind) {
            // Unreachable: escapeLabelFor returns null for both and we have already left.
            TargetKind.APP, TargetKind.GROUP -> return null

            // Backing out of a short-form player lands on the rest of the app, and if the app has
            // nothing to go back to it closes -- reopening then starts at its normal entry point
            // rather than back inside the feed. Either way you are out.
            TargetKind.FEED -> {
                { guard("escapeFeed") { performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } }
            }

            // Point the browser somewhere that is not the blocked site. Back is not enough on its
            // own: a restored tab can have no history, in which case back closes the browser and
            // reopening lands on the same blocked page again. Loading a URL changes what the
            // active tab *is*, which survives the app being closed and reopened.
            //
            // Typing into the bar is tried first, since it is what actually works against a
            // browser that rejects the escape intent outright (Samsung Internet: "Invalid
            // address", page unchanged). The intent stays as the fallback for anything below API
            // 30 or with no readable bar, and global back is the last resort if both refuse.
            TargetKind.WEBSITE -> {
                {
                    guard("escapeSite") {
                        val browser = currentPackage()
                        val typed = browser != null &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            typeEscapeInBrowser(browser)
                        val navigated = typed || (browser != null && runCatching {
                            startActivity(escapeIntent(browser))
                        }.isSuccess)
                        if (!navigated) performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                }
            }
        }
        return Escape(label) {
            leave()
            noteEscaped(targetId)
            verifyEscaped(targetId, System.currentTimeMillis())
        }
    }

    /**
     * An escape just moved the browser off [targetId], or is about to -- the page [visits] still
     * remembers is stale evidence, not merely unconfirmed.
     *
     * Positively forgetting rather than leaving the memory alone: the whole reason [VisitMemory]
     * exists is so re-entering a browser resumes what a read last found there, and without this the
     * very next re-entry would resume the site the user just paid to leave. [currentTargetId] is
     * deliberately left untouched -- [verifyEscaped] reads it to decide whether the escape actually
     * took, and that proof must not be disturbed by the same call that triggers it.
     */
    fun noteEscaped(targetId: String) {
        currentPackage()?.let { forgetVisit(it) }
        // Whatever is still held is now an assumption again -- it may keep being enforced, since a
        // page you scroll does not clear a block, but it may not throw up a countdown for a page
        // you have just been told you left.
        clearConfidence()
        persistVisit()
    }

    /**
     * Checks that the escape actually escaped, and re-blocks if a read that actually inspected the
     * browser's own window says it did not.
     *
     * The escape hides the block screen and then *asks* an app to go somewhere else. Nothing
     * guarantees the app agrees: Samsung Internet answered an `about:blank` intent with an
     * "Invalid address" toast and stayed on the blocked page, `startActivity` returned normally,
     * and the block was already gone. From there the only thing that could have re-blocked was the
     * next successful address-bar read, and a page you scroll does not produce one -- so a tap on
     * "Leave this site" bought unlimited browsing on the site that had just run out of time.
     *
     * A request that hides a block must therefore carry its own proof of success -- but "no read
     * landed" is not proof of anything. A device capture caught the old version of this re-blocking
     * a browser that had already escaped, four times in 24 seconds, because every read in the
     * verify window bounced off a transient `com.android.systemui` window and never got as far as
     * inspecting the browser at all. [verifyEscapeOutcome] tells that apart from an actual refusal
     * using [lastWindowReadAtMs], and [attempt]/[ESCAPE_VERIFY_ATTEMPTS] bound how long "nobody has
     * looked yet" gets to keep meaning that before it is treated as a refusal regardless.
     *
     * [escapedAtMs] is captured once, at the escape, and threaded through every re-armed attempt
     * unchanged -- the question is always "has anything been readable since the escape happened",
     * not "since the last time this fired".
     *
     * Two [requestInspect] calls rather than one: reads are already debounced and rate-limited by
     * the inspector loop, so the second costs at most one more `rootInActiveWindow` read, and it
     * stops a single transient non-browser window from consuming the whole verify budget.
     *
     * Deliberately a delayed post and not a scheduled job: the screen is on and the user is
     * standing right there, so nothing needs to survive the process. If it is lost, the target
     * simply stays blocked.
     */
    fun verifyEscaped(targetId: String, escapedAtMs: Long, attempt: Int = 1) {
        currentPackage()?.let { requestInspect(it) }
        handler.postDelayed({
            guard("verifyEscapeInspect") {
                currentPackage()?.let { requestInspect(it) }
            }
        }, ESCAPE_VERIFY_MS / 2)
        handler.postDelayed({
            guard("verifyEscaped") {
                when (
                    verifyEscapeOutcome(
                        escapedTargetId = targetId,
                        currentTargetId = currentTargetId(),
                        escapedAtMs = escapedAtMs,
                        lastWindowReadAtMs = lastWindowReadAtMs(),
                        attempt = attempt,
                        maxAttempts = ESCAPE_VERIFY_ATTEMPTS,
                    )
                ) {
                    EscapeVerdict.ESCAPED -> return@guard
                    EscapeVerdict.UNCONFIRMED -> {
                        EnforcementStatus.note("escape from $targetId: nothing readable yet, still waiting")
                        verifyEscaped(targetId, escapedAtMs, attempt + 1)
                    }
                    EscapeVerdict.REFUSED -> {
                        trace("escape from $targetId did not take, re-blocking")
                        EnforcementStatus.note("escape from $targetId did not take, re-blocking")
                        evaluateAndEnforce(targetId, System.currentTimeMillis())
                    }
                }
            }
        }, ESCAPE_VERIFY_MS)
    }

    /**
     * Types [BrowserRegistry.ESCAPE_URL] straight into [browser]'s own address bar. This is tried
     * before [escapeIntent] because it is what actually worked against Samsung Internet's
     * rejection of that intent -- see [typeEscape].
     */
    private fun typeEscapeInBrowser(browser: String): Boolean {
        val spec = BrowserRegistry.specFor(browser) ?: return false
        val root = rootInActiveWindow() ?: return false
        try {
            if (root.packageName?.toString() != browser) return false
            val bar = root.findAddressBarNode(spec) ?: return false
            try {
                return typeEscape(bar, BrowserRegistry.ESCAPE_URL)
            } finally {
                bar.recycleCompat()
            }
        } finally {
            root.recycleCompat()
        }
    }
}
