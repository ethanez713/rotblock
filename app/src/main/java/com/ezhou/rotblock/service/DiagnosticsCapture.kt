package com.ezhou.rotblock.service

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.block.Interstitial
import com.ezhou.rotblock.browser.AddressBar
import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.browser.LinkConsensus
import com.ezhou.rotblock.browser.PageSignature
import com.ezhou.rotblock.browser.WebMatcher
import com.ezhou.rotblock.diag.CaptureInput
import com.ezhou.rotblock.diag.CaptureStore
import com.ezhou.rotblock.diag.NodeDumpSnapshot
import com.ezhou.rotblock.diag.NodeSnapshot
import com.ezhou.rotblock.diag.PauseBudgetSnapshot
import com.ezhou.rotblock.diag.RequirementSnapshot
import com.ezhou.rotblock.diag.ServiceInternals
import com.ezhou.rotblock.diag.SessionRow
import com.ezhou.rotblock.diag.buildCapture
import com.ezhou.rotblock.diag.gatherAppInfo
import com.ezhou.rotblock.diag.gatherOutageSnapshot
import com.ezhou.rotblock.diag.gatherPhoneSnapshot
import com.ezhou.rotblock.diag.gatherWorkoutSnapshot
import com.ezhou.rotblock.diag.toAuditEventSnapshot
import com.ezhou.rotblock.diag.toDecisionSnapshot
import com.ezhou.rotblock.diag.toPolicySnapshot
import com.ezhou.rotblock.pause.BudgetStatus
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyEngine
import com.ezhou.rotblock.quota.UsageSession
import com.ezhou.rotblock.setup.gatherSetupFacts
import com.ezhou.rotblock.setup.requirementsFor
import com.ezhou.rotblock.workout.WorkoutMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How long a diagnostic capture keeps polling for a window it can read before giving up.
 *
 * The tap that triggers a capture happens from the Quick Settings shade, so `rootInActiveWindow`
 * belongs to systemui at the moment of the request -- there is no event for "the shade closed", so
 * this polls for one instead. That is normally forbidden by `CLAUDE.md`'s no-repeating-callbacks
 * rule; it is allowed here because it is user-initiated (one QS tile tap), deadline-bounded by this
 * constant, only running while the screen is on (nothing else could have opened the shade), fires
 * once per capture rather than on a schedule, and exists specifically to catch a state -- the shade
 * closing back onto whatever was blocked -- that by definition emits no accessibility event of its
 * own. `requestInspect` cannot stand in for it: that channel only ever queues a read for a browser
 * or feed package, and a capture has to work for a plain app target too.
 */
private const val DIAG_DUMP_DEADLINE_MS = 15_000L
private const val DIAG_DUMP_POLL_MS = 1_000L

/** Bounds on the diagnostic tree dump -- generous, since this runs once per capture, not per event. */
private const val DIAG_DUMP_MAX_NODES = 1_500
private const val DIAG_DUMP_MAX_DEPTH = 40

internal class CaptureLive(
    val currentPackage: String?,
    val currentTargetId: String?,
    val ticking: Boolean,
    val tickDueAtMs: Long,
    val frictionServedFor: String?,
    val targetConfirmed: Boolean,
    val visitMemory: Map<String, String>,
    val searchPageMemory: Map<String, Long>,
    val linkCorroborationPending: Map<String, Pair<String, Long>>,
    val revealAttemptedThisVisit: Boolean,
    val hudDismissedThisSession: Boolean,
    val policiesByTarget: Map<String, Policy>,
    val websiteMatchers: List<String>,
    val dayStartHour: Int,
    val openTargetId: String?,
    val pausedUntilMs: Long,
    val pausedTargets: Map<String, Long>,
)

internal class DiagnosticsCapture(
    private val dispatch: ServiceDispatch,
    private val serviceGuard: ServiceGuard,
    private val audit: () -> AuditLog,
    private val actions: AccessibilityActions,
    private val live: () -> CaptureLive,
    private val accounting: CaptureAccounting,
) {
    private val context: Context get() = dispatch.context
    private val scope: CoroutineScope get() = dispatch.scope
    private val handler: Handler get() = dispatch.handler
    private fun guard(where: String, block: () -> Unit) = serviceGuard.guard(where, block)
    private fun auditError(where: String, error: Throwable) = audit().recordError(where, error)
    private fun rootInActiveWindow() = actions.rootInActiveWindow()
    private fun ourPackage() = context.packageName
    private fun sessionsForTarget(targetId: String, nowMs: Long) = accounting.sessionsForTarget(targetId, nowMs)
    private fun sessionsForPolicy(policy: Policy, targetId: String, nowMs: Long) =
        accounting.sessionsForPolicy(policy, targetId, nowMs)
    private fun policiesCovering(targetId: String) = accounting.policiesCovering(targetId)
    private fun pauseUntil(targetId: String) = accounting.pauseUntil(targetId)
    private suspend fun pauseBudget(nowMs: Long) = accounting.pauseBudget(nowMs)


    /**
     * The capture that has been requested but not yet written, so [teardown] can flush it rather
     * than let [handler]'s cancelled poll drop it. Set as soon as the request is gathered, before
     * the poll -- or even the suspending [withCaptureFacts] read -- exists, because a teardown
     * landing in that gap must not lose the capture either. Main thread only, like the rest of this
     * service's mutable state.
     */
    private var captureInFlight: CaptureInput? = null

    /**
     * Starts a diagnostic capture: everything that does not need the node tree is gathered here,
     * on the main thread, from live service state; everything that needs a suspending DataStore or
     * Room read is filled in by [withCaptureFacts]; the node dump is last, because it is the one
     * part that has to wait for the shade to close. See [com.ezhou.rotblock.diag.Diagnostics].
     */
    fun startDiagnosticsCapture(requestedAtMs: Long) {
        val basics = gatherCaptureBasics(requestedAtMs)
        // Held from here, not from when the poll is first posted: the withCaptureFacts suspend
        // below can itself outlive the service, and a capture lost in that gap is just as gone as
        // one lost to a cancelled poll.
        captureInFlight = basics
        scope.launch {
            val enriched = runCatching { withCaptureFacts(basics, requestedAtMs) }.getOrDefault(basics)
            handler.post {
                guard("diagnosticsNodeDump") {
                    captureInFlight = enriched
                    attemptNodeDump(enriched, requestedAtMs + DIAG_DUMP_DEADLINE_MS)
                }
            }
        }
    }

    private fun gatherCaptureBasics(now: Long): CaptureInput {
        val state = live()
        val appInfo = gatherAppInfo(context)
        val targetId = state.currentTargetId
        val sessions = targetId?.let { sessionsForTarget(it, now) }.orEmpty()
        val covering = targetId?.let { policiesCovering(it) }.orEmpty()
        // evaluateAll, not evaluate: the same question evaluateAndEnforce asked, over the same
        // rules. A capture that folded only the target's own rule would show a website allowed
        // while the phone in the user's hand was blocked by a group -- a capture of a different
        // phone, which is the one thing this file may not produce.
        val verdict = targetId?.let { id ->
            PolicyEngine.evaluateAll(
                covering,
                { sessionsForPolicy(it, id, now) },
                pauseUntil(id),
                WorkoutMode.overrideUntilMs(state.currentPackage, now),
                now,
                state.dayStartHour,
            )
        }
        val decision = verdict?.let { toDecisionSnapshot(it.decision) }
        return CaptureInput(
            capturedAtMs = now,
            processUptimeMs = now - Rotblock.processStartedAtMs,
            versionName = appInfo.first,
            versionCode = appInfo.second,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            osRelease = Build.VERSION.RELEASE,
            enforcement = EnforcementStatus.snapshot(),
            service = ServiceInternals(
                tickerArmed = state.ticking,
                tickerDueAtMs = if (state.ticking) state.tickDueAtMs else null,
                frictionServedFor = state.frictionServedFor,
                targetConfidenceConfirmed = state.targetConfirmed,
                visitMemory = state.visitMemory,
                searchPageMemory = state.searchPageMemory,
                linkCorroborationPending = state.linkCorroborationPending,
                revealAttemptedThisVisit = state.revealAttemptedThisVisit,
                hudDismissedThisSession = state.hudDismissedThisSession,
                enabledTargets = state.policiesByTarget.values.filter { it.enabled }.map { it.id },
                websiteMatchers = state.websiteMatchers,
                coveringPolicyIds = covering.map { it.id },
                governingPolicyId = verdict?.governing?.id,
            ),
            interstitialDepth = Interstitial.currentDepth,
            interstitialShowing = Interstitial.isShowing(now),
            openTargetId = state.openTargetId,
            currentDecision = decision,
            currentSessions = sessions.map { SessionRow(it.startMs, it.endMs) },
            pausedUntilMs = state.pausedUntilMs,
            pausedTargets = state.pausedTargets,
            // Filled in by withCaptureFacts, which needs a suspending read this main-thread pass
            // cannot do.
            pauseBudget = null,
            workout = gatherWorkoutSnapshot(now),
            requirements = emptyList(),
            outage = gatherOutageSnapshot(context),
            phone = gatherPhoneSnapshot(context),
            policies = state.policiesByTarget.values.map { toPolicySnapshot(it) },
            auditEvents = emptyList(),
            nodeDump = null,
        )
    }

    private suspend fun withCaptureFacts(basics: CaptureInput, now: Long): CaptureInput {
        val settings = Rotblock.settings(context).current()
        val policies = live().policiesByTarget.values.toList()
        val requirements = requirementsFor(gatherSetupFacts(context, settings, policies, now)).map {
            RequirementSnapshot(it.id.name, it.status.name, it.need.name)
        }
        val budget = runCatching { pauseBudget(now) }.getOrNull()?.let {
            PauseBudgetSnapshot(it.pausesUsed, it.minutesUsed, it.maxPauses, it.maxMinutes)
        }
        val events = runCatching { Rotblock.database(context).auditDao().recent(50) }
            .getOrDefault(emptyList())
            .map { toAuditEventSnapshot(it) }
        return basics.copy(requirements = requirements, pauseBudget = budget, auditEvents = events)
    }

    /**
     * Polls for a window this capture is allowed to read, once every [DIAG_DUMP_POLL_MS] until
     * [deadlineMs]. See [DIAG_DUMP_DEADLINE_MS] for why this is the one repeating callback this app
     * adds.
     */
    private fun attemptNodeDump(input: CaptureInput, deadlineMs: Long) {
        val pkg = live().currentPackage
        val root = rootInActiveWindow()
        val rootPkg = root?.packageName?.toString()
        // A null currentPackage, or a window that disagrees with it, is not a reason to refuse --
        // both are among the actual failures a capture gets taken to investigate, and the ones
        // where a tree dump is worth most. Refusing there would have made the tool blindest in
        // exactly the case it exists for. See canDumpWindow and dumpStatus for the rule and how the
        // disagreement still reaches the reader instead of being recorded as if it agreed.
        if (canDumpWindow(rootPkg, ourPackage())) {
            val dump = try {
                // Losing the whole capture because the walk threw would be the worst possible
                // failure for a diagnostic: the user taps the tile, nothing appears, and the state
                // they were trying to preserve is gone. Record the failure and write the rest.
                runCatching { buildNodeDump(root!!, rootPkg!!) }
                    .getOrElse { emptyNodeDump("tree walk failed: ${it.javaClass.simpleName}", rootPkg) }
            } finally {
                root?.recycleCompat()
            }
            finishDiagnosticsCapture(
                input.copy(nodeDump = dump.copy(status = dumpStatus(dump.status, pkg, rootPkg!!))),
            )
            return
        }
        root?.recycleCompat()
        val now = System.currentTimeMillis()
        if (now >= deadlineMs) {
            finishDiagnosticsCapture(
                input.copy(nodeDump = emptyNodeDump("no readable window within 15s", pkg)),
            )
        } else {
            handler.postDelayed(
                { guard("diagnosticsNodeDump") { attemptNodeDump(input, deadlineMs) } },
                DIAG_DUMP_POLL_MS,
            )
        }
    }

    /** A dump that carries only why there is no tree, so [status] is never the missing field. */
    private fun emptyNodeDump(status: String, pkg: String?) = NodeDumpSnapshot(
        status = status,
        packageName = pkg,
        nodeCount = 0,
        truncated = false,
        nodes = emptyList(),
        addressBar = null,
        linkUrls = emptyList(),
        linkDescriptions = emptyList(),
        linkConsensusHost = null,
        pageSignatureKey = null,
        classification = null,
    )

    private fun finishDiagnosticsCapture(input: CaptureInput) {
        // Cleared here, before the write is even dispatched, so a teardown landing after this point
        // never finds the capture still in flight and writes it a second time.
        captureInFlight = null
        scope.launch {
            runCatching {
                val json = buildCapture(input)
                CaptureStore(CaptureStore.directoryFor(context))
                    .write(input.capturedAtMs, json)
            }.onFailure { auditError("diagnosticsCapture", it) }
        }
    }

    /**
     * One depth-first walk of the tree a capture found readable, bounded by [DIAG_DUMP_MAX_NODES]
     * and [DIAG_DUMP_MAX_DEPTH] -- generous next to [MAX_LINK_SCAN_NODES], because this runs once
     * per capture rather than on every scroll event. Collects the same link-target and
     * content-description evidence [harvestPageSignals] does, without that function's throttle:
     * a capture has to look regardless of when the inspector coroutine last scanned.
     *
     * Alongside the raw tree this records what rotblock's own machinery makes of it -- the address
     * bar read, the link consensus, the page signature, and [classifyInspection]'s verdict -- so a
     * tree that looks fine next to a wrong conclusion is distinguishable from a tree with nothing
     * on it at all.
     */
    private fun buildNodeDump(root: AccessibilityNodeInfo, pkg: String): NodeDumpSnapshot {
        val nodes = ArrayList<NodeSnapshot>()
        val linkUrls = ArrayList<String>()
        val descriptions = ArrayList<String>()
        var title: String? = null
        var truncated = false

        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (i in root.childCount - 1 downTo 0) {
            runCatching { root.getChild(i) }.getOrNull()?.let { stack.addLast(it to 1) }
        }
        var visited = 0
        try {
            while (stack.isNotEmpty()) {
                if (visited >= DIAG_DUMP_MAX_NODES) {
                    truncated = true
                    break
                }
                val (node, depth) = stack.removeLast()
                visited++
                try {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    val targetUrl = runCatching {
                        node.extras?.getCharSequence(LinkConsensus.EXTRA_TARGET_URL)
                    }.getOrNull()?.toString()
                    nodes.add(
                        NodeSnapshot(
                            depth = depth,
                            className = node.className?.toString(),
                            viewId = runCatching { node.viewIdResourceName }.getOrNull(),
                            text = node.text?.toString()?.take(200),
                            contentDescription = node.contentDescription?.toString()?.take(200),
                            bounds = bounds.toShortString(),
                            visibleToUser = runCatching { node.isVisibleToUser }.getOrDefault(false),
                            clickable = node.isClickable,
                            targetUrl = targetUrl,
                        ),
                    )
                    targetUrl?.takeIf { it.isNotBlank() }?.let { linkUrls.add(it) }
                    node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { descriptions.add(it) }
                    if (title == null && node.className?.toString() == "android.webkit.WebView") {
                        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { title = it }
                    }
                    if (depth < DIAG_DUMP_MAX_DEPTH) {
                        for (i in node.childCount - 1 downTo 0) {
                            runCatching { node.getChild(i) }.getOrNull()?.let { stack.addLast(it to depth + 1) }
                        }
                    }
                } finally {
                    node.recycleCompat()
                }
            }
        } finally {
            stack.forEach { it.first.recycleCompat() }
        }

        val browserSpec = BrowserRegistry.specFor(pkg)
        val barRead = browserSpec?.let { root.readAddressBar(it) }
        val addressBarText = when (barRead) {
            is AddressBar.Read.Text -> "Text(\"${barRead.value}\")"
            is AddressBar.Read.Chooser -> "Chooser(${barRead.offered})"
            AddressBar.Read.Absent, null -> "Absent"
        }
        val parsedBar = (barRead as? AddressBar.Read.Text)?.value?.let { WebMatcher.parse(it) }
        val linkConsensusHost = LinkConsensus.of(linkUrls, title)?.host
        val linkHost = linkConsensusHost?.let { WebMatcher.ParsedUrl(host = it, path = "") }
        val signature = PageSignature.keyOf(descriptions, title, linkUrls, parsedBar?.host)
        val feedKey = FeedRegistry.specsFor(pkg)
            .firstOrNull { spec -> spec.viewIdHints.any { root.hasViewId(it) } }
            ?.key
        val classification = classifyInspection(
            pkg,
            feedKey,
            (barRead as? AddressBar.Read.Text)?.value,
            live().websiteMatchers,
            linkHost,
            descriptions,
            title,
            linkUrls,
        )
        val classificationText = when (classification) {
            is Inspected.Target -> "Target(${classification.targetId})"
            Inspected.AppOnly -> "AppOnly"
            Inspected.Unreadable -> "Unreadable"
        }

        return NodeDumpSnapshot(
            status = "ok",
            packageName = pkg,
            nodeCount = nodes.size,
            truncated = truncated,
            nodes = nodes,
            addressBar = addressBarText,
            linkUrls = linkUrls,
            linkDescriptions = descriptions,
            linkConsensusHost = linkConsensusHost,
            pageSignatureKey = signature,
            classification = classificationText,
        )
    }

    fun abandonInFlight(currentPackage: String?) {
        // A capture still waiting on the node-dump poll below is one this teardown is about to
        // cancel outright -- and teardown is frequently followed by process death, which is exactly
        // the 15s window a capture taken right before a kill spends waiting. Written synchronously,
        // on this thread, for the same reason the outage timestamp above uses commit() rather than
        // apply(): a write dispatched to Dispatchers.IO here is precisely the write that will not
        // finish before the process is gone.
        captureInFlight?.let { pending ->
            runCatching {
                val json = buildCapture(
                    pending.copy(
                        nodeDump = emptyNodeDump(
                            "service torn down while waiting for a readable window",
                            currentPackage,
                        ),
                    ),
                )
                CaptureStore(CaptureStore.directoryFor(context)).write(pending.capturedAtMs, json)
            }
            captureInFlight = null
        }
    }
}
