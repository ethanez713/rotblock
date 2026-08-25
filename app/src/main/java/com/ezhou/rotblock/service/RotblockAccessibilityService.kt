package com.ezhou.rotblock.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.block.BlockOverlay
import com.ezhou.rotblock.block.BlockSelfTest
import com.ezhou.rotblock.block.Escape
import com.ezhou.rotblock.block.escapeLabelFor
import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.block.Grayscale
import com.ezhou.rotblock.block.FrictionGate
import com.ezhou.rotblock.block.formatDuration
import com.ezhou.rotblock.block.Interstitial
import com.ezhou.rotblock.block.TimerHud
import com.ezhou.rotblock.block.TrackingNotification
import com.ezhou.rotblock.block.WorkoutReminder
import com.ezhou.rotblock.browser.AddressBar
import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.browser.BrowserSpec
import com.ezhou.rotblock.browser.LinkConsensus
import com.ezhou.rotblock.browser.LinkCorroboration
import com.ezhou.rotblock.browser.PageSignature
import com.ezhou.rotblock.browser.escapeIntent
import com.ezhou.rotblock.browser.shouldAttemptReveal
import com.ezhou.rotblock.browser.typeEscape
import com.ezhou.rotblock.browser.WebMatcher
import com.ezhou.rotblock.diag.CaptureInput
import com.ezhou.rotblock.diag.CaptureStore
import com.ezhou.rotblock.diag.Diagnostics
import com.ezhou.rotblock.diag.NodeDumpSnapshot
import com.ezhou.rotblock.diag.NodeSnapshot
import com.ezhou.rotblock.diag.OutageSnapshot
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
import com.ezhou.rotblock.pause.PauseController
import com.ezhou.rotblock.policy.BlockReason
import com.ezhou.rotblock.policy.Decision
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyEngine
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.UsageLedger
import com.ezhou.rotblock.quota.UsageSession
import com.ezhou.rotblock.setup.DetectionCounters
import com.ezhou.rotblock.setup.gatherSetupFacts
import com.ezhou.rotblock.setup.requirementsFor
import com.ezhou.rotblock.ui.FrictionActivity
import com.ezhou.rotblock.ui.PauseRequestActivity
import com.ezhou.rotblock.workout.WorkoutMode
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

/**
 * How often the feed-detection counters reach disk.
 *
 * Long, because nothing depends on them being current: the alarm they feed needs three days of
 * counting before it says anything. Short enough that a process kill costs one window's worth of
 * evidence rather than the window itself.
 */
private const val DETECTION_FLUSH_MS = 5 * 60 * 1000L

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
private const val MAX_LINK_SCAN_NODES = 120
private const val MAX_LINK_URLS_RETAINED = 64
private const val MAX_DESCRIPTION_SAMPLES = 60
private const val MIN_PAGE_SCAN_INTERVAL_MS = 3_000L

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

/**
 * How long a reveal tap is given before the next read is requested. Long enough for the toolbar
 * animation to finish and settle; short enough that the visit is not left unread for a full tick.
 */
private const val REVEAL_FOLLOWUP_DELAY_MS = 500L

/**
 * Enforcement gaps shorter than this are system rebinds, not you switching rotblock off, and are
 * not written to the log. Toggling the service in Settings and coming back takes far longer.
 */
private const val MIN_REPORTABLE_OUTAGE_MS = 30_000L

/**
 * Where the service records that it was running, and when it stopped.
 *
 * Internal rather than private because the setup screen needs the heartbeat: "Android says
 * enforcement is on, and it has not been seen for two hours" is a different diagnosis, with a
 * different fix, from "it is off". See [com.ezhou.rotblock.setup.gatherSetupFacts].
 */
internal const val OUTAGE_PREFS = "rotblock_outage"
// Internal rather than private for the same reason as the heartbeat below: whether the service was
// *told* to stop is what tells a force-stop apart from the user switching rotblock off, and
// [com.ezhou.rotblock.setup.shouldRestoreEnrolment] needs to know which happened before it puts
// rotblock back in Android's list.
internal const val KEY_DOWN_SINCE = "down_since"
private const val KEY_DOWN_REASON = "down_reason"
internal const val KEY_LAST_ALIVE = "last_alive"

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

/** How often the service records that it was still running. */
private const val HEARTBEAT_MS = 60_000L

/**
 * How much extra time a snoozed workout reminder buys, on top of the ordinary interval.
 *
 * Longer than [WorkoutMode.reminderMs] on purpose: the ordinary interval is measuring "you
 * drifted without noticing", and a snooze is you saying you did not -- you are doing something
 * else deliberately, so re-nagging thirty seconds later would just be ignored and trains people
 * to dismiss the reminder on reflex.
 */
private const val REMINDER_SNOOZE_MS = 5 * 60 * 1000L

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
 * How much of the trail an [EvaluationRate] storm carries into the audit log with it.
 *
 * Enough to say what was happening before the rate went up, and bounded because this lands in a
 * single `detail` column that a person reads. The rest of the trail is still in the snapshot a
 * diagnostic capture takes -- if one is taken before the storm overwrites it, which is the whole
 * reason these lines are copied somewhere that outlives the process.
 */
private const val STORM_TRAIL_LINES = 12

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
    private lateinit var hud: TimerHud
    private lateinit var notification: TrackingNotification
    private lateinit var reminder: WorkoutReminder

    /**
     * The countdown-readout settings, copied out of the settings flow.
     *
     * Volatile and read on the main thread; written by the collector. Nothing about enforcement
     * depends on them -- if they were stale for a second, the ring would be a second late, and
     * that is the entire consequence.
     */
    @Volatile private var hudEnabled = true
    @Volatile private var hudOpacityPercent = 55
    @Volatile private var hudNotification = false

    /** Copied out of the settings flow the same way [hudEnabled] is. See [shouldAttemptReveal]. */
    @Volatile private var revealAddressBarEnabled = false

    /** Where the ring was last dropped; -1 means never, and [TimerHud] falls back to the corner. */
    @Volatile private var hudPositionX = -1
    @Volatile private var hudPositionY = -1

    /**
     * Whether the ring was dragged onto the dismiss target during the accrual session in progress.
     *
     * The only place this is set `true` is [TimerHud]'s `onDismissed` callback below. It is cleared
     * `false` in three places, all of them "this session just ended": [hideCountdown] covers most
     * of it (Allow, Blocked, one of our screens covering the app, screen off, teardown), but a
     * target or foreground change can close the ledger session and open a new one directly, without
     * an intervening call to [hideCountdown] -- so [onForegroundChanged] and the target-change
     * branch of `applyTarget` clear it too, at exactly the two spots where [friction]'s own
     * `endVisit()` already sits for the identical reason. [showCountdown] only ever *reads* this --
     * if it also cleared it, a dismissal would not outlive the very next tick, which would make the
     * gesture indistinguishable from doing nothing.
     */
    @Volatile private var hudDismissedThisSession = false

    /**
     * Whether the breathing countdown is still owed for this visit. Reset whenever the target
     * changes or the screen goes off, so friction is once per visit rather than once per
     * accessibility event -- and spent only by the countdown reaching zero, so backing out of it
     * does not buy the visit. See [FrictionGate].
     */
    private val friction = FrictionGate()

    /**
     * Whether the currently-held target is a fact a read established or still a restore's guess.
     * See [TargetConfidence] -- gates the friction countdown, never enforcement itself.
     */
    private val confidence = TargetConfidence()

    /** Main thread only, like [friction]. See [EvaluationRate]. */
    private val evaluationRate = EvaluationRate()

    /** See [noteDetectionLook]. Diagnostic only; never read by anything that decides a block. */
    @Volatile private var detection = DetectionCounters()
    private var detectionFlushedAtMs = 0L

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

    private val inspectRequests = Channel<String>(Channel.CONFLATED)

    /** Memoised `pkg/class` -> is-an-activity answers for [isActivity]. Main thread only. */
    private val activityCache = HashMap<String, Boolean>()

    /** Last time aliveness was written to disk; throttles [beat] to one write a minute. */
    private var lastBeatMs = 0L

    private var currentPackage: String? = null

    /**
     * Volatile because the inspector coroutine reads it to decide whether a target still needs
     * acquiring; every write is still on the main thread.
     */
    @Volatile private var currentTargetId: String? = null

    /**
     * Written on the inspector coroutine the moment a read gets far enough to inspect the target
     * package's own window -- past the null-root and wrong-package guards in
     * [resolveInspectedTarget], whatever it goes on to conclude. Read on the main thread by
     * [verifyEscaped], hence volatile.
     */
    @Volatile private var lastWindowReadAtMs = 0L

    /** Inspector coroutine only. A duplicated scan would be harmless, so no synchronisation. */
    private var lastPageScanMs = 0L

    /** Inspector coroutine only, like [lastPageScanMs]. See [SearchPageMemory]. */
    private val searchPages = SearchPageMemory()

    /** Inspector coroutine only, like [lastPageScanMs]. See [LinkCorroboration]. */
    private val linkCorroboration = LinkCorroboration()

    /** Inspector coroutine only. Last reveal-tap time per package, for [shouldAttemptReveal]'s cooldown. */
    private val revealLastAttemptMs = HashMap<String, Long>()

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

    /**
     * The capture that has been requested but not yet written, so [teardown] can flush it rather
     * than let [handler]'s cancelled poll drop it. Set as soon as the request is gathered, before
     * the poll -- or even the suspending [withCaptureFacts] read -- exists, because a teardown
     * landing in that gap must not lose the capture either. Main thread only, like the rest of this
     * service's mutable state.
     */
    private var captureInFlight: CaptureInput? = null

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
                        settleEvaluationRate(System.currentTimeMillis())
                        stopTicking()
                        overlay.hide()
                        hideCountdown()
                        friction.endVisit()
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
                        // A locked phone is not you being distracted, so the away-timer starts
                        // again from the unlock rather than counting the whole night. USER_PRESENT
                        // already calls reEvaluateCurrent, which restarts the clock through the
                        // normal path -- no reseeding needed here.
                        reminder.hide()
                        awaySinceMs = 0L
                        nextReminderAtMs = 0L
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
                    noteWorkoutTransition(now)
                    refreshGrayscale(now, ruleWantsGrayscale = false)
                    refreshWorkoutReminder(now)
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
            overlay = BlockOverlay(this)
            grayscale = Grayscale(this)
            reminder = WorkoutReminder(this)
            hud = TimerHud(
                this,
                onMoved = { x, y ->
                    // Position is a preference, not enforcement state -- a lost write costs a ring
                    // that resets to the corner once, nothing more, so it does not need guard()'s
                    // audit trail on top of the coroutine already containing whatever it throws.
                    scope.launch { runCatching { Rotblock.settings(this@RotblockAccessibilityService).setHudPosition(x, y) } }
                },
                onDismissed = {
                    handler.post { guard("hudDismissed") {
                        hudDismissedThisSession = true
                        // The ring's whole value is that its absence means something specific --
                        // see TimerHud's doc -- so an absence the user chose has to read
                        // differently in the trail from an absence that means nothing is counting.
                        EnforcementStatus.note("$currentTargetId: ring dismissed for this session")
                    } }
                },
            )
            notification = TrackingNotification(this)
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
                handler.post { guard("frictionShown") { friction.appeared(targetId) } }
            }
            FrictionGate.onHidden = { targetId ->
                handler.post { guard("frictionHidden") { friction.hidden(targetId) } }
            }
            FrictionGate.onServed = { targetId ->
                handler.post { guard("frictionServed") {
                    trace("friction served for $targetId")
                    friction.served(targetId)
                    persistVisit()
                } }
            }
            FrictionGate.onDeclined = { targetId ->
                handler.post { guard("frictionDeclined") {
                    trace("friction declined for $targetId")
                    noteEscaped(targetId)
                    verifyEscaped(targetId, System.currentTimeMillis())
                } }
            }

            // Runs on Setup's own thread, which is already the main thread this service does
            // everything on -- no dispatch needed, unlike the callbacks above that can fire from a
            // background coroutine. guard() returns Unit, not the Boolean this needs to report
            // back to Setup, so the same never-crash contract is inlined here instead.
            BlockSelfTest.onRequested = {
                runCatching { runBlockSelfTest() }
                    .onFailure { if (::audit.isInitialized) audit.recordError("blockSelfTest", it) }
                    .getOrDefault(false)
            }

            // Posted rather than run inline: the QS tile that triggers this is not on this
            // service's main thread by contract, and everything the capture reads below is.
            Diagnostics.onCaptureRequested = { requestedAtMs ->
                handler.post { guard("diagnosticsCapture") { startDiagnosticsCapture(requestedAtMs) } }
            }

            // Fires on liveness or start-time changes -- not on every re-post of Strong's ongoing
            // notification. Posting re-enters this service by way of re-evaluating what should be
            // on screen, so it goes through the handler like every other listener above.
            WorkoutMode.onChanged = {
                handler.post { guard("workoutChanged") { onWorkoutModeChanged() } }
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
                // Same opportunistic slot, for the same reason: this is the one path that runs on
                // every connect, and nothing here may arm a job to do it on a schedule.
                audit.prune(System.currentTimeMillis())
                pause.refresh()

                // Read once rather than from the settings flow below: the flow re-emits on every
                // unrelated settings change, and adopting the stored string again would throw away
                // whatever has been counted since the last flush.
                detection = DetectionCounters.decode(
                    Rotblock.settings(this@RotblockAccessibilityService).current().detectionCounters,
                )

                // Off the main thread on purpose: this reads the node tree, which the event
                // callback is never allowed to do.
                seedForegroundFromScreen()
                launch {
                    Rotblock.settings(this@RotblockAccessibilityService).settings.collectLatest {
                        pause.refresh()
                        hudEnabled = it.hudEnabled
                        hudOpacityPercent = it.hudOpacityPercent
                        hudNotification = it.hudNotification
                        hudPositionX = it.hudPositionX
                        hudPositionY = it.hudPositionY
                        revealAddressBarEnabled = it.revealAddressBar
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
            inspectorJob = scope.launch { runInspector() }
        }
    }

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
                    if (pkg == currentPackage) {
                        requestInspect(pkg)
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
        EnforcementStatus.note("foreground -> $pkg")
        currentPackage = pkg

        // Leaving the app earns the countdown again, however briefly you were gone. Mostly this
        // already fell out of the target changing on the way out and back, but not always -- two
        // browsers showing the same site, or a rule re-acquired before the switch registered --
        // and "once per visit" is the rule, not "once per target". Only a genuine foreground
        // change reaches here; a toast or a heads-up notification must never re-arm it, or the
        // countdown reappears on top of an app you are already using.
        friction.endVisit()

        // Same visit-not-target reasoning as friction: a reveal already tried on the page you left
        // says nothing about the page you are about to land on.
        revealAttemptedThisVisit = false

        // Same reasoning, one line down: a dismissal is scoped to the session that was open when
        // the ring went away, and the line below ends that session unconditionally -- even when
        // the target you land back on reads the same, ledger accounting starts a fresh session for
        // it once this closes the open one. A dismissal from ten minutes ago on the same site must
        // not silently apply to a visit that has not happened yet.
        hudDismissedThisSession = false

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

        requestInspect(pkg)
    }

    /**
     * Runs on the main thread; [targetId] null means nothing here is governed by a policy.
     *
     * @param confirmed whether [targetId] comes from a read rather than a restore -- see
     *   [TargetConfidence]. Every call site must state which it is.
     */
    private fun applyTarget(targetId: String?, nowMs: Long, confirmed: Boolean) {
        val covering = targetId?.let { enabledPoliciesCovering(it) }.orEmpty()
        trace("applyTarget($targetId) policies=${covering.map { it.label }} cur=$currentTargetId servedFor=${friction.servedFor}")
        if (targetId == null || covering.isEmpty()) {
            if (friction.servedFor != null) trace("  ENDING friction visit (no policy for $targetId)")
            if (currentTargetId != null) {
                EnforcementStatus.note("target $currentTargetId -> none (nothing here is covered)")
            }
            // While currentTargetId still names the target the storm was about.
            settleEvaluationRate(nowMs)
            currentTargetId = null
            confidence.clear()
            friction.endVisit()
            ledger.closeSession(nowMs)
            overlay.hide()
            hideCountdown()
            refreshGrayscale(nowMs, ruleWantsGrayscale = false)
            refreshWorkoutReminder(nowMs)
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
            friction.endVisit()
            // The other spot a session can end without an intervening hideCountdown() call --
            // navigating within the same foreground from a dismissed target straight to a
            // different one (a link inside a browser, say), which never routes through
            // onForegroundChanged. See [hudDismissedThisSession]'s doc.
            hudDismissedThisSession = false
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
     * Unlocking, which is a foreground change that arrives with no event to announce it.
     *
     * Two halves, and they answer different questions. The first re-enforces what was believed
     * before the lock straight away, so a block lands with the screen rather than after a poll --
     * and it is also the branch that seeds from nothing when the service was killed while the
     * screen was off. The second questions that belief, which nothing else does: see
     * [shouldAdoptUnlockedForeground].
     *
     * The null case is left to [reEvaluateCurrent] alone rather than run through both, because its
     * [reseed] reads the same window this would and two polls racing for it is one read too many
     * for an answer neither can improve on.
     */
    private fun onUnlocked() {
        reEvaluateCurrent()
        if (currentPackage != null) confirmForegroundFromScreen()
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
        requestInspect(pkg)
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
    private suspend fun readActiveWindowPackage(): String? {
        repeat(SEED_ATTEMPTS) {
            val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (pkg != null && pkg !in ignoredPackages) return pkg
            delay(SEED_RETRY_MS)
        }
        return null
    }

    /**
     * The unlock half of [requestForegroundRecheck]: confirms [currentPackage] against the screen
     * when no event is going to. See [shouldAdoptUnlockedForeground] for why unlock needs its own
     * path and why a game leaves no other one.
     */
    private fun confirmForegroundFromScreen() {
        if (foregroundConfirmJob?.isActive == true) return
        val believedAtRequest = currentPackage
        foregroundConfirmJob = scope.launch {
            val pkg = readActiveWindowPackage()
            withContext(Dispatchers.Main) {
                guard("confirmForeground") {
                    if (!shouldAdoptUnlockedForeground(pkg, believedAtRequest, currentPackage)) return@guard
                    trace("unlock: $currentPackage was stale, active window is $pkg")
                    EnforcementStatus.note("unlock: $currentPackage was stale, corrected to $pkg")
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
            friction.restoreServed(prefs.getString(KEY_VISIT_FRICTION_SERVED, null))
            // So a trip out of the app and back does not lose it again: the read that would
            // re-establish it is exactly the read that a scrolled page cannot give us.
            currentTargetId?.let { visits.remember(pkg, it) }
        }
        trace("seed: $pkg target=$currentTargetId servedFor=${friction.servedFor}")

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
        requestInspect(pkg)
    }

    /**
     * Records the visit so the next instance can pick it up. Cheap because it writes only on an
     * actual change -- `applyTarget` runs on every tick and every read.
     */
    private fun persistVisit() {
        val servedFor = friction.servedFor
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
                        is Inspected.Target -> {
                            visits.remember(pkg, resolved.targetId)
                            resolved.targetId
                        }
                        // A successful read is the only thing allowed to say "nothing here".
                        Inspected.AppOnly -> {
                            visits.forget(pkg)
                            appTargetFor(pkg)
                        }
                        // Keep enforcing whatever was last established. Every other outcome of a
                        // blind read weakens a block; this one cannot.
                        Inspected.Unreadable -> return@guard
                    }
                    // Both branches above are a read that actually landed -- Unreadable already
                    // returned above without reaching here -- so this is exactly the evidence
                    // [TargetConfidence] exists to require before the friction countdown may fire.
                    applyTarget(targetId, System.currentTimeMillis(), confirmed = true)
                }
            }
        }
    }

    /**
     * Reads the screen and hands what it found to [classifyInspection], which decides what it
     * means. Everything node-shaped lives here; every fail-open judgement lives there.
     */
    private fun resolveInspectedTarget(pkg: String): Inspected {
        // Started before rootInActiveWindow, which is itself an IPC and part of what this costs.
        // Elapsed wall time rather than CPU on purpose: the traversal runs in the *other* app's
        // process, so the number that matters is how long that app was held up, not how much of
        // this process's own budget went on it. Nothing outside rotblock can measure that.
        val readStartNs = System.nanoTime()
        val root = rootInActiveWindow
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
                currentTargetId == null &&
                isBrowser &&
                websiteMatchers.isNotEmpty() &&
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
                websiteMatchers.isNotEmpty() &&
                if (url == null) {
                    currentTargetId == null
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
                pkg, feedKey, url, websiteMatchers, linkHost, descriptions, title, signatureLinkUrls,
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
                enabled = revealAddressBarEnabled,
                revealId = revealId,
                url = url,
                chooser = chooser,
                heldTarget = currentTargetId,
                matcherCount = websiteMatchers.size,
                lastAttemptMs = revealLastAttemptMs[pkg] ?: 0L,
                attemptedThisVisit = revealAttemptedThisVisit,
                nowMs = nowMs,
            )
        ) {
            return
        }
        // Counted against the cooldown and the once-per-visit ceiling regardless of what happens
        // below -- a browser whose reveal control never actually works must not be clicked once a
        // minute forever, and a missing node this time is not evidence it will appear next time.
        revealLastAttemptMs[pkg] = nowMs
        revealAttemptedThisVisit = true
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

    private fun AccessibilityNodeInfo.hasViewId(viewId: String): Boolean {
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
    private fun AccessibilityNodeInfo.readAddressBar(spec: BrowserSpec): AddressBar.Read {
        for (viewId in spec.urlBarIds) {
            when (val read = readAddressBarId(viewId)) {
                is AddressBar.Read.Text -> return read
                is AddressBar.Read.Chooser -> return read
                AddressBar.Read.Absent -> Unit
            }
        }
        return AddressBar.Read.Absent
    }

    private fun AccessibilityNodeInfo.readAddressBarId(viewId: String): AddressBar.Read {
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
    private fun AccessibilityNodeInfo.findAddressBarNode(spec: BrowserSpec): AccessibilityNodeInfo? {
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

    /**
     * Types [BrowserRegistry.ESCAPE_URL] straight into [browser]'s own address bar. This is tried
     * before [escapeIntent] because it is what actually worked against Samsung Internet's
     * rejection of that intent -- see [typeEscape].
     */
    private fun typeEscapeInBrowser(browser: String): Boolean {
        val spec = BrowserRegistry.specFor(browser) ?: return false
        val root = rootInActiveWindow ?: return false
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

    /** recycle() is a deprecated no-op from API 33; still worth calling below that. */
    private fun AccessibilityNodeInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { recycle() }
        }
    }

    /**
     * Tally one inspection of a package a feed rule cares about, and whether its ids were found.
     *
     * Two map increments and a clock comparison, deliberately: this runs on the inspector coroutine
     * for every read of YouTube or Instagram, and anything heavier here would be paid for on the
     * busiest path in the app. The disk write happens at most every [DETECTION_FLUSH_MS] and is
     * fire-and-forget.
     *
     * Purely diagnostic. Nothing in [PolicyEngine], [classifyInspection] or [applyTarget] reads
     * these, and losing a window to a crash costs a warning arriving later than it might have.
     */
    private fun noteDetectionLook(pkg: String, foundFeedKey: String?) {
        val nowMs = System.currentTimeMillis()
        val counted = detection.rolled(nowMs).withLook(pkg)
        detection = if (foundFeedKey == null) counted else counted.withHit(foundFeedKey)
        if (nowMs - detectionFlushedAtMs >= DETECTION_FLUSH_MS) flushDetection(nowMs)
    }

    /**
     * Piggybacks on the inspection that just happened rather than arming anything of its own —
     * `CLAUDE.md` forbids a new repeating callback, and a counter is not worth a wakeup.
     */
    private fun flushDetection(nowMs: Long) {
        detectionFlushedAtMs = nowMs
        val encoded = detection.encode()
        scope.launch {
            runCatching {
                Rotblock.settings(this@RotblockAccessibilityService).setDetectionCounters(encoded)
            }
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
        noteEvaluationRate(targetId, nowMs)
        evaluateAndEnforceInner(targetId, nowMs)
        reportStatus()
    }

    /**
     * One audit event when the evaluation rate goes pathological and one when it recovers.
     *
     * In the healthy case this is two field updates and a comparison on a path that runs a few
     * times a second -- the cost only appears once the fault has, which is the same bargain
     * [shouldRecheckForeground]'s throttle makes.
     */
    private fun noteEvaluationRate(targetId: String, nowMs: Long) {
        when (val verdict = evaluationRate.evaluated(nowMs)) {
            EvaluationRate.Verdict.Quiet -> Unit
            is EvaluationRate.Verdict.Began -> recordStormBegan(targetId, verdict.perSecond)
            is EvaluationRate.Verdict.Ended -> recordStormEnded(targetId, verdict)
        }
    }

    /**
     * The wide event: everything that could name the cause, in one row.
     *
     * Every field here is one that was wanted and missing while reading back the storms this was
     * written for -- the gate depth and the friction gate's state because the alternation was
     * between "one of our screens is up" and "charging", the last read because a target flapping
     * would show there, and the trail because whatever *started* it happened before the first line
     * the storm itself wrote.
     */
    private fun recordStormBegan(targetId: String, perSecond: Int) {
        val snapshot = EnforcementStatus.snapshot()
        val preceding = snapshot.trail
            .take(STORM_TRAIL_LINES)
            .joinToString(" | ") { if (it.repeats > 1) "${it.text} x${it.repeats}" else it.text }
        EnforcementStatus.note("evaluation storm: ${perSecond}/s")
        audit.record(
            AuditType.EVALUATION_STORM,
            targetId = targetId,
            detail = "storm began at $perSecond/s" +
                " pkg=${snapshot.currentPackage}" +
                " gate=${Interstitial.currentDepth}" +
                " friction=${friction.servedFor}" +
                " lastRead=${snapshot.lastRead}" +
                " before: $preceding",
        )
    }

    private fun recordStormEnded(targetId: String, ended: EvaluationRate.Verdict.Ended) {
        EnforcementStatus.note("evaluation storm ended after ${ended.durationMs}ms")
        audit.record(
            AuditType.EVALUATION_STORM,
            targetId = targetId,
            detail = "storm ended after ${ended.durationMs}ms, ${ended.evaluations} evaluations",
            durationSeconds = (ended.durationMs / 1000).toInt(),
        )
    }

    /**
     * Brackets a storm that stopped because evaluation stopped -- the target was dropped, the
     * screen went off, the service went away -- rather than because the rate came down.
     */
    private fun settleEvaluationRate(nowMs: Long) {
        val ended = evaluationRate.settle(nowMs)
        if (ended is EvaluationRate.Verdict.Ended) {
            recordStormEnded(currentTargetId ?: "none", ended)
        }
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
        noteWorkoutTransition(nowMs)
        // Not only from the tick. A busy page re-evaluates several times a second and the tick can
        // legitimately not fire for its whole interval, and if the flush rode solely on the tick a
        // process kill would record the entire visit as zero seconds.
        ledger.touch(nowMs)
        val verdict = PolicyEngine.evaluateAll(
            covering,
            { sessionsFor(it, targetId, nowMs) },
            pause.pausedUntilMs,
            // Asked about the app in front rather than about the workout, so an allowed app falls
            // through to its own rules with no override in the fold. For a website target that is
            // the browser, which is the app you would have to have allowed.
            WorkoutMode.overrideUntilMs(currentPackage, nowMs),
            nowMs,
        )
        val decision = verdict.decision
        val policy = verdict.governing ?: return
        trace("decision for ${policy.label} (of ${covering.size}): $decision")
        when (decision) {
            is Decision.Allow -> {
                overlay.hide()
                hideCountdown()
                ledger.closeSession(nowMs)
                // A pause suspends greyscale and friction too; a pause means "get out of my way".
                // So do a policy's off-hours: outside them the rule does not exist, which means no
                // colour drained, no countdown, and nothing charged against the quota.
                refreshGrayscale(nowMs, ruleWantsGrayscale = false)
                refreshWorkoutReminder(nowMs)
                // Three quite different reasons nothing is happening, and "the app is open and
                // nothing happens" is what every failure in this app looks like from the outside.
                // Telling them apart in the trail is the entire point of the Right now panel.
                EnforcementStatus.note(
                    when {
                        decision.pausedUntilMs != null -> "$targetId: paused, not charging"
                        policy.enabled && !policy.schedule.isAlwaysOn ->
                            "$targetId: outside its hours, not charging"
                        else -> "$targetId: no active policy"
                    },
                )
            }

            is Decision.Allowed -> {
                overlay.hide()
                if (Interstitial.isShowing()) {
                    // One of our own screens is covering the app. You are not using it, so it does
                    // not get to charge you -- friction and cooldowns cost time, not quota.
                    ledger.closeSession(nowMs)
                    // And nothing is counting down, so nothing may say it is. The readout tracks
                    // the ledger exactly, because "it is not there" is what it is for.
                    hideCountdown()
                    EnforcementStatus.note("$targetId: not charging, one of our screens is up")
                } else {
                    ledger.startSession(targetId, nowMs)
                    showCountdown(policy.label, decision, nowMs)
                    EnforcementStatus.note("$targetId: charging")
                }
                refreshGrayscale(nowMs, ruleWantsGrayscale = verdict.grayscale)
                refreshWorkoutReminder(nowMs)
                maybeShowFriction(targetId, verdict.frictionFrom?.label ?: policy.label, verdict.frictionSeconds, nowMs)
            }

            is Decision.Blocked -> {
                // Close first: nextRelease assumes no session runs past now, and we should not
                // keep charging for time spent staring at the block screen.
                ledger.closeSession(nowMs)
                hideCountdown()
                refreshGrayscale(nowMs, ruleWantsGrayscale = false)
                if (Interstitial.isShowing()) {
                    // The pause flow and the friction countdown already cover the app underneath,
                    // so there is nothing to eject the user from -- painting the block screen on
                    // top of our own would just be one rotblock screen behind another. This does
                    // not fail open: `Interstitial.onCleared` re-enters `reEvaluateCurrent()` the
                    // instant the last of our screens goes away, which puts the block straight
                    // back up, and `isShowing` is itself deadline-bounded so a lost `exited()`
                    // cannot hold this off forever.
                    overlay.hide()
                    EnforcementStatus.note(
                        "$targetId: blocked, block screen held back while one of our screens is up",
                    )
                } else {
                    EnforcementStatus.note(
                        "$targetId: blocked (${decision.reason.name.lowercase().replace('_', ' ')})",
                    )
                    showBlock(policy, targetId, decision)
                }
                // After the block goes up, not before: on the pass where a block first appears
                // `overlay.isShowing` is still false beforehand, so a due reminder would slip onto
                // the screen in the instant before the block covered it.
                refreshWorkoutReminder(nowMs)
            }
        }

        // One place, every branch, because every one of them can go stale with nothing happening:
        // a pause elapses, an allowance runs out, a scheduled window opens or closes, a workout
        // reminder comes due -- and not one of those fires an event while the phone sits on the
        // same screen. The engine works out which policy deadline is soonest; workoutDeadlineMs
        // adds the reminder's, since it can fall due on a held target the same as with none. All
        // that is left here is to be awake for whichever is soonest. Read after
        // refreshWorkoutReminder has already run above, so nextReminderAtMs is current.
        val recheckAtMs = soonestMs(decision.recheckAtMs, workoutDeadlineMs(nowMs))
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
     * Puts up whichever countdown readouts are switched on. The notification defaults off; the
     * ring defaults on -- see [com.ezhou.rotblock.data.SettingsRepository.hudEnabled].
     *
     * Called only from the branch that opens a session, so the readout and the ledger can never
     * disagree -- an on-screen timer that keeps counting while nothing is being charged would be
     * worse than no timer at all, because its whole value is that you can trust its absence.
     *
     * A dismissal only ever suppresses the *ring*; a session the user dismissed the ring for is
     * still charging, so the notification (unaffected by a dismissal by design) still reflects it.
     */
    private fun showCountdown(label: String, decision: Decision.Allowed, nowMs: Long) {
        if (hudEnabled && !hudDismissedThisSession) {
            hud.show(decision.remainingMs, decision.quotaMs, hudOpacityPercent, hudPositionX, hudPositionY)
        } else {
            hud.hide()
        }
        if (hudNotification) {
            notification.show(label, nowMs + decision.remainingMs)
        } else {
            notification.hide()
        }
    }

    /**
     * Both are no-ops when nothing is showing, so this is safe to call from anywhere.
     *
     * Also where a ring dismissal expires: called from every branch that closes the accrual
     * session it applied to (Allow, Blocked, one of our screens covering the app, screen off,
     * teardown), so clearing the flag here needs no separate bookkeeping for any of those. Two
     * other spots close a session without routing through here -- a foreground change and a
     * target change -- and clear it directly; see [hudDismissedThisSession]'s own doc.
     */
    private fun hideCountdown() {
        hudDismissedThisSession = false
        if (::hud.isInitialized) hud.hide()
        if (::notification.isInitialized) notification.hide()
    }

    /**
     * [targetId] is what is on screen; [policy] is the rule that refused it, and since groups they
     * are not always the same thing. The audit event is filed against the rule -- "most blocked"
     * is a question about rules -- while the escape and its verification are about the page, which
     * is what [verifyEscaped] compares against `currentTargetId`.
     */
    private fun showBlock(policy: Policy, targetId: String, decision: Decision.Blocked) {
        if (!overlay.isShowing) {
            audit.record(
                AuditType.BLOCK_SHOWN,
                targetId = policy.id,
                detail = "${policy.label}: ${decision.reason.name.lowercase().replace('_', ' ')}" +
                    if (policy.id != targetId) " on $targetId" else "",
            )
        }
        val escape = escapeFor(targetId)
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
     * Draws the real block overlay with a synthetic label, so Setup can prove the enforcement
     * *output* actually paints without waiting for a real rule to fire. Refuses while a real block
     * is already up -- this exists to check the pipe works, not to interrupt one that is already
     * doing its job -- and refuses if the window itself could not be created, which is exactly the
     * "SYSTEM_ALERT_WINDOW granted but the OEM still drops the window" state this exists to catch.
     */
    private fun runBlockSelfTest(): Boolean {
        if (overlay.isShowing) return false
        val shown = overlay.show(
            label = "This is a test",
            reason = BlockReason.HARD_BLOCK,
            liftsAtMs = null,
            onPauseRequested = {},
            escape = Escape("I saw it") { guard("blockSelfTestDismiss") { endBlockSelfTest() } },
            singleAction = true,
        )
        if (shown) {
            handler.postDelayed(
                { guard("blockSelfTestTimeout") { endBlockSelfTest() } },
                BlockSelfTest.TIMEOUT_MS,
            )
        }
        return shown
    }

    private fun endBlockSelfTest() {
        overlay.hide()
        BlockSelfTest.ended()
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
    private fun escapeFor(targetId: String): Escape? {
        val kind = Policy.kindOf(targetId) ?: return null
        val label = escapeLabelFor(kind) ?: return null
        val leave: () -> Unit = when (kind) {
            // Unreachable: escapeLabelFor returns null for both and we have already left.
            TargetKind.APP, TargetKind.GROUP -> return null

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
            //
            // Typing into the bar is tried first, since it is what actually works against a
            // browser that rejects the escape intent outright (Samsung Internet: "Invalid
            // address", page unchanged). The intent stays as the fallback for anything below API
            // 30 or with no readable bar, and global back is the last resort if both refuse.
            TargetKind.WEBSITE -> {
                {
                    guard("escapeSite") {
                        val browser = currentPackage
                        val typed = browser != null &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            typeEscapeInBrowser(browser)
                        val navigated = typed || (browser != null && runCatching {
                            startActivity(escapeIntent(browser))
                        }.isSuccess)
                        if (!navigated) performGlobalAction(GLOBAL_ACTION_BACK)
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
    private fun noteEscaped(targetId: String) {
        currentPackage?.let { visits.forget(it) }
        // Whatever is still held is now an assumption again -- it may keep being enforced, since a
        // page you scroll does not clear a block, but it may not throw up a countdown for a page
        // you have just been told you left.
        confidence.clear()
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
    private fun verifyEscaped(targetId: String, escapedAtMs: Long, attempt: Int = 1) {
        currentPackage?.let { requestInspect(it) }
        handler.postDelayed({
            guard("verifyEscapeInspect") {
                currentPackage?.let { requestInspect(it) }
            }
        }, ESCAPE_VERIFY_MS / 2)
        handler.postDelayed({
            guard("verifyEscaped") {
                when (
                    verifyEscapeOutcome(
                        escapedTargetId = targetId,
                        currentTargetId = currentTargetId,
                        escapedAtMs = escapedAtMs,
                        lastWindowReadAtMs = lastWindowReadAtMs,
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
     * Shows the breathing screen once per visit -- once per *wait served*, not once per screen put
     * up. Backing out of the countdown leaves it owed and this puts it straight back. The whole
     * state machine, including the one case that must not be retried, is [FrictionGate].
     *
     * Keyed on the target, never on the rule that asked for the wait. Several policies can cover
     * one page since groups, and a gate keyed on the rule would owe you a separate countdown for
     * each of them -- while [FrictionGate.onDeclined] hands its key back to be compared against
     * `currentTargetId`, which only a target id can ever match. [seconds] is the longest any live
     * rule asks for, so adding a group cannot shorten a wait a rule already demanded.
     */
    private fun maybeShowFriction(targetId: String, label: String, seconds: Int, nowMs: Long) {
        if (seconds <= 0) return
        // A target restored by VisitMemory or the persisted visit, rather than confirmed by a read,
        // may still be *enforced* -- a block it turns out to be wrong about is lifted by the very
        // next read, which is the safe direction this app takes everywhere. It must not drive the
        // countdown, though: unlike a block, a false countdown costs the wait before you can prove
        // it wrong, and one popping up for a page you are not on -- coming back into a browser
        // still sitting on the page you already served this visit's countdown for -- is exactly the
        // false positive that trains distrust in the whole mechanism. So the countdown waits for a
        // read to confirm the target, and if the page stays scrolled and unreadable for the rest of
        // the visit, it simply never re-fires -- the wait was already served for that page.
        if (!confidence.confirmed) {
            trace("friction withheld for $targetId: target not confirmed by a read yet")
            EnforcementStatus.note("$targetId: countdown held, target not yet confirmed by a read")
            return
        }
        if (!friction.shouldLaunch(targetId, Interstitial.isShowing(), nowMs)) {
            trace("friction not launched for $targetId (served=${friction.servedFor})")
            return
        }
        trace("friction SHOWING for $targetId")
        EnforcementStatus.note("$targetId: showing the countdown, wait still owed")
        friction.launched(targetId, nowMs)
        guard("friction") {
            startActivity(
                Intent(this, FrictionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(FrictionActivity.EXTRA_LABEL, label)
                    .putExtra(FrictionActivity.EXTRA_SECONDS, seconds)
                    .putExtra(FrictionActivity.EXTRA_TARGET, targetId)
                    // "Take me back" has the same dead end as the block screen: going home leaves
                    // the browser on the site, so reopening it lands straight back on the page you
                    // just declined. Told which browser, the screen moves it off first.
                    .putExtra(FrictionActivity.EXTRA_BROWSER, browserBehindWebsite(targetId)),
            )
        }
    }

    /** The browser showing a website target, or null for any other kind of block. */
    private fun browserBehindWebsite(targetId: String): String? {
        if (Policy.kindOf(targetId) != TargetKind.WEBSITE) return null
        return currentPackage?.takeIf { BrowserRegistry.specFor(it) != null }
    }

    private fun launchPauseRequest(policy: Policy) {
        guard("launchPauseRequest") {
            overlay.hide()
            startActivity(
                Intent(this, PauseRequestActivity::class.java)
                    // No CLEAR_TASK: the activity is singleTask, so a second request while a
                    // cooldown is already running reaches the live instance through onNewIntent
                    // instead of destroying and recreating it -- which is what lets the cooldown
                    // keep counting rather than restarting from the top on a repeat tap.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(PauseRequestActivity.EXTRA_TARGET_LABEL, policy.label),
            )
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
        val until = workoutDeadlineMs(nowMs)
        if (until == null) stopTicking() else startTicking(until - nowMs, maxIntervalMs = MAX_IDLE_TICK_MS)
    }

    /** The soonest of two deadlines, either of which may be absent. */
    private fun soonestMs(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    /**
     * The next moment workout mode changes what is on screen: the cap lapsing, or a reminder
     * coming due. Callers must run this after [refreshWorkoutReminder] has already run for the
     * same pass, so [nextReminderAtMs] reflects the current away-time rather than the last one.
     */
    private fun workoutDeadlineMs(nowMs: Long): Long? =
        soonestMs(WorkoutMode.activeUntilMs(nowMs), nextReminderAtMs.takeIf { it > 0L })

    /**
     * The away-timer for the "get back to your workout" reminder.
     *
     * A deadline like every other relaxation in this file, not a countdown that ticks on its own
     * -- [nextReminderAtMs] is compared to the clock, and the single ticker gets armed for it
     * through [workoutDeadlineMs]. Called from every site that already calls [refreshGrayscale],
     * because the two ask the same "is this the workout app, is a workout live" question, even
     * though they answer differently once they have it -- greyscale degrades to a colour flip,
     * a reminder can outright fail to show, so they stay separate functions.
     */
    private fun refreshWorkoutReminder(nowMs: Long) {
        // Being in an allowed app is not being away: choosing the next podcast is not drifting, and
        // nagging you out of the app you deliberately allowed is the fastest way to lose the whole
        // feature.
        val away = WorkoutMode.overrideUntilMs(currentPackage, nowMs) != null
        if (!away) {
            awaySinceMs = 0L
            nextReminderAtMs = 0L
            reminder.hide()
            return
        }
        if (awaySinceMs == 0L) {
            awaySinceMs = nowMs
            nextReminderAtMs = nowMs + WorkoutMode.reminderMs
        }
        // Checked before the deadline, not after, because this has to take a reminder *down* as
        // well as decline to put one up: walking from an unblocked app into a blocked one leaves
        // an already-showing nudge stranded on top of the block otherwise, and only the not-away
        // branch above would ever have cleared it.
        //
        // Suppressed rather than rescheduled: a block screen already says a workout is in
        // progress, and one of our own screens means you are looking at rotblock, not being
        // distracted by something else. Leaving it due means it appears the moment the cover
        // goes away, rather than being silently skipped for this interval.
        if (overlay.isShowing || Interstitial.isShowing(nowMs)) {
            reminder.hide()
            return
        }
        if (nowMs < nextReminderAtMs) return
        if (showWorkoutReminder()) nextReminderAtMs = nowMs + WorkoutMode.reminderMs
    }

    /**
     * Puts the reminder up, wired to its two ways out -- both dismiss it, so it is never a dead
     * end. The primary action gets you back to what you chose to do; the quiet one is the way
     * out, same ranking as the block screen's and for the same reason.
     */
    private fun showWorkoutReminder(): Boolean = reminder.show(
        onResume = { guard("workoutReminderResume") { reminder.hide(); launchWorkoutApp() } },
        onSnooze = {
            guard("workoutReminderSnooze") {
                reminder.hide()
                nextReminderAtMs = System.currentTimeMillis() + REMINDER_SNOOZE_MS
                EnforcementStatus.note("workout reminder: snoozed")
            }
        },
    )

    /**
     * A null launch intent means the configured package is not installed, or is not visible
     * under scoped package visibility -- the manifest's `<queries>` LAUNCHER entry already makes
     * every launchable app visible, so this is a bad setting rather than a permissions problem.
     */
    private fun launchWorkoutApp() {
        val intent = packageManager.getLaunchIntentForPackage(WorkoutMode.appPackage)
        if (intent == null) {
            EnforcementStatus.note("workout reminder: cannot launch ${WorkoutMode.appPackage}")
            return
        }
        runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { EnforcementStatus.note("workout reminder: launch failed") }
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

    private fun onWorkoutModeChanged() {
        val nowMs = System.currentTimeMillis()
        noteWorkoutTransition(nowMs)
        // Screen-off is the one state this must not re-evaluate from. A workout ends when you rack
        // the last set, which is routinely with the phone locked in a pocket -- and screen-off does
        // not clear [currentTargetId], so re-evaluating would find the app you locked the phone
        // inside, decide it is allowed again now the workout is over, and open a ledger session for
        // it. That charges quota for a phone nobody is looking at, and over-charging is the one
        // direction the ledger may not err in. It would arm the ticker too, which is exactly the
        // pending-tick-with-the-screen-off that [MAX_IDLE_TICK_MS] argues cannot happen.
        //
        // Nothing is lost by waiting: ACTION_USER_PRESENT re-evaluates on the way back in, which is
        // where the greyscale skipped here gets put right. The transition above is still recorded,
        // because when the workout ended is a fact about the past and does not depend on the screen.
        if (isScreenOn()) reEvaluateCurrent()
    }

    /**
     * Asked rather than remembered. A tracked boolean would need a correct value at connect time,
     * which is exactly when this service knows least, and it is read once per workout transition --
     * far too rarely to be worth a field that could go stale. Assumes on when it cannot tell: that
     * is the pre-existing behaviour, and the alternative risks leaving the display grey.
     */
    private fun isScreenOn(): Boolean =
        runCatching { (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }
            .getOrDefault(true)

    /**
     * Whether the last look found workout mode live. Diagnostic only -- [PolicyEngine] re-derives
     * liveness from the clock every time and never reads this. It exists so the audit log gets
     * one entry per transition rather than one per evaluation.
     */
    private var workoutWasLive = false

    /**
     * When the phone last left the workout app with a workout live, or 0 when it has not. Not a
     * deadline that outlives the process on purpose -- an away-timer that survived a restart
     * would fire a reminder for time spent before rotblock was even running. Written only by
     * [refreshWorkoutReminder], plus the screen-off reset below, never anywhere else.
     */
    private var awaySinceMs = 0L

    /** When the next reminder is due, or 0 when none is pending. See [awaySinceMs]. */
    private var nextReminderAtMs = 0L

    private fun noteWorkoutTransition(nowMs: Long) {
        val live = WorkoutMode.activeUntilMs(nowMs) != null
        if (live == workoutWasLive) return
        workoutWasLive = live
        audit.record(
            if (live) AuditType.WORKOUT_MODE_STARTED else AuditType.WORKOUT_MODE_ENDED,
            detail = if (live) "Workout in progress; every enabled rule is blocking"
                     else "Workout mode ended; normal rules resume",
        )
        EnforcementStatus.note(if (live) "workout mode: started" else "workout mode: ended")
    }

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
            // stoppedAt, not downSince. The fallback three lines up exists precisely because a
            // force-stop leaves downSince at zero, and stamping the row with it wrote the outage
            // at the epoch: 20 such rows found on device, all of them the force-stop case this
            // record must not fail in. They were in the log and unreadable -- sorted to the far
            // end of it, rendered as 1969 -- which is the same failure as not recording them,
            // wearing a costume.
            tsMs = stoppedAt,
        )
        audit.record(
            AuditType.SERVICE_ENABLED,
            detail = "Enforcement back on after ${formatDuration(outage)} with nothing blocked",
        )
    }

    private fun teardown(reason: String) {
        val now = System.currentTimeMillis()
        trace("teardown: $reason")
        // Before the trail records the disconnect, and before the process likely goes away: a
        // storm still running at teardown is exactly the one worth having bracketed.
        settleEvaluationRate(now)
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
                CaptureStore(CaptureStore.directoryFor(this)).write(pending.capturedAtMs, json)
            }
            captureInFlight = null
        }
        // Including any pending escape check: it would re-show the overlay on a service that is
        // going away, and nothing would be left to take that window down again.
        handler.removeCallbacksAndMessages(null)
        if (::ledger.isInitialized) ledger.closeSession(now)
        flushDetection(now)
        if (::overlay.isInitialized) overlay.hide()
        // Enforcement going away must not leave a countdown on screen claiming it is still
        // counting. That is the one lie this readout cannot be allowed to tell.
        hideCountdown()
        // Enforcement going away must not leave the display greyscale with nothing left running
        // to put it back.
        if (::grayscale.isInitialized) grayscale.restore()
        if (::reminder.isInitialized) reminder.hide()
        inspectorJob?.cancel()
        watchJob?.cancel()
        reseedJob?.cancel()
        foregroundRecheckJob?.cancel()
        foregroundConfirmJob?.cancel()
    }

    /**
     * Starts a diagnostic capture: everything that does not need the node tree is gathered here,
     * on the main thread, from live service state; everything that needs a suspending DataStore or
     * Room read is filled in by [withCaptureFacts]; the node dump is last, because it is the one
     * part that has to wait for the shade to close. See [com.ezhou.rotblock.diag.Diagnostics].
     */
    private fun startDiagnosticsCapture(requestedAtMs: Long) {
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
        val appInfo = gatherAppInfo(this)
        val targetId = currentTargetId
        val sessions = targetId?.let { ledger.sessionsFor(it, now) }.orEmpty()
        val covering = targetId?.let { enabledPoliciesCovering(it) }.orEmpty()
        // evaluateAll, not evaluate: the same question evaluateAndEnforce asked, over the same
        // rules. A capture that folded only the target's own rule would show a website allowed
        // while the phone in the user's hand was blocked by a group -- a capture of a different
        // phone, which is the one thing this file may not produce.
        val verdict = targetId?.let { id ->
            PolicyEngine.evaluateAll(
                covering,
                { sessionsFor(it, id, now) },
                pause.pausedUntilMs,
                WorkoutMode.overrideUntilMs(currentPackage, now),
                now,
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
                tickerArmed = ticking,
                tickerDueAtMs = if (ticking) tickDueAtMs else null,
                frictionServedFor = friction.servedFor,
                targetConfidenceConfirmed = confidence.confirmed,
                visitMemory = visits.snapshot(),
                searchPageMemory = searchPages.snapshot(),
                linkCorroborationPending = linkCorroboration.snapshot(),
                revealAttemptedThisVisit = revealAttemptedThisVisit,
                hudDismissedThisSession = hudDismissedThisSession,
                enabledTargets = policiesByTarget.values.filter { it.enabled }.map { it.id },
                websiteMatchers = websiteMatchers,
                coveringPolicyIds = covering.map { it.id },
                governingPolicyId = verdict?.governing?.id,
            ),
            interstitialDepth = Interstitial.currentDepth,
            interstitialShowing = Interstitial.isShowing(now),
            openTargetId = ledger.openTargetId,
            currentDecision = decision,
            currentSessions = sessions.map { SessionRow(it.startMs, it.endMs) },
            pausedUntilMs = pause.pausedUntilMs,
            // Filled in by withCaptureFacts, which needs a suspending read this main-thread pass
            // cannot do.
            pauseBudget = null,
            workout = gatherWorkoutSnapshot(now),
            requirements = emptyList(),
            outage = gatherOutageSnapshot(this),
            phone = gatherPhoneSnapshot(this),
            policies = policiesByTarget.values.map { toPolicySnapshot(it) },
            auditEvents = emptyList(),
            nodeDump = null,
        )
    }

    private suspend fun withCaptureFacts(basics: CaptureInput, now: Long): CaptureInput {
        val settings = Rotblock.settings(this).current()
        val policies = policiesByTarget.values.toList()
        val requirements = requirementsFor(gatherSetupFacts(this, settings, policies, now)).map {
            RequirementSnapshot(it.id.name, it.status.name, it.need.name)
        }
        val budget = runCatching { pause.budgetStatus(now) }.getOrNull()?.let {
            PauseBudgetSnapshot(it.pausesUsed, it.minutesUsed, it.maxPauses, it.maxMinutes)
        }
        val events = runCatching { Rotblock.database(this).auditDao().recent(50) }
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
        val pkg = currentPackage
        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString()
        // A null currentPackage, or a window that disagrees with it, is not a reason to refuse --
        // both are among the actual failures a capture gets taken to investigate, and the ones
        // where a tree dump is worth most. Refusing there would have made the tool blindest in
        // exactly the case it exists for. See canDumpWindow and dumpStatus for the rule and how the
        // disagreement still reaches the reader instead of being recorded as if it agreed.
        if (canDumpWindow(rootPkg, packageName)) {
            val dump = try {
                // Losing the whole capture because the walk threw would be the worst possible
                // failure for a diagnostic: the user taps the tile, nothing appears, and the state
                // they were trying to preserve is gone. Record the failure and write the rest.
                runCatching { buildNodeDump(root!!, rootPkg!!) }
                    .getOrElse { emptyNodeDump("tree walk failed: ${it.javaClass.simpleName}", rootPkg) }
            } finally {
                root.recycleCompat()
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
                CaptureStore(CaptureStore.directoryFor(this@RotblockAccessibilityService))
                    .write(input.capturedAtMs, json)
            }.onFailure { audit.recordError("diagnosticsCapture", it) }
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
            websiteMatchers,
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
