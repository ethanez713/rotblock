package com.ezhou.rotblock.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.block.BlockSelfTest
import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.block.Grayscale
import com.ezhou.rotblock.block.FrictionGate
import com.ezhou.rotblock.block.Interstitial
import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.insights.DailyRollup
import com.ezhou.rotblock.diag.Diagnostics
import com.ezhou.rotblock.pause.PauseController
import com.ezhou.rotblock.policy.Decision
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyEngine
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.UsageLedger
import com.ezhou.rotblock.quota.UsageSession
import com.ezhou.rotblock.workout.WorkoutMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

/**
 * Ceiling for a tick that is purely waiting out a deadline.
 *
 * [MAX_TICK_MS] buys two things -- a ledger that keeps flushing, and noticing one of our own
 * screens go away -- and neither is in play once the decision is to allow: no session is open, and
 * an interstitial clearing calls back directly. All that is left is the deadline itself, which for
 * a scheduled window can be most of a day away. Waking 240 times an hour to re-learn that a rule
 * is still outside its hours is exactly the battery cost that gets an always-on service switched
 * off.
 *
 * The obvious objection is drift: `postDelayed` counts uptime, which stops during deep sleep, so a
 * long delay could in principle fire late in wall-clock terms -- and a window *opening* late is a
 * fail-open. It cannot happen here, and not by luck: the ticker only ever exists while the screen
 * is on, because screen-off calls [stopTicking] and coming back re-evaluates from scratch. A
 * pending tick and a sleeping CPU are mutually exclusive by construction. Anything that changes
 * that gating has to revisit this constant.
 *
 * Generous rather than unbounded only because the value is added to a clock; the real interval
 * always comes from an actual deadline.
 */
private const val MAX_IDLE_TICK_MS = 10L * 24 * 60 * 60 * 1_000


/**
 * The visit in progress, carried across a rebind. See [seedForeground].
 *
 * Not a relaxation and not a deadline — losing these three strings is what *breaks* enforcement,
 * so persisting them is on the same side of the invariant as everything else here.
 */
// Internal, like the outage keys above: a diagnostic capture reads these directly, both from
// inside the service and, when nothing is bound, from com.ezhou.rotblock.diag.Diagnostics itself.
internal const val KEY_VISIT_PACKAGE = "visit_package"
internal const val KEY_VISIT_TARGET = "visit_target"
// A new key rather than the old "visit_friction_shown": that one recorded a countdown having been
// *put up*, which is exactly the thing that no longer spends the wait. Reading a stale one back as
// "served" would hand out one free bypass per upgrade.
internal const val KEY_VISIT_FRICTION_SERVED = "visit_friction_served"

/**
 * Attempts to read the already-open window at startup, before giving up until the next event.
 *
 * Ten seconds, not the one second this used to allow. `rootInActiveWindow` returns null for a
 * while after a bind even with the screen on and an app plainly in front -- measured on device,
 * where `uiautomator dump` retrieved the very same window seconds later without trouble. Giving up
 * after a second meant a reinstall or a re-enable left the service permanently blind, because the
 * app it should have been watching was already in the foreground and would never announce itself.
 *
 * The cost is twenty null checks spread over ten seconds on a background coroutine, once per bind,
 * and only ever while a bind is happening. That is not a battery consideration; being blind is.
 */
private const val SEED_ATTEMPTS = 20
private const val SEED_RETRY_MS = 500L

/**
 * How long after the last window-state event the active window is read to confirm the foreground.
 *
 * `notificationTimeout` delivers only the *last* window-state change of each burst, and on One UI
 * the last one is routinely not the app: the launcher's own change when an app opens from home,
 * `sidegesturepad` on every navigation swipe, Game Booster's overlay when a game starts. Measured on
 * device: a quota'd game in front for minutes, the only event delivered for its launch was
 * `game.gametools`, and a game emits nothing afterwards that could correct it. A second is long
 * enough for the transition that produced the burst to have finished.
 */
private const val FOREGROUND_SETTLE_MS = 1_000L

/**
 * Floor between foreground rechecks triggered by a content-change event that disagrees with
 * [RotblockAccessibilityService.currentPackage].
 *
 * In the healthy case `currentPackage` is already correct, every content-change matches it, and
 * this path never runs at all -- the cost is not "constant background polling", it is "one
 * `rootInActiveWindow` read every two seconds for as long as a policy-relevant package keeps
 * disagreeing with a stale belief". That is the bounded worst case, not free, and it only starts
 * once the state that gave rise to this fix -- a missed window-state change -- has already
 * happened.
 *
 * Two seconds bounds how long a real recovery can be stuck behind an already-in-flight,
 * not-yet-resolved check (see [RotblockAccessibilityService.requestForegroundRecheck]) without
 * being so tight that a churning background tab could keep the read firing on every event.
 */
private const val FOREGROUND_RECHECK_MIN_INTERVAL_MS = 2_000L

/**
 * How often a held target with a stopped ticker may be re-evaluated off an event.
 *
 * Five seconds rather than two: unlike [FOREGROUND_RECHECK_MIN_INTERVAL_MS] this buys no node read
 * and settles no ambiguity, it simply restarts a clock that should not have been stopped. The one
 * healthy state it fires in is a hard block with no deadline, and once every five seconds is a
 * cheap price for the block screen being restored that fast if it is ever lost.
 */
private const val EVENT_REARM_MIN_INTERVAL_MS = 5_000L

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
    private lateinit var grayscale: Grayscale

    private val settingsMirror = SettingsMirror()
    private val outage = OutageJournal(this) { audit }
    private val detectionJournal = DetectionJournal(
        scope = scope,
        saveCounters = { encoded ->
            Rotblock.settings(this@RotblockAccessibilityService).setDetectionCounters(encoded)
        },
        audit = { audit },
        frictionServedFor = { if (::enforcementUi.isInitialized) enforcementUi.frictionServedFor() else null },
        currentTargetId = { currentTargetId },
    )
    private lateinit var enforcementUi: EnforcementUi
    private lateinit var workout: WorkoutCoordinator
    private lateinit var escapeController: EscapeController
    private lateinit var pageInspector: PageInspector
    private lateinit var diagnostics: DiagnosticsCapture

    /**
     * Whether the currently-held target is a fact a read established or still a restore's guess.
     * See [TargetConfidence] -- gates the friction countdown, never enforcement itself.
     */
    private val confidence = TargetConfidence()

    /** Read on every window change, so it stays a plain volatile map lookup. */
    @Volatile private var policiesByTarget: Map<String, Policy> = emptyMap()

    /**
     * Every policy covering a target id, which since groups is no longer at most one.
     *
     * A target's own rule is indexed under its id; a group is indexed under each of its members.
     * Nothing on screen is ever a group, so a group's own id never appears here as a key -- it
     * appears as a value, beside the rule for the thing you are actually looking at.
     */
    @Volatile private var policiesCovering: Map<String, List<Policy>> = emptyMap()

    /** Website rule matchers, kept separately because they need scanning rather than lookup. */
    @Volatile private var websiteMatchers: List<String> = emptyList()

    /** Packages whose windows are not "the app you are using": ours, system UI, keyboards. */
    @Volatile private var ignoredPackages: Set<String> = emptySet()

    /** Packages worth reading the node tree for: browsers and apps with in-app feeds. */
    private val inspectablePackages: Set<String> = BrowserRegistry.packages + FeedRegistry.packages

    /** Memoised `pkg/class` -> is-an-activity answers for [isActivity]. Main thread only. */
    private val activityCache = HashMap<String, Boolean>()

    private var currentPackage: String? = null

    /**
     * Volatile because the inspector coroutine reads it to decide whether a target still needs
     * acquiring; every write is still on the main thread.
     */
    @Volatile private var currentTargetId: String? = null

    /**
     * Whether the reveal tap has already been tried for the visit in progress. A synthesised click
     * is a real side effect on screen, so one guess per visit is the ceiling regardless of how many
     * reads a sustained scroll asks for. Cleared on the main thread in [onForegroundChanged]; read
     * by the inspector coroutine, hence volatile.
     */
    @Volatile private var revealAttemptedThisVisit = false

    /**
     * What a read last found inside each app that needs one, so re-entering a browser does not
     * silently start tracking nothing. See [VisitMemory] -- this is the fix for "it stopped
     * noticing reddit after I switched apps".
     */
    private val visits = VisitMemory()

    /** Last values written by [persistVisit], so repeat calls do not touch disk. */
    private var persistedPackage: String? = null
    private var persistedTarget: String? = null
    private var persistedFrictionServed: String? = null

    private var ticking = false

    /** When the armed tick is due, so a re-arm can tell whether it would delay it. */
    private var tickDueAtMs = 0L

    private var watchJob: Job? = null
    private var inspectorJob: Job? = null
    private var reseedJob: Job? = null
    private var foregroundRecheckJob: Job? = null
    private var foregroundConfirmJob: Job? = null

    /** Callback thread. Last time [requestForegroundRecheck] fired, for its own throttle. */
    private var lastForegroundRecheckAtMs = 0L

    /** Callback thread. Last time [shouldRearmFromEvent] let an event restart the ticker. */
    private var lastEventRearmAtMs = 0L

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
                        detectionJournal.settleEvaluationRate(System.currentTimeMillis())
                        stopTicking()
                        enforcementUi.hideOverlay()
                        enforcementUi.hideCountdown()
                        enforcementUi.endFrictionVisit()
                        // The visit really has ended, so the record of it has to say so. Without
                        // this the persisted visit went on claiming the wait had been served: lock
                        // the phone inside a friction-gated app, let One UI kill us while the
                        // screen is off, and `restoreServed` reads that stale mark back on the
                        // rebind. Unlocking into the same app fires no foreground change to re-arm
                        // the gate, so the countdown was simply skipped -- a wait lost rather than
                        // one served twice, which is the direction [FrictionGate] does not fail in.
                        persistVisit()
                        // Never leave the phone monochrome behind a locked screen.
                        grayscale.restore()
                        workout.onScreenOff()
                    }
                    // Coming back may land on the same app with no new window event, so the
                    // session has to be restarted explicitly -- and may equally land on a
                    // *different* app with no event either, which is why this verifies rather
                    // than only resumes. See [onUnlocked].
                    //
                    // USER_PRESENT only, deliberately not SCREEN_ON. The lock screen is system UI,
                    // which is ignored, so `currentPackage` is still whatever was open underneath
                    // it -- resuming on SCREEN_ON charges the target for every glance at the clock
                    // and for however long the phone sits unlocked-but-not-entered. Over-charging
                    // is the one direction the ledger may not err in.
                    // A screen coming back on with no keyguard in the way will never produce a
                    // USER_PRESENT, and that is the case this exists for: inside Samsung's
                    // lock-delay grace period the phone locks the display without engaging the
                    // lock screen, so screen-off stopped the ticker and nothing was ever going to
                    // start it again. Measured on device at twenty-three minutes of a quota'd game
                    // enforcing nothing.
                    //
                    // Gated on the keyguard rather than on the screen, which is what keeps the
                    // "never resume on SCREEN_ON" rule above intact: with a lock screen up this
                    // does nothing at all and USER_PRESENT still does the work, so a glance at the
                    // clock cannot charge the target underneath.
                    Intent.ACTION_SCREEN_ON -> if (!isKeyguardLocked()) onUnlocked()

                    Intent.ACTION_USER_PRESENT -> onUnlocked()
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
                if (target == null) {
                    // The no-target path a lapsing workout cap arrives on: nothing else calls
                    // evaluateAndEnforce to log the transition or repaint the display.
                    workout.noteWorkoutTransition(now)
                    refreshGrayscale(now, ruleWantsGrayscale = false)
                    workout.refreshWorkoutReminder(now)
                    armForWorkoutOnly(now)
                } else {
                    evaluateAndEnforce(target, now)
                }
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
            grayscale = Grayscale(this)
            val dispatch = object : ServiceDispatch {
                override val context: Context get() = this@RotblockAccessibilityService
                override val handler: Handler get() = this@RotblockAccessibilityService.handler
                override val scope: CoroutineScope get() = this@RotblockAccessibilityService.scope
            }
            val serviceGuard = object : ServiceGuard {
                override fun guard(where: String, block: () -> Unit) =
                    this@RotblockAccessibilityService.guard(where, block)
                override fun trace(message: String) = this@RotblockAccessibilityService.trace(message)
            }
            val actions = object : AccessibilityActions {
                override fun rootInActiveWindow() = this@RotblockAccessibilityService.rootInActiveWindow
                override fun performGlobalAction(action: Int) =
                    this@RotblockAccessibilityService.performGlobalAction(action)
                override fun startActivity(intent: Intent) =
                    this@RotblockAccessibilityService.startActivity(intent)
            }
            val belief = object : ForegroundBelief {
                override val currentPackage: String? get() = this@RotblockAccessibilityService.currentPackage
                override val currentTargetId: String? get() = this@RotblockAccessibilityService.currentTargetId
                override val lastWindowReadAtMs: Long get() = pageInspector.lastWindowReadAtMs()
            }
            val coverage = object : CoverageIndex {
                override val ignoredPackages: Set<String> get() = this@RotblockAccessibilityService.ignoredPackages
                override val websiteMatchers: List<String> get() = this@RotblockAccessibilityService.websiteMatchers
            }
            val coordination = object : InspectCoordination {
                override val revealAttemptedThisVisit: Boolean
                    get() = this@RotblockAccessibilityService.revealAttemptedThisVisit
                override fun markRevealAttempted() {
                    this@RotblockAccessibilityService.revealAttemptedThisVisit = true
                }
                override fun foregroundSettlePending() = handler.hasCallbacks(settleForeground)
                override fun scheduleForegroundSettle() =
                    this@RotblockAccessibilityService.scheduleForegroundSettle()
            }
            val sink = object : InspectedTargetSink {
                override fun recordInspectError(error: Throwable) = audit.recordError("inspect", error)
                override fun noteDetectionLook(pkg: String, foundFeedKey: String?) =
                    detectionJournal.noteDetectionLook(pkg, foundFeedKey)
                override fun rememberVisit(pkg: String, targetId: String) = visits.remember(pkg, targetId)
                override fun forgetVisit(pkg: String) = visits.forget(pkg)
                override fun appTargetFor(pkg: String) = this@RotblockAccessibilityService.appTargetFor(pkg)
                override fun applyTarget(targetId: String?, nowMs: Long, confirmed: Boolean) =
                    this@RotblockAccessibilityService.applyTarget(targetId, nowMs, confirmed)
            }
            pageInspector = PageInspector(
                handler = handler,
                actions = actions,
                belief = belief,
                settings = settingsMirror,
                serviceGuard = serviceGuard,
                coverage = coverage,
                coordination = coordination,
                sink = sink,
            )
            val followUp = object : EscapeFollowUp {
                override fun requestInspect(pkg: String) = pageInspector.requestInspect(pkg)
                override fun evaluateAndEnforce(targetId: String, nowMs: Long) =
                    this@RotblockAccessibilityService.evaluateAndEnforce(targetId, nowMs)
                override fun persistVisit() = this@RotblockAccessibilityService.persistVisit()
            }
            val visitOps = object : VisitMemoryOps {
                override fun remember(pkg: String, targetId: String) = visits.remember(pkg, targetId)
                override fun forget(pkg: String) = visits.forget(pkg)
            }
            val heldConfidence = object : HeldConfidence {
                override val confirmed: Boolean get() = confidence.confirmed
                override fun clear() = confidence.clear()
            }
            escapeController = EscapeController(
                handler = handler,
                serviceGuard = serviceGuard,
                belief = belief,
                actions = actions,
                visits = visitOps,
                confidence = heldConfidence,
                followUp = followUp,
            )
            val screen = object : BlockScreenFacts {
                override val targetConfirmed: Boolean get() = confidence.confirmed
                override fun policiesCovering(targetId: String) = enabledPoliciesCovering(targetId)
                override fun pauseSince(targetId: String) = pause.effectivePauseSince(targetId)
                override fun escapeFor(targetId: String) = escapeController.escapeFor(targetId)
            }
            enforcementUi = EnforcementUi(
                dispatch = dispatch,
                serviceGuard = serviceGuard,
                audit = { audit },
                settings = settingsMirror,
                belief = belief,
                actions = actions,
                screen = screen,
                persistHudPosition = { x, y ->
                    Rotblock.settings(this@RotblockAccessibilityService).setHudPosition(x, y)
                },
            )
            val hooks = object : WorkoutHooks {
                override fun blockOverlayShowing() = enforcementUi.overlayShowing()
                override fun reEvaluate() = reEvaluateCurrent()
                override fun screenOn() = isScreenOn()
                override fun launchIntent(packageName: String) =
                    packageManager.getLaunchIntentForPackage(packageName)
                override fun soonest(a: Long?, b: Long?) = soonestMs(a, b)
            }
            workout = WorkoutCoordinator(
                context = this,
                audit = { audit },
                serviceGuard = serviceGuard,
                belief = belief,
                actions = actions,
                hooks = hooks,
            )
            val accounting = object : CaptureAccounting {
                override fun sessionsForTarget(targetId: String, nowMs: Long) = ledger.sessionsFor(targetId, nowMs)
                override fun sessionsForPolicy(policy: Policy, targetId: String, nowMs: Long) =
                    sessionsFor(policy, targetId, nowMs)
                override fun policiesCovering(targetId: String) = enabledPoliciesCovering(targetId)
                override fun pauseUntil(targetId: String) = pause.effectivePauseUntil(targetId)
                override suspend fun pauseBudget(nowMs: Long) = pause.budgetStatus(nowMs)
            }
            diagnostics = DiagnosticsCapture(
                dispatch = dispatch,
                serviceGuard = serviceGuard,
                audit = { audit },
                actions = actions,
                live = {
                    CaptureLive(
                        currentPackage = currentPackage,
                        currentTargetId = currentTargetId,
                        ticking = ticking,
                        tickDueAtMs = tickDueAtMs,
                        frictionServedFor = enforcementUi.frictionServedFor(),
                        targetConfirmed = confidence.confirmed,
                        visitMemory = visits.snapshot(),
                        searchPageMemory = pageInspector.searchPageSnapshot(),
                        linkCorroborationPending = pageInspector.linkCorroborationSnapshot(),
                        revealAttemptedThisVisit = revealAttemptedThisVisit,
                        hudDismissedThisSession = enforcementUi.hudDismissedThisSession,
                        policiesByTarget = policiesByTarget,
                        websiteMatchers = websiteMatchers,
                        dayStartHour = settingsMirror.dayStartHour,
                        openTargetId = ledger.openTargetId,
                        pausedUntilMs = pause.pausedUntilMs,
                        pausedTargets = pause.pausedTargets,
                    )
                },
                accounting = accounting,
            )
            ignoredPackages = buildIgnoredPackages()
            trace("onServiceConnected: fresh instance, state reset")
            EnforcementStatus.setConnected(true)

            // Our own screens blind every read taken while they are up, and the app underneath
            // resumes before they stop -- so the window-state change that would prompt a re-read
            // arrives too early to see past us. This is the second chance.
            Interstitial.onCleared = {
                handler.post { guard("interstitialCleared") {
                    trace("interstitial cleared, re-evaluating")
                    reEvaluateCurrent()
                } }
            }

            // The moment one of our own screens goes up, not just when it comes down. Without this
            // the friction countdown and the pause cooldown are charged against the target's quota
            // in full, and the on-screen ring keeps visibly counting down over a screen that is not
            // the target, until the next tick happens to notice.
            Interstitial.onShown = {
                handler.post { guard("interstitialShown") {
                    trace("interstitial shown, re-evaluating")
                    reEvaluateCurrent()
                } }
            }

            // The countdown is the only thing that can see itself: whether it came up at all, and
            // whether it was sat through rather than merely shown. Both arrive on the main thread
            // already; posting keeps every write to the gate on the same thread as the service's.
            FrictionGate.onShown = { targetId ->
                handler.post { guard("frictionShown") { enforcementUi.frictionAppeared(targetId) } }
            }
            FrictionGate.onHidden = { targetId ->
                handler.post { guard("frictionHidden") { enforcementUi.frictionHidden(targetId) } }
            }
            FrictionGate.onServed = { targetId ->
                handler.post { guard("frictionServed") {
                    trace("friction served for $targetId")
                    enforcementUi.frictionServed(targetId)
                    persistVisit()
                } }
            }
            FrictionGate.onDeclined = { targetId ->
                handler.post { guard("frictionDeclined") {
                    trace("friction declined for $targetId")
                    escapeController.noteEscaped(targetId)
                    escapeController.verifyEscaped(targetId, System.currentTimeMillis())
                } }
            }

            // Runs on Setup's own thread, which is already the main thread this service does
            // everything on -- no dispatch needed, unlike the callbacks above that can fire from a
            // background coroutine. guard() returns Unit, not the Boolean this needs to report
            // back to Setup, so the same never-crash contract is inlined here instead.
            BlockSelfTest.onRequested = {
                runCatching { enforcementUi.runBlockSelfTest() }
                    .onFailure { if (::audit.isInitialized) audit.recordError("blockSelfTest", it) }
                    .getOrDefault(false)
            }

            // Posted rather than run inline: the QS tile that triggers this is not on this
            // service's main thread by contract, and everything the capture reads below is.
            Diagnostics.onCaptureRequested = { requestedAtMs ->
                handler.post { guard("diagnosticsCapture") { diagnostics.startDiagnosticsCapture(requestedAtMs) } }
            }

            // Fires on liveness or start-time changes -- not on every re-post of Strong's ongoing
            // notification. Posting re-enters this service by way of re-evaluating what should be
            // on screen, so it goes through the handler like every other listener above.
            WorkoutMode.onChanged = {
                handler.post { guard("workoutChanged") { workout.onWorkoutModeChanged() } }
            }

            // Undo a greyscale left on by a crash before doing anything else.
            grayscale.restoreOnStart()

            outage.reportPreviousOutage()

            // Isolated: if this throws, the outer guard would swallow it and skip everything
            // below, leaving the service alive but never loading a single policy. Losing
            // screen-state handling is survivable; losing enforcement is not.
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    screenReceiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_USER_PRESENT)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.onFailure { audit.recordError("registerScreenReceiver", it) }

            watchJob?.cancel()
            watchJob = scope.launch {
                ledger.load(System.currentTimeMillis())
                ledger.prune(System.currentTimeMillis())
                // Same opportunistic slot, for the same reason: this is the one path that runs on
                // every connect, and nothing here may arm a job to do it on a schedule.
                audit.prune(System.currentTimeMillis())
                pause.refresh()

                // Read once rather than from the settings flow below: the flow re-emits on every
                // unrelated settings change, and adopting the stored string again would throw away
                // whatever has been counted since the last flush.
                detectionJournal.load(
                    Rotblock.settings(this@RotblockAccessibilityService).current().detectionCounters,
                )

                // Off the main thread on purpose: this reads the node tree, which the event
                // callback is never allowed to do.
                seedForegroundFromScreen()
                launch {
                    Rotblock.settings(this@RotblockAccessibilityService).settings.collectLatest {
                        pause.refresh()
                        settingsMirror.apply(it)
                        // A pause starting or ending changes what should be on screen right now,
                        // and so does switching the countdown readout on or off.
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
                        policiesCovering = coverageByTarget(list)
                        // A group's website members have to be here too, or the page is never
                        // recognised as a target at all and the group has nothing to cover.
                        websiteMatchers = (
                            list.filter { it.enabled && it.kind == TargetKind.WEBSITE }
                                .map { it.matcher } +
                                list.filter { it.enabled && it.kind == TargetKind.GROUP }
                                    .flatMap { it.members }
                                    .filter { Policy.kindOf(it) == TargetKind.WEBSITE }
                                    .map { it.removePrefix("web:") }
                            ).distinct()
                        handler.post { guard("policiesChanged") { reEvaluateCurrent() } }
                    }
            }

            inspectorJob?.cancel()
            inspectorJob = scope.launch { pageInspector.runInspector() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        guard("onAccessibilityEvent") {
            event ?: return@guard
            outage.beat()
            val pkg = event.packageName?.toString() ?: return@guard
            if (pkg in ignoredPackages) return@guard

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    trace("WSC pkg=$pkg cls=${event.className} current=$currentPackage")
                    // Every one, including those judged not to be a foreground change below: the
                    // event that was coalesced away may have been the app switch itself.
                    scheduleForegroundSettle()
                    if (pkg != currentPackage) {
                        if (isForegroundChange(pkg, event.className?.toString())) {
                            onForegroundChanged(pkg, System.currentTimeMillis())
                        } else {
                            trace("  WSC ignored: not a foreground change")
                        }
                    } else {
                        pageInspector.requestInspect(pkg)
                        rearmIfStalled()
                    }
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // Navigating within a browser or scrolling into a feed produces no window
                    // state change, so this is the only signal that the target changed.
                    if (pkg == currentPackage) {
                        pageInspector.requestInspect(pkg)
                        // The only route back for a plain app target whose ticker has stopped:
                        // requestInspect above declines a package that is not a browser or feed
                        // app, so without this the event ends here and nothing is ever enforced
                        // again. See [shouldRearmFromEvent].
                        rearmIfStalled()
                    } else if (currentPackage == null && isForegroundChange(pkg, null)) {
                        // Nothing has been established at all: the seed failed with the screen
                        // off, and no window-state change has arrived since because the app was
                        // already in front before we existed. Scrolling a page is then the only
                        // evidence we will ever get, and discarding it for not matching a package
                        // we never learned is what kept the service blind for an entire session.
                        //
                        // Passing a null class deliberately restricts this to packages we have a
                        // reason to care about -- one with a policy, or a browser -- so a passing
                        // toast still cannot claim the foreground.
                        trace("WCC adopting $pkg: nothing established yet")
                        onForegroundChanged(pkg, System.currentTimeMillis())
                    } else if (
                        shouldRecheckForeground(
                            eventPackage = pkg,
                            currentPackage = currentPackage,
                            relevant = isForegroundChange(pkg, null),
                            nowMs = System.currentTimeMillis(),
                            lastRecheckAtMs = lastForegroundRecheckAtMs,
                            minIntervalMs = FOREGROUND_RECHECK_MIN_INTERVAL_MS,
                        )
                    ) {
                        // Unlike the null-currentPackage case above, there is a competing belief
                        // here: something already holds the target. Adopting pkg on the strength
                        // of a content-change alone -- as the branch above does when there is
                        // nothing to lose -- would let a background browser tab that is still
                        // rendering claim the foreground and start charging its quota while the
                        // real foreground is some other app entirely. That is over-charging, the
                        // direction this app must not fail in, so this only ever verifies.
                        trace("WCC currentPackage=$currentPackage stale against $pkg, verifying")
                        requestForegroundRecheck(pkg)
                    }
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
        if (policiesCovering.containsKey(appTargetFor(pkg))) return true
        if (pkg in inspectablePackages) return true
        return isActivity(pkg, className)
    }

    /**
     * Resolves `pkg/className` against PackageManager, cached — the lookup is not free.
     *
     * Since `QUERY_ALL_PACKAGES` was dropped this also answers false for a package we cannot *see*
     * under scoped package visibility. That is the safe direction and mostly the desired one: what
     * the `<queries>` declaration makes visible is everything with a launcher icon, so real apps
     * still resolve, while `android`'s toast and overlay-warning windows — the ones that used to
     * cancel a block by looking like an app switch — no longer resolve at all. A package with a
     * policy never reaches here; [isForegroundChange] honours those first, whatever they are.
     */
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

    private fun onForegroundChanged(pkg: String, nowMs: Long) {
        trace("foreground -> $pkg")
        EnforcementStatus.note("foreground -> $pkg")
        currentPackage = pkg

        // Leaving the app earns the countdown again, however briefly you were gone. Mostly this
        // already fell out of the target changing on the way out and back, but not always -- two
        // browsers showing the same site, or a rule re-acquired before the switch registered --
        // and "once per visit" is the rule, not "once per target". Only a genuine foreground
        // change reaches here; a toast or a heads-up notification must never re-arm it, or the
        // countdown reappears on top of an app you are already using.
        enforcementUi.endFrictionVisit()

        // Same visit-not-target reasoning as friction: a reveal already tried on the page you left
        // says nothing about the page you are about to land on.
        revealAttemptedThisVisit = false

        // Same reasoning, one line down: a dismissal is scoped to the session that was open when
        // the ring went away, and the line below ends that session unconditionally -- even when
        // the target you land back on reads the same, ledger accounting starts a fresh session for
        // it once this closes the open one. A dismissal from ten minutes ago on the same site must
        // not silently apply to a visit that has not happened yet.
        enforcementUi.clearHudDismissals()

        // Whatever was accruing time has stopped.
        ledger.closeSession(nowMs)
        stopTicking()

        // Not simply the app target. Inside a browser the app target means "nothing here is
        // covered", and coming back to a page you left scrolled reads as unreadable -- so the
        // website target would be dropped on the way in and never re-acquired while you kept
        // scrolling. The read that follows overrules this within about a second either way.
        //
        // The resumed target, if any, is a restore rather than a read -- see [TargetConfidence] --
        // so it may enforce but must not drive the friction countdown until a read confirms it.
        // Falling back to the app target needs no confirming: the package just came from a real
        // accessibility event, which makes it a fact rather than a guess.
        val resumed = visits.resume(pkg)
        applyTarget(resumed ?: appTargetFor(pkg), nowMs, confirmed = resumed == null)

        pageInspector.requestInspect(pkg)
    }

    /**
     * Runs on the main thread; [targetId] null means nothing here is governed by a policy.
     *
     * @param confirmed whether [targetId] comes from a read rather than a restore -- see
     *   [TargetConfidence]. Every call site must state which it is.
     */
    private fun applyTarget(targetId: String?, nowMs: Long, confirmed: Boolean) {
        val covering = targetId?.let { enabledPoliciesCovering(it) }.orEmpty()
        trace("applyTarget($targetId) policies=${covering.map { it.label }} cur=$currentTargetId servedFor=${enforcementUi.frictionServedFor()}")
        if (targetId == null || covering.isEmpty()) {
            if (enforcementUi.frictionServedFor() != null) trace("  ENDING friction visit (no policy for $targetId)")
            if (currentTargetId != null) {
                EnforcementStatus.note("target $currentTargetId -> none (nothing here is covered)")
            }
            // While currentTargetId still names the target the storm was about.
            detectionJournal.settleEvaluationRate(nowMs)
            currentTargetId = null
            confidence.clear()
            enforcementUi.endFrictionVisit()
            ledger.closeSession(nowMs)
            enforcementUi.hideOverlay()
            enforcementUi.hideCountdown()
            refreshGrayscale(nowMs, ruleWantsGrayscale = false)
            workout.refreshWorkoutReminder(nowMs)
            armForWorkoutOnly(nowMs)
            persistVisit()
            reportStatus()
            return
        }
        val changed = currentTargetId != targetId
        if (changed) {
            trace("  target change $currentTargetId -> $targetId, re-arming friction")
            EnforcementStatus.note("target ${currentTargetId ?: "none"} -> $targetId")
            ledger.closeSession(nowMs)
            currentTargetId = targetId
            enforcementUi.endFrictionVisit()
            // The other spot a session can end without an intervening hideCountdown() call --
            // navigating within the same foreground from a dismissed target straight to a
            // different one (a link inside a browser, say), which never routes through
            // onForegroundChanged. See [hudDismissedThisSession]'s doc.
            enforcementUi.clearHudDismissals()
        }
        confidence.apply(changed, confirmed)
        evaluateAndEnforce(targetId, nowMs)
        // After enforcement, so a friction screen just shown is part of what gets carried over.
        persistVisit()
        reportStatus()
    }

    /** Publishes what enforcement believes, for the "Right now" panel. See [EnforcementStatus]. */
    private fun reportStatus() {
        val nowMs = System.currentTimeMillis()
        // Both or neither: a source without a deadline would read as a live workout that never
        // ends, which is the state this panel exists to make impossible to mistake.
        val workoutUntil = WorkoutMode.activeUntilMs(nowMs)
        EnforcementStatus.update(
            pkg = currentPackage,
            targetId = currentTargetId,
            accruing = ledger.openTargetId,
            interstitial = Interstitial.isShowing(),
            workoutUntil = workoutUntil,
            workoutSource = workoutUntil?.let { WorkoutMode.source.name.lowercase() },
        )
    }

    /**
     * Restarts enforcement when a held target's ticker has stopped and nothing will restart it.
     *
     * Callback thread, so the work is posted: [evaluateAndEnforce] touches service state that lives
     * on the main thread, and the throttle is stamped here rather than inside the post so a burst
     * of events cannot queue a hundred of them behind one another.
     */
    private fun rearmIfStalled() {
        val now = System.currentTimeMillis()
        if (!shouldRearmFromEvent(
                hasTarget = currentTargetId != null,
                tickerArmed = ticking,
                screenOn = isScreenOn(),
                nowMs = now,
                lastRearmAtMs = lastEventRearmAtMs,
                minIntervalMs = EVENT_REARM_MIN_INTERVAL_MS,
            )
        ) {
            return
        }
        lastEventRearmAtMs = now
        handler.post {
            guard("rearmIfStalled") {
                val target = currentTargetId ?: return@guard
                // Re-checked on the main thread: an evaluation that landed in the meantime may
                // already have armed it, and re-arming a live ticker is how a deadline gets pushed.
                if (ticking) return@guard
                trace("re-arming from an event: $target held with the ticker stopped")
                EnforcementStatus.note("$target: ticker had stopped, re-armed from an event")
                evaluateAndEnforce(target, System.currentTimeMillis())
            }
        }
    }

    /**
     * Unlocking, which is a foreground change that arrives with no event to announce it.
     *
     * Two halves, and they answer different questions. The first re-enforces what was believed
     * before the lock straight away, so a block lands with the screen rather than after a poll --
     * and it is also the branch that seeds from nothing when the service was killed while the
     * screen was off. The second questions that belief, which nothing else does: see
     * [shouldAdoptActiveWindow].
     *
     * The null case is left to [reEvaluateCurrent] alone rather than run through both, because its
     * [reseed] reads the same window this would and two polls racing for it is one read too many
     * for an answer neither can improve on.
     */
    private fun onUnlocked() {
        reEvaluateCurrent()
        if (currentPackage != null) confirmForegroundFromScreen("unlock", SEED_ATTEMPTS)
        // Android prunes usage events after about ten days, and this process can outlive that
        // without restarting or a tile binding. An unlock is the one daily moment that is always
        // already awake; the rollup is a single in-memory prefs read unless a day has completed.
        scope.launch { DailyRollup.catchUp(this@RotblockAccessibilityService) }
    }

    private val settleForeground = Runnable {
        guard("settleForeground") {
            if (currentPackage != null && isScreenOn()) confirmForegroundFromScreen("settle", attempts = 1)
        }
    }

    /** Main thread. Restarts the wait, so a burst of events costs one read after it ends. */
    private fun scheduleForegroundSettle() {
        handler.removeCallbacks(settleForeground)
        handler.postDelayed(settleForeground, FOREGROUND_SETTLE_MS)
    }

    private fun reEvaluateCurrent() {
        val now = System.currentTimeMillis()
        // Cheap, and this runs on unlock and on every settings change, which is the widest net we
        // have for noticing a pause that lapsed while nothing was watching.
        pause.noteExpiryIfNeeded(now)
        val pkg = currentPackage
        if (pkg == null) {
            // The seed at connect is allowed to fail and regularly does: enabling the service or
            // reinstalling while the phone is idle means there is no readable window yet. Nothing
            // ever asked again, and an app already in the foreground fires no window-state change
            // when you come back to it -- so the service stayed blind for as long as you stayed in
            // that app. Observed on device: bound, alive, zero events, nothing enforced, no symptom.
            //
            // Unlocking is exactly when the screen becomes readable again, and this is the path
            // ACTION_USER_PRESENT already takes.
            reseed()
            return
        }
        // Falling back to the app target needs no confirming, the same as everywhere else; simply
        // re-applying a target already held says nothing new about it, so [confidence] is left as
        // it was.
        applyTarget(currentTargetId ?: appTargetFor(pkg), now, confirmed = currentTargetId == null)
        pageInspector.requestInspect(pkg)
    }

    /** One re-seed at a time; callers fire on unlock and on every settings and policy change. */
    private fun reseed() {
        if (reseedJob?.isActive == true) return
        reseedJob = scope.launch { seedForegroundFromScreen() }
    }

    /**
     * Verifies a stale [currentPackage] against the one structural signal that can settle it,
     * fired when [shouldRecheckForeground] says a content-change event's package is worth
     * checking. Never walks the node tree on the callback thread -- see [seedForegroundFromScreen]
     * for the same shape, reading `rootInActiveWindow` off [scope] and hopping back to the main
     * thread only with an answer in hand.
     *
     * If the active window is not [pkg], this does nothing at all: that is exactly what a
     * background browser tab still emitting content changes looks like, and it is precisely what
     * this check exists to reject rather than adopt.
     */
    private fun requestForegroundRecheck(pkg: String) {
        if (foregroundRecheckJob?.isActive == true) return
        lastForegroundRecheckAtMs = System.currentTimeMillis()
        foregroundRecheckJob = scope.launch {
            val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (rootPkg != pkg) return@launch
            withContext(Dispatchers.Main) {
                guard("foregroundRecheck") {
                    // May have already been corrected by a window-state change that arrived
                    // while this read was in flight.
                    if (currentPackage == pkg) return@guard
                    trace("foreground recheck confirmed $pkg over stale $currentPackage")
                    EnforcementStatus.note("foreground recheck: $currentPackage was stale, corrected to $pkg")
                    onForegroundChanged(pkg, System.currentTimeMillis())
                }
            }
        }
    }

    private fun appTargetFor(pkg: String): String = Policy.targetId(TargetKind.APP, pkg)

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
     *    a foreground change into an app with no policy, which ends the friction visit. The next
     *    read then resolves the website again, that reads as a *target change*, friction re-arms,
     *    and the countdown fires again -- the loop, exactly.
     *
     * So the visit is persisted and restored, but only when the package on screen still matches the
     * one it was recorded against. A rebind that happens while you are somewhere else starts clean.
     */
    private suspend fun seedForegroundFromScreen() {
        val pkg = readActiveWindowPackage()
        if (pkg == null) {
            trace("seed: gave up, no readable active window")
            return
        }
        withContext(Dispatchers.Main) { guard("seed") { seedForeground(pkg) } }
    }

    /**
     * Polls `rootInActiveWindow` for a package that is really somebody's foreground, or null.
     *
     * Never on the callback thread -- see rule 2. Packages in [ignoredPackages] are skipped rather
     * than returned: the keyguard, the shade and our own screens all own the active window at the
     * exact moments this runs, and none of them is what is underneath.
     *
     * It polls because both callers arrive *before* the window they want exists -- a rebind races
     * the tree coming up, and an unlock races the keyguard going away. It stops at the first
     * readable answer, so the ordinary cost is one read or two rather than [SEED_ATTEMPTS] of them.
     */
    private suspend fun readActiveWindowPackage(attempts: Int = SEED_ATTEMPTS): String? {
        repeat(attempts) {
            val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (pkg != null && pkg !in ignoredPackages) return pkg
            delay(SEED_RETRY_MS)
        }
        return null
    }

    /**
     * Confirms [currentPackage] against the screen when no event is going to: after an unlock, and
     * after a burst of window-state changes whose app switch may have been coalesced away. See
     * [shouldAdoptActiveWindow] for why a game leaves no other route back.
     *
     * [attempts] is one for a settle: our own screens own the active window at exactly the moments
     * a block goes up, and polling through them concludes nothing the next event will not.
     */
    private fun confirmForegroundFromScreen(label: String, attempts: Int) {
        if (foregroundConfirmJob?.isActive == true) return
        val believedAtRequest = currentPackage
        foregroundConfirmJob = scope.launch {
            val pkg = readActiveWindowPackage(attempts)
            withContext(Dispatchers.Main) {
                guard("confirmForeground") {
                    if (!shouldAdoptActiveWindow(pkg, believedAtRequest, currentPackage)) return@guard
                    trace("$label: $currentPackage was stale, active window is $pkg")
                    EnforcementStatus.note("$label: $currentPackage was stale, corrected to $pkg")
                    onForegroundChanged(pkg!!, System.currentTimeMillis())
                }
            }
        }
    }

    /** Main thread. [pkg] is the package that genuinely owns the active window. */
    private fun seedForeground(pkg: String) {
        // A real event beat us to it; it knows better than a poll does.
        if (currentPackage != null) return

        currentPackage = pkg
        val prefs = getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_VISIT_PACKAGE, null) == pkg) {
            currentTargetId = prefs.getString(KEY_VISIT_TARGET, null)
            enforcementUi.restoreFrictionServed(prefs.getString(KEY_VISIT_FRICTION_SERVED, null))
            // So a trip out of the app and back does not lose it again: the read that would
            // re-establish it is exactly the read that a scrolled page cannot give us.
            currentTargetId?.let { visits.remember(pkg, it) }
        }
        trace("seed: $pkg target=$currentTargetId servedFor=${enforcementUi.frictionServedFor()}")

        // Enforce the restored target straight away rather than waiting for the read to confirm
        // it. If the page moved on while we were unbound the read corrects it within the second,
        // and a moment of over-blocking is the right way round to be wrong. A restored website or
        // feed target is still only a guess, though -- it must not throw up a countdown for a page
        // the rebind never actually saw.
        applyTarget(
            currentTargetId ?: appTargetFor(pkg),
            System.currentTimeMillis(),
            confirmed = currentTargetId == null,
        )
        pageInspector.requestInspect(pkg)
    }

    /**
     * Records the visit so the next instance can pick it up. Cheap because it writes only on an
     * actual change -- `applyTarget` runs on every tick and every read.
     */
    private fun persistVisit() {
        val servedFor = enforcementUi.frictionServedFor()
        if (currentPackage == persistedPackage &&
            currentTargetId == persistedTarget &&
            servedFor == persistedFrictionServed
        ) {
            return
        }
        persistedPackage = currentPackage
        persistedTarget = currentTargetId
        persistedFrictionServed = servedFor
        runCatching {
            getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_VISIT_PACKAGE, currentPackage)
                .putString(KEY_VISIT_TARGET, currentTargetId)
                .putString(KEY_VISIT_FRICTION_SERVED, servedFor)
                .apply()
        }
    }

    /**
     * Reports at the end of every path through this, not only the one [applyTarget] takes. A
     * decision reached by the ticker changes what is being charged with no target change and no
     * event of any kind -- an allowance running out is the ordinary case -- and leaving the
     * snapshot behind made the Right now panel say "Counting time against: NecroMerger" with the
     * block screen already up. That panel exists to answer whether time is being charged, so it is
     * the one readout that may not be stale about it.
     */
    private fun evaluateAndEnforce(targetId: String, nowMs: Long) {
        // Before the work, not after: the onset event's whole value is the trail as it stood
        // *before* the storm started overwriting it, and evaluateAndEnforceInner writes to that
        // trail itself.
        detectionJournal.noteEvaluationRate(targetId, nowMs)
        evaluateAndEnforceInner(targetId, nowMs)
        reportStatus()
    }

    /** Every enabled policy covering [targetId]: its own rule, and every group it belongs to. */
    private fun enabledPoliciesCovering(targetId: String): List<Policy> =
        policiesCovering[targetId].orEmpty().filter { it.enabled }

    /**
     * Indexes policies by what they cover rather than by what they are.
     *
     * The one place group membership is turned into coverage, so nothing downstream has to know a
     * group exists -- it asks what covers the target in front and gets a list.
     */
    private fun coverageByTarget(list: List<Policy>): Map<String, List<Policy>> {
        val out = HashMap<String, MutableList<Policy>>()
        for (policy in list) {
            val targets =
                if (policy.kind == TargetKind.GROUP) policy.members else listOf(policy.id)
            for (target in targets) out.getOrPut(target) { mutableListOf() }.add(policy)
        }
        return out
    }

    /**
     * A group's usage is the union of its members', which is the whole reason groups cost the
     * ledger nothing: time is recorded against the target in front whoever asks about it, so a
     * minute on one site is charged to that site's own rule and to every group holding it, at once.
     */
    private fun sessionsFor(policy: Policy, targetId: String, nowMs: Long): List<UsageSession> =
        if (policy.kind == TargetKind.GROUP) {
            policy.chargedTargets.flatMap { ledger.sessionsFor(it, nowMs) }
        } else {
            ledger.sessionsFor(targetId, nowMs)
        }

    private fun evaluateAndEnforceInner(targetId: String, nowMs: Long) {
        val covering = enabledPoliciesCovering(targetId)
        if (covering.isEmpty()) return
        // The cap lapsing fires no onChanged of its own, and a held target reaches this function
        // far more often than the no-target tick path does -- so a workout ending mid-visit on a
        // covered app is logged here rather than waiting for the target to be dropped.
        workout.noteWorkoutTransition(nowMs)
        // Not only from the tick. A busy page re-evaluates several times a second and the tick can
        // legitimately not fire for its whole interval, and if the flush rode solely on the tick a
        // process kill would record the entire visit as zero seconds.
        ledger.touch(nowMs)
        val verdict = PolicyEngine.evaluateAll(
            covering,
            { sessionsFor(it, targetId, nowMs) },
            // The later of the global pause and this target's own scoped pause. A scoped pause
            // relaxes only the app it names, so the game you paused unblocks while a social app
            // covered by another rule stays blocked. See [PauseController.effectivePauseUntil].
            pause.effectivePauseUntil(targetId),
            // Asked about the app in front rather than about the workout, so an allowed app falls
            // through to its own rules with no override in the fold. For a website target that is
            // the browser, which is the app you would have to have allowed.
            WorkoutMode.overrideUntilMs(currentPackage, nowMs),
            nowMs,
            settingsMirror.dayStartHour,
        )
        val decision = verdict.decision
        val policy = verdict.governing ?: return
        trace("decision for ${policy.label} (of ${covering.size}): $decision")
        when (decision) {
            is Decision.Allow -> {
                enforcementUi.hideOverlay()
                val pausedUntil = decision.pausedUntilMs
                // A pause still records the visit — an hour on reddit while paused is an hour
                // spent — but it must not count toward quota. Off-hours is different: the rule
                // does not exist, so nothing is recorded.
                if (pausedUntil != null && !Interstitial.isShowing()) {
                    ledger.startSession(targetId, nowMs, countsTowardQuota = false)
                } else {
                    ledger.closeSession(nowMs)
                }
                // A pause suspends greyscale and friction too; a pause means "get out of my way".
                // So do a policy's off-hours: outside them the rule does not exist, which means no
                // colour drained, no countdown, and nothing charged against the quota.
                refreshGrayscale(nowMs, ruleWantsGrayscale = false)
                workout.refreshWorkoutReminder(nowMs)
                // A pause gets the squircle counting down to when blocking resumes -- the same
                // reason the ring shows what is left of an allowance. Off-hours and no-policy get
                // no readout: nothing is being kept at bay, so there is nothing to count down.
                // Our own screen covering the app is the exception a pause shares with accrual: it
                // would put the squircle over one of our screens, so hold it back the same way.
                if (pausedUntil != null && !Interstitial.isShowing()) {
                    enforcementUi.hideAllowanceCountdown()
                    enforcementUi.showPauseCountdown(pausedUntil, targetId, nowMs)
                } else {
                    enforcementUi.hideCountdown()
                }
                // Different reasons nothing is happening, and "the app is open and nothing happens"
                // is what every failure in this app looks like from the outside. Telling them apart
                // in the trail is the entire point of the Right now panel.
                EnforcementStatus.note(
                    when {
                        pausedUntil != null -> "$targetId: paused, tracking, not charging"
                        // Asked of every rule covering the page, not just the one named: with
                        // nothing paused and nothing blocking, a rule that allows is a rule
                        // outside its hours, and the panel has to say which of the three
                        // reasons-nothing-is-happening this is.
                        covering.any { !it.schedule.isAlwaysOn } ->
                            "$targetId: outside its hours, not charging"
                        else -> "$targetId: no active policy"
                    },
                )
            }

            is Decision.Allowed -> {
                enforcementUi.hideOverlay()
                if (Interstitial.isShowing()) {
                    // One of our own screens is covering the app. You are not using it, so it does
                    // not get to charge you -- friction and cooldowns cost time, not quota.
                    ledger.closeSession(nowMs)
                    // And nothing is counting down, so nothing may say it is. The readout tracks
                    // the ledger exactly, because "it is not there" is what it is for.
                    enforcementUi.hideCountdown()
                    EnforcementStatus.note("$targetId: not charging, one of our screens is up")
                } else {
                    ledger.startSession(targetId, nowMs)
                    // A pause elapsing into accrual on the same target fires no target change, so
                    // the squircle would otherwise linger next to the ring. It is not a pause any more.
                    enforcementUi.hidePauseCountdown()
                    enforcementUi.showCountdown(policy.label, decision, nowMs)
                    EnforcementStatus.note(
                        if (decision.fromPool) "$targetId: charging, on its daily pool" else "$targetId: charging",
                    )
                }
                refreshGrayscale(nowMs, ruleWantsGrayscale = verdict.grayscale)
                workout.refreshWorkoutReminder(nowMs)
                enforcementUi.maybeShowFriction(targetId, verdict.frictionFrom?.label ?: policy.label, verdict.frictionSeconds, nowMs)
            }

            is Decision.Blocked -> {
                // Close first: nextRelease assumes no session runs past now, and we should not
                // keep charging for time spent staring at the block screen.
                ledger.closeSession(nowMs)
                enforcementUi.hideCountdown()
                refreshGrayscale(nowMs, ruleWantsGrayscale = false)
                if (Interstitial.isShowing()) {
                    // The pause flow and the friction countdown already cover the app underneath,
                    // so there is nothing to eject the user from -- painting the block screen on
                    // top of our own would just be one rotblock screen behind another. This does
                    // not fail open: `Interstitial.onCleared` re-enters `reEvaluateCurrent()` the
                    // instant the last of our screens goes away, which puts the block straight
                    // back up, and `isShowing` is itself deadline-bounded so a lost `exited()`
                    // cannot hold this off forever.
                    enforcementUi.hideOverlay()
                    EnforcementStatus.note(
                        "$targetId: blocked, block screen held back while one of our screens is up",
                    )
                } else {
                    EnforcementStatus.note(
                        "$targetId: blocked (${decision.reason.name.lowercase().replace('_', ' ')})",
                    )
                    enforcementUi.showBlock(policy, targetId, decision)
                }
                // After the block goes up, not before: on the pass where a block first appears
                // `overlay.isShowing` is still false beforehand, so a due reminder would slip onto
                // the screen in the instant before the block covered it.
                workout.refreshWorkoutReminder(nowMs)
            }
        }

        // One place, every branch, because every one of them can go stale with nothing happening:
        // a pause elapses, an allowance runs out, a scheduled window opens or closes, a workout
        // reminder comes due -- and not one of those fires an event while the phone sits on the
        // same screen. The engine works out which policy deadline is soonest; workoutDeadlineMs
        // adds the reminder's, since it can fall due on a held target the same as with none. All
        // that is left here is to be awake for whichever is soonest. Read after
        // refreshWorkoutReminder has already run above, so nextReminderAtMs is current.
        val recheckAtMs = soonestMs(decision.recheckAtMs, workout.workoutDeadlineMs(nowMs))
        if (recheckAtMs != null) {
            startTicking(
                recheckAtMs - nowMs,
                // Nothing is accruing on an allow and none of our screens need watching, so there
                // is no reason to wake before the deadline. See [MAX_IDLE_TICK_MS].
                maxIntervalMs = if (decision is Decision.Allow) MAX_IDLE_TICK_MS else MAX_TICK_MS,
            )
        } else {
            stopTicking()
        }
    }

    /**
     * Arms the next re-check for [untilDeadlineMs] from now, clamped into
     * [MIN_TICK_MS]..[maxIntervalMs].
     *
     * Keeps an already-armed tick when it would fire no later than the one being asked for, and
     * that is not an optimisation -- it is what stops the tick being starved. This runs from every
     * evaluation, and a page emitting events several times a second would otherwise cancel and
     * re-post the callback faster than it could ever come due, so the tick would simply never run.
     *
     * Rescheduling *earlier* is always allowed, so the soonest deadline still wins and a late tick
     * can never become a fail-open.
     */
    private fun startTicking(untilDeadlineMs: Long, maxIntervalMs: Long = MAX_TICK_MS) {
        val dueAtMs = System.currentTimeMillis() + untilDeadlineMs.coerceIn(MIN_TICK_MS, maxIntervalMs)
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

    /**
     * Keeps the clock running for workout mode when there is no target to run it for.
     *
     * A workout greys the whole phone and, now, may owe a reminder -- both change what is on
     * screen even when nothing is being enforced and no session is open, and neither fires an
     * event of its own. Armed for the soonest of the cap or a pending reminder, and nothing
     * sooner: with no target there is no ledger to flush and no interstitial to notice, which is
     * the same reasoning [MAX_IDLE_TICK_MS] already carries on [Decision.Allow].
     */
    private fun armForWorkoutOnly(nowMs: Long) {
        val until = workout.workoutDeadlineMs(nowMs)
        if (until == null) stopTicking() else startTicking(until - nowMs, maxIntervalMs = MAX_IDLE_TICK_MS)
    }

    /** The soonest of two deadlines, either of which may be absent. */
    private fun soonestMs(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    /**
     * The single answer to "what should the display look like right now".
     *
     * @param ruleWantsGrayscale whether the target being enforced asks for it. Passed in rather
     *   than remembered, so there is no flag that can be set and never cleared.
     */
    private fun refreshGrayscale(nowMs: Long, ruleWantsGrayscale: Boolean) {
        // The workout app and anything allowed alongside it are exempt: greying Strong's own charts
        // and plate colours, or the album art you put on to train to, is punitive and buys nothing.
        // The moment you leave for something else, the world goes grey.
        val workoutWants = WorkoutMode.overrideUntilMs(currentPackage, nowMs) != null
        if (ruleWantsGrayscale || workoutWants) grayscale.enable() else grayscale.restore()
    }

    /**
     * Asked rather than remembered. A tracked boolean would need a correct value at connect time,
     * which is exactly when this service knows least, and it is read once per workout transition --
     * far too rarely to be worth a field that could go stale. Assumes on when it cannot tell: that
     * is the pre-existing behaviour, and the alternative risks leaving the display grey.
     */
    /**
     * Whether a lock screen still has to be got past.
     *
     * Assumes locked when it cannot tell, which is the safe direction here: the cost of a wrong
     * "locked" is that recovery waits for USER_PRESENT, while a wrong "unlocked" would resume
     * accrual behind a lock screen.
     */
    private fun isKeyguardLocked(): Boolean =
        runCatching {
            (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager)
                .isKeyguardLocked
        }.getOrDefault(true)

    private fun isScreenOn(): Boolean =
        runCatching { (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }
            .getOrDefault(true)

    private fun buildIgnoredPackages(): Set<String> {
        val ignored = mutableSetOf(packageName, ASSUMED_SHADE_PACKAGE)
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
        trace("teardown: $reason")
        // Before the trail records the disconnect, and before the process likely goes away: a
        // storm still running at teardown is exactly the one worth having bracketed.
        detectionJournal.settleEvaluationRate(now)
        EnforcementStatus.setConnected(false)
        stopTicking()
        Interstitial.onCleared = null
        Interstitial.onShown = null
        FrictionGate.onShown = null
        FrictionGate.onHidden = null
        FrictionGate.onServed = null
        FrictionGate.onDeclined = null
        BlockSelfTest.onRequested = null
        BlockSelfTest.ended()
        Diagnostics.onCaptureRequested = null
        // A field on a process-wide singleton holding a reference to this service instance --
        // this service is rebuilt on every rebind, so leaving it set would keep a dead instance
        // reachable and let it act after teardown.
        WorkoutMode.onChanged = null
        runCatching { unregisterReceiver(screenReceiver) }
        outage.noteDown(now, reason)
        if (::diagnostics.isInitialized) diagnostics.abandonInFlight(currentPackage)
        // Including any pending escape check: it would re-show the overlay on a service that is
        // going away, and nothing would be left to take that window down again.
        handler.removeCallbacksAndMessages(null)
        if (::ledger.isInitialized) ledger.closeSession(now)
        detectionJournal.flushDetection(now)
        if (::enforcementUi.isInitialized) {
            enforcementUi.hideOverlay()
            // Enforcement going away must not leave a countdown on screen claiming it is still
            // counting. That is the one lie this readout cannot be allowed to tell.
            enforcementUi.hideCountdown()
        }
        // Enforcement going away must not leave the display greyscale with nothing left running
        // to put it back.
        if (::grayscale.isInitialized) grayscale.restore()
        if (::workout.isInitialized) workout.hideReminder()
        inspectorJob?.cancel()
        watchJob?.cancel()
        reseedJob?.cancel()
        foregroundRecheckJob?.cancel()
        foregroundConfirmJob?.cancel()
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
