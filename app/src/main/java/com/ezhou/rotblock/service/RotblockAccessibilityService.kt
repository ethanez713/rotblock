package com.ezhou.rotblock.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.block.BlockOverlay
import com.ezhou.rotblock.block.Escape
import com.ezhou.rotblock.block.escapeLabelFor
import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.block.Grayscale
import com.ezhou.rotblock.block.formatDuration
import com.ezhou.rotblock.block.Interstitial
import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.browser.WebMatcher
import com.ezhou.rotblock.pause.PauseController
import com.ezhou.rotblock.policy.Decision
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyEngine
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.UsageLedger
import com.ezhou.rotblock.ui.FrictionActivity
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

/**
 * Bounds on how often an on-screen target is re-checked.
 *
 * The tick is scheduled for the *soonest deadline that matters* -- quota running out, or a pause
 * elapsing -- clamped into this range, rather than firing every second regardless. Sitting in a
 * twenty-minute allowance used to cost 1200 wakeups; it now costs about 80, and the deadline is
 * still hit exactly, because the interval is never longer than the time left.
 *
 * The ceiling is not what keeps the ledger flushing -- [evaluateAndEnforce] touches it directly,
 * because the tick legitimately may not fire for its whole interval. See [UsageLedger].
 */
private const val MIN_TICK_MS = 1_000L
private const val MAX_TICK_MS = 15_000L

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
 * How long an escape gets to prove it worked before the block comes back.
 *
 * Long enough for the app to navigate and for a read to land -- [INSPECT_DEBOUNCE_MS] plus
 * [MIN_INSPECT_INTERVAL_MS] plus room for the page -- and short enough that a failed escape is a
 * blink rather than a browsing session. Being early only costs a block that the next read lifts
 * again; being late costs unmetered time on the target.
 */
private const val ESCAPE_VERIFY_MS = 3_000L

/**
 * Enforcement gaps shorter than this are system rebinds, not you switching rotblock off, and are
 * not written to the log. Toggling the service in Settings and coming back takes far longer.
 */
private const val MIN_REPORTABLE_OUTAGE_MS = 30_000L

private const val OUTAGE_PREFS = "rotblock_outage"
private const val KEY_DOWN_SINCE = "down_since"
private const val KEY_DOWN_REASON = "down_reason"
private const val KEY_LAST_ALIVE = "last_alive"

/**
 * The visit in progress, carried across a rebind. See [seedForeground].
 *
 * Not a relaxation and not a deadline — losing these three strings is what *breaks* enforcement,
 * so persisting them is on the same side of the invariant as everything else here.
 */
private const val KEY_VISIT_PACKAGE = "visit_package"
private const val KEY_VISIT_TARGET = "visit_target"
private const val KEY_VISIT_FRICTION_SHOWN = "visit_friction_shown"

/** Attempts to read the already-open window at startup, before giving up until the next event. */
private const val SEED_ATTEMPTS = 5
private const val SEED_RETRY_MS = 200L

/** How often the service records that it was still running. */
private const val HEARTBEAT_MS = 60_000L

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
    private lateinit var grayscale: Grayscale

    /**
     * The target whose friction screen has already been shown for this visit. Cleared whenever
     * the target changes or the screen goes off, so friction is once per visit rather than once
     * per accessibility event.
     */
    private var frictionShownFor: String? = null

    /** Read on every window change, so it stays a plain volatile map lookup. */
    @Volatile private var policiesByTarget: Map<String, Policy> = emptyMap()

    /** Website rule matchers, kept separately because they need scanning rather than lookup. */
    @Volatile private var websiteMatchers: List<String> = emptyList()

    /** Packages whose windows are not "the app you are using": ours, system UI, keyboards. */
    @Volatile private var ignoredPackages: Set<String> = emptySet()

    /** Packages worth reading the node tree for: browsers and apps with in-app feeds. */
    private val inspectablePackages: Set<String> = BrowserRegistry.packages + FeedRegistry.packages

    private val inspectRequests = Channel<String>(Channel.CONFLATED)

    /** Memoised `pkg/class` -> is-an-activity answers for [isActivity]. Main thread only. */
    private val activityCache = HashMap<String, Boolean>()

    /** Last time aliveness was written to disk; throttles [beat] to one write a minute. */
    private var lastBeatMs = 0L

    private var currentPackage: String? = null
    private var currentTargetId: String? = null

    /** Last values written by [persistVisit], so repeat calls do not touch disk. */
    private var persistedPackage: String? = null
    private var persistedTarget: String? = null
    private var persistedFrictionShown: String? = null

    private var ticking = false

    /** When the armed tick is due, so a re-arm can tell whether it would delay it. */
    private var tickDueAtMs = 0L

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
                        frictionShownFor = null
                        // Never leave the phone monochrome behind a locked screen.
                        grayscale.restore()
                    }
                    // Coming back may land on the same app with no new window event, so the
                    // session has to be restarted explicitly.
                    //
                    // USER_PRESENT only, deliberately not SCREEN_ON. The lock screen is system UI,
                    // which is ignored, so `currentPackage` is still whatever was open underneath
                    // it -- resuming on SCREEN_ON charges the target for every glance at the clock
                    // and for however long the phone sits unlocked-but-not-entered. Over-charging
                    // is the one direction the ledger may not err in.
                    Intent.ACTION_USER_PRESENT -> reEvaluateCurrent()
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            var reschedulingHandled = false
            guard("tick") {
                val now = System.currentTimeMillis()
                ledger.touch(now)
                pause.noteExpiryIfNeeded(now)
                val target = currentTargetId
                // evaluateAndEnforce arms the next tick for whatever deadline it found.
                if (target == null) stopTicking() else evaluateAndEnforce(target, now)
                reschedulingHandled = true
            }
            // A swallowed error must not be able to stop the clock: an unnoticed quota deadline
            // is a fail-open, so fall back to the floor interval and try again.
            if (!reschedulingHandled && ticking) handler.postDelayed(this, MIN_TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        guard("onServiceConnected") {
            ledger = Rotblock.ledger(this)
            pause = Rotblock.pause(this)
            audit = Rotblock.audit(this)
            overlay = BlockOverlay(this)
            grayscale = Grayscale(this)
            ignoredPackages = buildIgnoredPackages()
            trace("onServiceConnected: fresh instance, state reset")

            // Our own screens blind every read taken while they are up, and the app underneath
            // resumes before they stop -- so the window-state change that would prompt a re-read
            // arrives too early to see past us. This is the second chance.
            Interstitial.onCleared = {
                handler.post { guard("interstitialCleared") {
                    trace("interstitial cleared, re-evaluating")
                    reEvaluateCurrent()
                } }
            }

            // Undo a greyscale left on by a crash before doing anything else.
            grayscale.restoreOnStart()

            reportPreviousOutage()

            // Isolated: if this throws, the outer guard would swallow it and skip everything
            // below, leaving the service alive but never loading a single policy. Losing
            // screen-state handling is survivable; losing enforcement is not.
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    screenReceiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_USER_PRESENT)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.onFailure { audit.recordError("registerScreenReceiver", it) }

            watchJob?.cancel()
            watchJob = scope.launch {
                ledger.load(System.currentTimeMillis())
                ledger.prune(System.currentTimeMillis())
                pause.refresh()

                // Off the main thread on purpose: this reads the node tree, which the event
                // callback is never allowed to do.
                seedForegroundFromScreen()
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
            beat()
            val pkg = event.packageName?.toString() ?: return@guard
            if (pkg in ignoredPackages) return@guard

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    trace("WSC pkg=$pkg cls=${event.className} current=$currentPackage")
                    if (pkg != currentPackage) {
                        if (isForegroundChange(pkg, event.className?.toString())) {
                            onForegroundChanged(pkg, System.currentTimeMillis())
                        } else {
                            trace("  WSC ignored: not a foreground change")
                        }
                    } else {
                        requestInspect(pkg)
                    }
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // Navigating within a browser or scrolling into a feed produces no window
                    // state change, so this is the only signal that the target changed.
                    if (pkg == currentPackage) requestInspect(pkg)
                }
            }
        }
    }

    /**
     * Whether a window-state change from [pkg] really means "you are now using that app".
     *
     * `TYPE_WINDOW_STATE_CHANGED` fires for far more than app switches: toasts, dialogs, heads-up
     * notifications, and — found the hard way on device — the system's own "rotblock is
     * displaying over other apps" notification, which is posted by package `android`. Treating
     * that as a foreground change made the newly-shown block screen vanish about two seconds
     * after it appeared, because `android` has no policy and so cleared the target.
     *
     * The test is deliberately asymmetric, and the asymmetry is the point:
     *
     * - A package that **has a policy** is always honoured, whatever kind of window it is. Missing
     *   a blocked app is a fail-open, and nothing here may risk that.
     * - A package with **no policy** only clears the current target if the window is a genuine
     *   activity. That is what stops a passing toast from cancelling a block.
     *
     * So a mistake in either direction leaves blocking on rather than off.
     */
    private fun isForegroundChange(pkg: String, className: String?): Boolean {
        if (policiesByTarget.containsKey(appTargetFor(pkg))) return true
        if (pkg in inspectablePackages) return true
        return isActivity(pkg, className)
    }

    /** Resolves `pkg/className` against PackageManager, cached — the lookup is not free. */
    private fun isActivity(pkg: String, className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        val key = "$pkg/$className"
        activityCache[key]?.let { return it }
        val resolved = runCatching {
            packageManager.getActivityInfo(ComponentName(pkg, className), 0)
        }.isSuccess
        // Bounded: window classes are few in practice, but a misbehaving app could churn them.
        if (activityCache.size > 512) activityCache.clear()
        activityCache[key] = resolved
        return resolved
    }

    /** Debug-only trace of why the enforced target changed. Silent in release. */
    private fun trace(msg: String) {
        if (com.ezhou.rotblock.BuildConfig.DEBUG) android.util.Log.d("rotblockFG", msg)
    }

    /**
     * Records that enforcement was running, at most once a minute.
     *
     * Piggy-backs on accessibility events rather than a timer: they arrive whenever the phone is
     * being used, which is exactly when an enforcement gap would matter.
     */
    private fun beat() {
        val now = System.currentTimeMillis()
        if (now - lastBeatMs < HEARTBEAT_MS) return
        lastBeatMs = now
        runCatching {
            getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_ALIVE, now).apply()
        }
    }

    private fun onForegroundChanged(pkg: String, nowMs: Long) {
        trace("foreground -> $pkg")
        currentPackage = pkg

        // Whatever was accruing time has stopped.
        ledger.closeSession(nowMs)
        stopTicking()

        applyTarget(appTargetFor(pkg), nowMs)

        requestInspect(pkg)
    }

    /** Runs on the main thread; [targetId] null means nothing here is governed by a policy. */
    private fun applyTarget(targetId: String?, nowMs: Long) {
        val policy = targetId?.let { policiesByTarget[it] }
        trace("applyTarget($targetId) policy=${policy?.label} enabled=${policy?.enabled} cur=$currentTargetId shownFor=$frictionShownFor")
        if (policy == null || !policy.enabled) {
            if (frictionShownFor != null) trace("  CLEARING frictionShownFor (no policy for $targetId)")
            currentTargetId = null
            frictionShownFor = null
            ledger.closeSession(nowMs)
            stopTicking()
            overlay.hide()
            grayscale.restore()
            persistVisit()
            return
        }
        if (currentTargetId != targetId) {
            trace("  target change $currentTargetId -> $targetId, re-arming friction")
            ledger.closeSession(nowMs)
            currentTargetId = targetId
            frictionShownFor = null
            grayscale.restore()
        }
        evaluateAndEnforce(targetId, nowMs)
        // After enforcement, so a friction screen just shown is part of what gets carried over.
        persistVisit()
    }

    private fun reEvaluateCurrent() {
        val now = System.currentTimeMillis()
        // Cheap, and this runs on unlock and on every settings change, which is the widest net we
        // have for noticing a pause that lapsed while nothing was watching.
        pause.noteExpiryIfNeeded(now)
        val pkg = currentPackage ?: return
        applyTarget(currentTargetId ?: appTargetFor(pkg), now)
        requestInspect(pkg)
    }

    private fun appTargetFor(pkg: String): String = Policy.targetId(TargetKind.APP, pkg)

    // region surviving a rebind

    /**
     * Works out what is already on screen, because a fresh instance knows nothing about it.
     *
     * Android rebinds this service constantly -- on reinstall, on config changes, and whenever
     * another accessibility client attaches or fails to. Each rebind is a brand new instance with
     * every field at its default, and the app that is already open does not fire a window-state
     * change to announce itself. Two things then go wrong, both found on device:
     *
     * 1. `currentPackage` stays null, so `TYPE_WINDOW_CONTENT_CHANGED` -- the *only* signal a
     *    browser gives while you stay inside it -- is discarded for not matching. A browser sitting
     *    on a blocked site is simply never inspected, and nothing is enforced until you happen to
     *    switch apps.
     * 2. When a window-state change finally does arrive, a null `currentPackage` makes it look like
     *    a foreground change into an app with no policy, which clears `frictionShownFor`. The next
     *    read then resolves the website again, that reads as a *target change*, friction re-arms,
     *    and the countdown fires again -- the loop, exactly.
     *
     * So the visit is persisted and restored, but only when the package on screen still matches the
     * one it was recorded against. A rebind that happens while you are somewhere else starts clean.
     */
    private suspend fun seedForegroundFromScreen() {
        repeat(SEED_ATTEMPTS) {
            val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (pkg != null && pkg !in ignoredPackages) {
                withContext(Dispatchers.Main) { guard("seed") { seedForeground(pkg) } }
                return
            }
            // The tree is not always up yet in the first moments after binding.
            delay(SEED_RETRY_MS)
        }
        trace("seed: gave up, no readable active window")
    }

    /** Main thread. [pkg] is the package that genuinely owns the active window. */
    private fun seedForeground(pkg: String) {
        // A real event beat us to it; it knows better than a poll does.
        if (currentPackage != null) return

        currentPackage = pkg
        val prefs = getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_VISIT_PACKAGE, null) == pkg) {
            currentTargetId = prefs.getString(KEY_VISIT_TARGET, null)
            frictionShownFor = prefs.getString(KEY_VISIT_FRICTION_SHOWN, null)
        }
        trace("seed: $pkg target=$currentTargetId shownFor=$frictionShownFor")

        // Enforce the restored target straight away rather than waiting for the read to confirm
        // it. If the page moved on while we were unbound the read corrects it within the second,
        // and a moment of over-blocking is the right way round to be wrong.
        applyTarget(currentTargetId ?: appTargetFor(pkg), System.currentTimeMillis())
        requestInspect(pkg)
    }

    /**
     * Records the visit so the next instance can pick it up. Cheap because it writes only on an
     * actual change -- `applyTarget` runs on every tick and every read.
     */
    private fun persistVisit() {
        if (currentPackage == persistedPackage &&
            currentTargetId == persistedTarget &&
            frictionShownFor == persistedFrictionShown
        ) {
            return
        }
        persistedPackage = currentPackage
        persistedTarget = currentTargetId
        persistedFrictionShown = frictionShownFor
        runCatching {
            getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_VISIT_PACKAGE, currentPackage)
                .putString(KEY_VISIT_TARGET, currentTargetId)
                .putString(KEY_VISIT_FRICTION_SHOWN, frictionShownFor)
                .apply()
        }
    }

    // endregion

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
    private fun requestInspect(pkg: String) {
        if (pkg !in inspectablePackages) return
        inspectRequests.trySend(pkg)
    }

    // endregion

    // region node inspection (worker thread only)

    /**
     * Reads the node tree off the event callback.
     *
     * The channel is conflated and the loop both debounces and rate-limits, so a burst of a hundred
     * content-change events during a scroll collapses into one tree read, and a sustained scroll
     * costs at most one read a second.
     */
    private suspend fun runInspector() {
        var lastReadMs = 0L
        for (pkg in inspectRequests) {
            delay(INSPECT_DEBOUNCE_MS)
            val sinceLastRead = System.currentTimeMillis() - lastReadMs
            if (sinceLastRead < MIN_INSPECT_INTERVAL_MS) delay(MIN_INSPECT_INTERVAL_MS - sinceLastRead)
            lastReadMs = System.currentTimeMillis()

            val resolved = try {
                resolveInspectedTarget(pkg)
            } catch (t: Throwable) {
                audit.recordError("inspect", t)
                Inspected.Unreadable
            }
            withContext(Dispatchers.Main) {
                guard("applyInspected") {
                    trace("inspected $pkg -> $resolved (current=$currentPackage)")
                    // Discard if the user has moved on since the read started.
                    if (pkg != currentPackage) return@guard
                    val targetId = when (resolved) {
                        is Inspected.Target -> resolved.targetId
                        Inspected.AppOnly -> appTargetFor(pkg)
                        // Keep enforcing whatever was last established. Every other outcome of a
                        // blind read weakens a block; this one cannot.
                        Inspected.Unreadable -> return@guard
                    }
                    applyTarget(targetId, System.currentTimeMillis())
                }
            }
        }
    }

    /**
     * Reads the screen and hands what it found to [classifyInspection], which decides what it
     * means. Everything node-shaped lives here; every fail-open judgement lives there.
     */
    private fun resolveInspectedTarget(pkg: String): Inspected {
        val root = rootInActiveWindow
        if (root == null) {
            trace("read($pkg): rootInActiveWindow null")
            return Inspected.Unreadable
        }
        try {
            // The active window is often not the app we were asked about -- most often it is one of
            // rotblock's own full-screen screens sitting on top of it, while the app underneath
            // keeps emitting content-change events as its page loads. Anything concluded from the
            // wrong window is noise, and acting on it drops a live target.
            if (root.packageName?.toString() != pkg) {
                trace("read($pkg): active window is ${root.packageName}")
                return Inspected.Unreadable
            }

            val feedKey = FeedRegistry.specsFor(pkg)
                .firstOrNull { spec -> spec.viewIdHints.any { root.hasViewId(it) } }
                ?.key
            val url = BrowserRegistry.specFor(pkg)
                ?.urlBarIds
                ?.firstNotNullOfOrNull { root.textForViewId(it) }
            trace("read($pkg): feed=$feedKey url=${url?.let { "\"$it\"" }}")

            return classifyInspection(pkg, feedKey, url, websiteMatchers)
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
        // Not only from the tick. A busy page re-evaluates several times a second and the tick can
        // legitimately not fire for its whole interval, and if the flush rode solely on the tick a
        // process kill would record the entire visit as zero seconds.
        ledger.touch(nowMs)
        val sessions = ledger.sessionsFor(targetId, nowMs)
        val decision = PolicyEngine.evaluate(policy, sessions, pause.pausedUntilMs, nowMs)
        trace("decision for ${policy.label}: $decision")
        when (decision) {
            is Decision.Allow -> {
                overlay.hide()
                ledger.closeSession(nowMs)
                // A pause suspends greyscale and friction too; a pause means "get out of my way".
                grayscale.restore()
                // A pause ends by *elapsing*, which fires no event at all: no window change while
                // you keep scrolling, and no settings emission for time merely passing. Without
                // the ticker running through it, the app would stay open past the deadline until
                // something else happened to wake us -- a fail-open, which this design does not get
                // to have. No session is opened, so paused time still costs no quota.
                val pausedUntil = decision.pausedUntilMs
                if (pausedUntil != null) startTicking(pausedUntil - nowMs) else stopTicking()
            }

            is Decision.Allowed -> {
                overlay.hide()
                if (Interstitial.isShowing) {
                    // One of our own screens is covering the app. You are not using it, so it does
                    // not get to charge you -- friction and cooldowns cost time, not quota.
                    ledger.closeSession(nowMs)
                } else {
                    ledger.startSession(targetId, nowMs)
                }
                // Wake exactly when the allowance runs out. While an interstitial is up nothing is
                // accruing, so that deadline is not approaching -- but the tick still has to run to
                // notice the screen going away, hence the clamp rather than a long sleep.
                startTicking(decision.remainingMs)
                if (policy.grayscale) grayscale.enable() else grayscale.restore()
                maybeShowFriction(policy)
            }

            is Decision.Blocked -> {
                // Close first: nextRelease assumes no session runs past now, and we should not
                // keep charging for time spent staring at the block screen.
                ledger.closeSession(nowMs)
                stopTicking()
                grayscale.restore()
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
        val escape = escapeFor(policy)
        val shown = overlay.show(
            label = policy.label,
            reason = decision.reason,
            liftsAtMs = decision.liftsAtMs,
            onPauseRequested = { launchPauseRequest(policy) },
            escape = escape,
        )
        if (!shown) {
            // No overlay permission, or the window manager refused. Blocking still has to block,
            // so the user gets ejected -- but for a website or feed, ejecting alone is the dead
            // end: the app reopens on the same page and ejects again, with no button anywhere to
            // escape it. Move the app off the blocked page first, then leave.
            escape?.action?.invoke()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    /**
     * How to get out of a block that covers more than it forbids.
     *
     * An app-level block needs nothing: the app *is* the target, so going home is a complete
     * answer. A website or feed block is different -- it covers a browser or an app you are still
     * allowed to use, including the address bar or the back gesture you would use to leave the
     * blocked part. Reopening restores the same page and blocks again, and the only remaining way
     * out is disabling the policy, which is the failure this whole app exists to prevent.
     */
    private fun escapeFor(policy: Policy): Escape? {
        val label = escapeLabelFor(policy.kind) ?: return null
        val leave: () -> Unit = when (policy.kind) {
            // Unreachable: escapeLabelFor returns null for APP and we have already left.
            TargetKind.APP -> return null

            // Backing out of a short-form player lands on the rest of the app, and if the app has
            // nothing to go back to it closes -- reopening then starts at its normal entry point
            // rather than back inside the feed. Either way you are out.
            TargetKind.FEED -> {
                { guard("escapeFeed") { performGlobalAction(GLOBAL_ACTION_BACK) } }
            }

            // Point the browser somewhere that is not the blocked site. Back is not enough on its
            // own: a restored tab can have no history, in which case back closes the browser and
            // reopening lands on the same blocked page again. Loading a URL changes what the
            // active tab *is*, which survives the app being closed and reopened.
            TargetKind.WEBSITE -> {
                {
                    guard("escapeSite") {
                        val browser = currentPackage
                        val navigated = browser != null && runCatching {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(BrowserRegistry.ESCAPE_URL))
                                    .setPackage(browser)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.isSuccess
                        if (!navigated) performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            }
        }
        return Escape(label) {
            leave()
            verifyEscaped(policy.id)
        }
    }

    /**
     * Checks that the escape actually escaped, and re-blocks if it did not.
     *
     * The escape hides the block screen and then *asks* an app to go somewhere else. Nothing
     * guarantees the app agrees: Samsung Internet answered an `about:blank` intent with an
     * "Invalid address" toast and stayed on the blocked page, `startActivity` returned normally,
     * and the block was already gone. From there the only thing that could have re-blocked was the
     * next successful address-bar read, and a page you scroll does not produce one -- so a tap on
     * "Leave this site" bought unlimited browsing on the site that had just run out of time.
     *
     * A request that hides a block must therefore carry its own proof of success. This is the
     * proof, and it fails closed: if the target is still the one we tried to leave when the check
     * lands, enforcement comes straight back. A successful escape clears the target by then --
     * either from the browser's own content-change events or from [requestInspect] below -- and
     * this finds nothing to do.
     *
     * Deliberately a delayed post and not a scheduled job: the screen is on and the user is
     * standing right there, so nothing needs to survive the process. If it is lost, the target
     * simply stays blocked.
     */
    private fun verifyEscaped(targetId: String) {
        currentPackage?.let { requestInspect(it) }
        handler.postDelayed({
            guard("verifyEscaped") {
                if (currentTargetId != targetId) return@guard
                trace("escape from $targetId did not take, re-blocking")
                evaluateAndEnforce(targetId, System.currentTimeMillis())
            }
        }, ESCAPE_VERIFY_MS)
    }

    /**
     * Shows the breathing screen once per visit.
     *
     * Marked as shown *before* launching, not after: if the launch fails or the user backs out,
     * a retry loop that reopens the screen every time the app redraws would be far worse than
     * skipping the friction once.
     */
    private fun maybeShowFriction(policy: Policy) {
        if (policy.frictionSeconds <= 0) return
        if (frictionShownFor == policy.id) {
            trace("friction skipped: already shown this visit for ${policy.id}")
            return
        }
        trace("friction SHOWING for ${policy.id} (was $frictionShownFor)")
        frictionShownFor = policy.id
        guard("friction") {
            startActivity(
                Intent(this, FrictionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(FrictionActivity.EXTRA_LABEL, policy.label)
                    .putExtra(FrictionActivity.EXTRA_SECONDS, policy.frictionSeconds),
            )
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

    /**
     * Arms the next re-check for [untilDeadlineMs] from now, clamped into
     * [MIN_TICK_MS]..[MAX_TICK_MS].
     *
     * Keeps an already-armed tick when it would fire no later than the one being asked for, and
     * that is not an optimisation -- it is what stops the tick being starved. This runs from every
     * evaluation, and a page emitting events several times a second would otherwise cancel and
     * re-post the callback faster than it could ever come due, so the tick would simply never run.
     *
     * Rescheduling *earlier* is always allowed, so the soonest deadline still wins and a late tick
     * can never become a fail-open.
     */
    private fun startTicking(untilDeadlineMs: Long) {
        val dueAtMs = System.currentTimeMillis() + untilDeadlineMs.coerceIn(MIN_TICK_MS, MAX_TICK_MS)
        if (ticking && tickDueAtMs in (System.currentTimeMillis() + 1)..dueAtMs) return
        ticking = true
        tickDueAtMs = dueAtMs
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, (dueAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private fun stopTicking() {
        if (!ticking) return
        ticking = false
        tickDueAtMs = 0L
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

    /**
     * Writes the previous outage to the log, if there was one worth mentioning.
     *
     * The log exists to show when enforcement was actually off, but Android rebinds this service
     * constantly -- on reinstall, on config changes, whenever another accessibility client
     * attaches. Logging each blip buried the real events: on the first device run 89% of the
     * audit log was bind churn, which is worse than not recording it at all, because the one
     * entry that matters stops standing out.
     *
     * So the disable is written on the *next* connect, once its true duration is known, and
     * stamped at the moment it happened rather than the moment it was noticed -- the same
     * approach as a lapsed pause.
     */
    private fun reportPreviousOutage() {
        val prefs = getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE)
        val downSince = prefs.getLong(KEY_DOWN_SINCE, 0L)
        val lastAlive = prefs.getLong(KEY_LAST_ALIVE, 0L)
        val reason = prefs.getString(KEY_DOWN_REASON, null)
        prefs.edit().remove(KEY_DOWN_SINCE).remove(KEY_DOWN_REASON).commit()

        // A clean unbind records exactly when it stopped. A force-stop, a crash or a reboot kills
        // the process with no callback at all, so fall back to the last heartbeat -- otherwise
        // being killed would erase the outage from the log entirely, which is the one direction
        // this record must not fail in.
        val stoppedAt = maxOf(downSince, lastAlive)
        if (stoppedAt <= 0L) {
            audit.record(AuditType.SERVICE_ENABLED, detail = "Enforcement started")
            return
        }
        val outage = System.currentTimeMillis() - stoppedAt
        if (outage < MIN_REPORTABLE_OUTAGE_MS) return // a rebind, not an event

        audit.record(
            AuditType.SERVICE_DISABLED,
            detail = reason ?: "Enforcement stopped",
            tsMs = downSince,
        )
        audit.record(
            AuditType.SERVICE_ENABLED,
            detail = "Enforcement back on after ${formatDuration(outage)} with nothing blocked",
        )
    }

    private fun teardown(reason: String) {
        val now = System.currentTimeMillis()
        trace("teardown: $reason")
        stopTicking()
        Interstitial.onCleared = null
        runCatching { unregisterReceiver(screenReceiver) }
        // onUnbind and onDestroy both land here; keep the first timestamp, which is when
        // enforcement actually stopped.
        runCatching {
            val prefs = getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_DOWN_SINCE, 0L) <= 0L) {
                prefs.edit()
                    .putLong(KEY_DOWN_SINCE, now)
                    .putString(KEY_DOWN_REASON, reason)
                    .commit()
            }
        }
        // Including any pending escape check: it would re-show the overlay on a service that is
        // going away, and nothing would be left to take that window down again.
        handler.removeCallbacksAndMessages(null)
        if (::ledger.isInitialized) ledger.closeSession(now)
        if (::overlay.isInitialized) overlay.hide()
        // Enforcement going away must not leave the display greyscale with nothing left running
        // to put it back.
        if (::grayscale.isInitialized) grayscale.restore()
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
