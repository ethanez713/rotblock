package com.ezhou.rotblock.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.block.BlockOverlay
import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.browser.WebMatcher
import com.ezhou.rotblock.pause.PauseController
import com.ezhou.rotblock.policy.Decision
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyEngine
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.UsageLedger
import com.ezhou.rotblock.ui.PauseRequestActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often an in-quota target is re-checked while it is on screen. */
private const val TICK_MS = 1_000L

/** Coalescing window for content-change bursts before the node tree is read. */
private const val INSPECT_DEBOUNCE_MS = 250L

/**
 * The single service that enforces everything.
 *
 * Two rules govern this class, both learned the hard way by every app in this category:
 *
 * 1. **It must never crash.** A dead accessibility service is a silently unblocked phone, which
 *    is worse than no app at all. Every entry point is wrapped in [guard].
 * 2. **Never walk the node tree in [onAccessibilityEvent].** Content-change events arrive at a
 *    furious rate while scrolling; the callback does a set lookup and a conflated channel send,
 *    and all node reading happens on [inspectorJob].
 */
class RotblockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var ledger: UsageLedger
    private lateinit var pause: PauseController
    private lateinit var audit: AuditLog
    private lateinit var overlay: BlockOverlay

    /** Read on every window change, so it stays a plain volatile map lookup. */
    @Volatile private var policiesByTarget: Map<String, Policy> = emptyMap()

    /** Website rule matchers, kept separately because they need scanning rather than lookup. */
    @Volatile private var websiteMatchers: List<String> = emptyList()

    /** Packages whose windows are not "the app you are using": ours, system UI, keyboards. */
    @Volatile private var ignoredPackages: Set<String> = emptySet()

    /** Packages worth reading the node tree for: browsers and apps with in-app feeds. */
    private val inspectablePackages: Set<String> = BrowserRegistry.packages + FeedRegistry.packages

    private val inspectRequests = Channel<String>(Channel.CONFLATED)

    private var currentPackage: String? = null
    private var currentTargetId: String? = null
    private var ticking = false
    private var watchJob: Job? = null
    private var inspectorJob: Job? = null

    /**
     * Screen off is the one way a target leaves the foreground without an accessibility event.
     * Without this, locking the phone on Reddit would keep burning quota until the next window
     * change -- charging you for time the screen was dark.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            guard("screenReceiver") {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        ledger.closeSession(System.currentTimeMillis())
                        stopTicking()
                        overlay.hide()
                    }
                    // Coming back may land on the same app with no new window event, so the
                    // session has to be restarted explicitly.
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> reEvaluateCurrent()
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            guard("tick") {
                val now = System.currentTimeMillis()
                ledger.touch(now)
                pause.noteExpiryIfNeeded(now)
                currentTargetId?.let { evaluateAndEnforce(it, now) }
            }
            if (ticking) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        guard("onServiceConnected") {
            ledger = Rotblock.ledger(this)
            pause = Rotblock.pause(this)
            audit = Rotblock.audit(this)
            overlay = BlockOverlay(this)
            ignoredPackages = buildIgnoredPackages()

            audit.record(AuditType.SERVICE_ENABLED, detail = "Enforcement started")

            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
            )

            watchJob?.cancel()
            watchJob = scope.launch {
                ledger.load(System.currentTimeMillis())
                ledger.prune(System.currentTimeMillis())
                pause.refresh()
                launch {
                    Rotblock.settings(this@RotblockAccessibilityService).settings.collectLatest {
                        pause.refresh()
                        // A pause starting or ending changes what should be on screen right now.
                        handler.post { guard("pauseChanged") { reEvaluateCurrent() } }
                    }
                }
                Rotblock.database(this@RotblockAccessibilityService).policyDao().observeAll()
                    .collectLatest { list ->
                        // Opportunistic apply: a due weakening lands the next time policies are
                        // looked at. Late is fine; early is not.
                        runCatching {
                            Rotblock.changeQueue(this@RotblockAccessibilityService).applyDue()
                        }
                        policiesByTarget = list.associateBy { it.id }
                        websiteMatchers = list
                            .filter { it.enabled && it.kind == TargetKind.WEBSITE }
                            .map { it.matcher }
                        handler.post { guard("policiesChanged") { reEvaluateCurrent() } }
                    }
            }

            inspectorJob?.cancel()
            inspectorJob = scope.launch { runInspector() }
        }
    }

    // region event intake

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        guard("onAccessibilityEvent") {
            event ?: return@guard
            val pkg = event.packageName?.toString() ?: return@guard
            if (pkg in ignoredPackages) return@guard

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (pkg != currentPackage) {
                        onForegroundChanged(pkg, System.currentTimeMillis())
                    } else if (pkg in inspectablePackages) {
                        inspectRequests.trySend(pkg)
                    }
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // Navigating within a browser or scrolling into a feed produces no window
                    // state change, so this is the only signal that the target changed.
                    if (pkg == currentPackage && pkg in inspectablePackages) {
                        inspectRequests.trySend(pkg)
                    }
                }
            }
        }
    }

    private fun onForegroundChanged(pkg: String, nowMs: Long) {
        currentPackage = pkg

        // Whatever was accruing time has stopped.
        ledger.closeSession(nowMs)
        stopTicking()

        applyTarget(appTargetFor(pkg), nowMs)

        if (pkg in inspectablePackages) inspectRequests.trySend(pkg)
    }

    /** Runs on the main thread; [targetId] null means nothing here is governed by a policy. */
    private fun applyTarget(targetId: String?, nowMs: Long) {
        val policy = targetId?.let { policiesByTarget[it] }
        if (policy == null || !policy.enabled) {
            currentTargetId = null
            ledger.closeSession(nowMs)
            stopTicking()
            overlay.hide()
            return
        }
        if (currentTargetId != targetId) {
            ledger.closeSession(nowMs)
            currentTargetId = targetId
        }
        evaluateAndEnforce(targetId, nowMs)
    }

    private fun reEvaluateCurrent() {
        val pkg = currentPackage ?: return
        val now = System.currentTimeMillis()
        applyTarget(currentTargetId ?: appTargetFor(pkg), now)
        if (pkg in inspectablePackages) inspectRequests.trySend(pkg)
    }

    private fun appTargetFor(pkg: String): String = Policy.targetId(TargetKind.APP, pkg)

    // endregion

    // region node inspection (worker thread only)

    /**
     * Reads the node tree off the event callback.
     *
     * The channel is conflated and the loop debounces, so a burst of a hundred content-change
     * events during a scroll collapses into one tree read.
     */
    private suspend fun runInspector() {
        for (pkg in inspectRequests) {
            delay(INSPECT_DEBOUNCE_MS)
            val resolved = try {
                resolveInspectedTarget(pkg)
            } catch (t: Throwable) {
                audit.recordError("inspect", t)
                null
            }
            withContext(Dispatchers.Main) {
                guard("applyInspected") {
                    // Discard if the user has moved on since the read started.
                    if (pkg != currentPackage) return@guard
                    applyTarget(resolved ?: appTargetFor(pkg), System.currentTimeMillis())
                }
            }
        }
    }

    /**
     * The most specific policy target on screen, or null to fall back to the app-level one.
     * Feeds beat websites beat apps: a Shorts rule should win over a YouTube-app rule.
     */
    private fun resolveInspectedTarget(pkg: String): String? {
        val root = rootInActiveWindow ?: return null
        try {
            for (spec in FeedRegistry.specsFor(pkg)) {
                if (spec.viewIdHints.any { root.hasViewId(it) }) {
                    return Policy.targetId(TargetKind.FEED, spec.key)
                }
            }
            val browser = BrowserRegistry.specFor(pkg)
            if (browser != null) {
                val url = browser.urlBarIds.firstNotNullOfOrNull { root.textForViewId(it) }
                val matcher = WebMatcher.firstMatch(url, websiteMatchers)
                if (matcher != null) return Policy.targetId(TargetKind.WEBSITE, matcher)
            }
            return null
        } finally {
            root.recycleCompat()
        }
    }

    private fun AccessibilityNodeInfo.hasViewId(viewId: String): Boolean {
        val nodes = findAccessibilityNodeInfosByViewId(viewId) ?: return false
        val found = nodes.isNotEmpty()
        nodes.forEach { it.recycleCompat() }
        return found
    }

    private fun AccessibilityNodeInfo.textForViewId(viewId: String): String? {
        val nodes = findAccessibilityNodeInfosByViewId(viewId) ?: return null
        try {
            for (node in nodes) {
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                if (!text.isNullOrBlank()) return text
            }
            return null
        } finally {
            nodes.forEach { it.recycleCompat() }
        }
    }

    /** recycle() is a deprecated no-op from API 33; still worth calling below that. */
    private fun AccessibilityNodeInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { recycle() }
        }
    }

    // endregion

    // region enforcement

    private fun evaluateAndEnforce(targetId: String, nowMs: Long) {
        val policy = policiesByTarget[targetId] ?: return
        val sessions = ledger.sessionsFor(targetId, nowMs)
        when (val decision = PolicyEngine.evaluate(policy, sessions, pause.pausedUntilMs, nowMs)) {
            is Decision.Allow -> {
                overlay.hide()
                stopTicking()
                ledger.closeSession(nowMs)
            }

            is Decision.Allowed -> {
                overlay.hide()
                ledger.startSession(targetId, nowMs)
                startTicking()
            }

            is Decision.Blocked -> {
                // Close first: nextRelease assumes no session runs past now, and we should not
                // keep charging for time spent staring at the block screen.
                ledger.closeSession(nowMs)
                stopTicking()
                showBlock(policy, decision)
            }
        }
    }

    private fun showBlock(policy: Policy, decision: Decision.Blocked) {
        if (!overlay.isShowing) {
            audit.record(
                AuditType.BLOCK_SHOWN,
                targetId = policy.id,
                detail = "${policy.label}: ${decision.reason.name.lowercase().replace('_', ' ')}",
            )
        }
        val shown = overlay.show(
            label = policy.label,
            reason = decision.reason,
            liftsAtMs = decision.liftsAtMs,
            onPauseRequested = { launchPauseRequest(policy) },
        )
        if (!shown) {
            // No overlay permission, or the window manager refused. Ejecting the user is blunt,
            // but blocking has to actually block.
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun launchPauseRequest(policy: Policy) {
        guard("launchPauseRequest") {
            overlay.hide()
            startActivity(
                Intent(this, PauseRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(PauseRequestActivity.EXTRA_TARGET_LABEL, policy.label),
            )
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stopTicking() {
        if (!ticking) return
        ticking = false
        handler.removeCallbacks(tick)
    }

    // endregion

    private fun buildIgnoredPackages(): Set<String> {
        val ignored = mutableSetOf(packageName, "com.android.systemui")
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.mapTo(ignored) { it.packageName }
        }
        return ignored
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        guard("onUnbind") { teardown("Accessibility service turned off") }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        guard("onDestroy") { teardown("Accessibility service destroyed") }
        super.onDestroy()
    }

    private fun teardown(reason: String) {
        val now = System.currentTimeMillis()
        stopTicking()
        runCatching { unregisterReceiver(screenReceiver) }
        if (::ledger.isInitialized) ledger.closeSession(now)
        if (::overlay.isInitialized) overlay.hide()
        // Recorded so that turning rotblock off leaves a mark in the log rather than a silent gap.
        if (::audit.isInitialized) audit.record(AuditType.SERVICE_DISABLED, detail = reason)
        inspectorJob?.cancel()
        watchJob?.cancel()
    }

    /**
     * Runs [block], swallowing anything it throws into the audit log.
     *
     * Letting an exception out of an accessibility service callback kills the service, and a dead
     * service means nothing is blocked at all. Losing one event is always the better trade.
     */
    private fun guard(where: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (::audit.isInitialized) audit.recordError(where, t)
            else android.util.Log.e("rotblock", "error in $where before init", t)
        }
    }
}
