package com.ezhou.rotblock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.block.BlockOverlay
import com.ezhou.rotblock.block.BlockSelfTest
import com.ezhou.rotblock.block.Escape
import com.ezhou.rotblock.block.FrictionGate
import com.ezhou.rotblock.block.HudShape
import com.ezhou.rotblock.block.Interstitial
import com.ezhou.rotblock.block.TimerHud
import com.ezhou.rotblock.block.TrackingNotification
import com.ezhou.rotblock.block.pauseArcTotalMs
import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.insights.loadDiscouragingStats
import com.ezhou.rotblock.policy.BlockReason
import com.ezhou.rotblock.policy.Decision
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.ui.FrictionActivity
import com.ezhou.rotblock.ui.PauseRequestActivity
import android.content.Context
import android.os.Handler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EnforcementUi(
    dispatch: ServiceDispatch,
    private val serviceGuard: ServiceGuard,
    private val audit: () -> AuditLog,
    private val settings: SettingsMirror,
    private val belief: ForegroundBelief,
    private val actions: AccessibilityActions,
    private val screen: BlockScreenFacts,
    private val persistHudPosition: suspend (Int, Int) -> Unit,
) {
    private val context: Context = dispatch.context
    private val handler: Handler = dispatch.handler
    private val scope: CoroutineScope = dispatch.scope
    private fun guard(where: String, block: () -> Unit) = serviceGuard.guard(where, block)
    private fun trace(message: String) = serviceGuard.trace(message)
    private fun hudEnabled() = settings.hudEnabled
    private fun hudOpacityPercent() = settings.hudOpacityPercent
    private fun hudNotification() = settings.hudNotification
    private fun hudPositionX() = settings.hudPositionX
    private fun hudPositionY() = settings.hudPositionY
    private fun currentPackage() = belief.currentPackage
    private fun currentTargetId() = belief.currentTargetId
    private fun targetConfirmed() = screen.targetConfirmed
    private fun enabledPoliciesCovering(targetId: String) = screen.policiesCovering(targetId)
    private fun pauseSince(targetId: String) = screen.pauseSince(targetId)
    private fun escapeFor(targetId: String) = screen.escapeFor(targetId)
    private fun performGlobalAction(action: Int) = actions.performGlobalAction(action)
    private fun startActivity(intent: Intent) = actions.startActivity(intent)

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
    @Volatile
    var hudDismissedThisSession: Boolean = false
        private set

    /**
     * The pause squircle's own dismissal, tracked separately from [hudDismissedThisSession] because
     * a pause and an accrual are different sessions -- dismissing the squircle while paused must not
     * suppress the ring once blocking resumes and time starts being spent again. Cleared everywhere
     * [hideCountdown] runs, plus the two direct session ends that skip it (see that field's doc).
     */
    @Volatile
    private var pauseHudDismissedThisSession = false

    private fun hudDismissed() = hudDismissedThisSession
    private fun pauseHudDismissed() = pauseHudDismissedThisSession
    private fun noteHudDismissed() { hudDismissedThisSession = true }
    private fun notePauseHudDismissed() { pauseHudDismissedThisSession = true }
    private fun clearAllowanceDismissal() { hudDismissedThisSession = false }
    private fun clearPauseDismissal() { pauseHudDismissedThisSession = false }

    fun clearHudDismissals() {
        hudDismissedThisSession = false
        pauseHudDismissedThisSession = false
    }

    private val overlay = BlockOverlay(context)

    private val hud = TimerHud(
        context,
        onMoved = { x, y ->
            // Position is a preference, not enforcement state -- a lost write costs a ring
            // that resets to the corner once, nothing more, so it does not need guard()'s
            // audit trail on top of the coroutine already containing whatever it throws.
            scope.launch { runCatching { persistHudPosition(x, y) } }
        },
        onDismissed = {
            handler.post { guard("hudDismissed") {
                noteHudDismissed()
                // The ring's whole value is that its absence means something specific --
                // see TimerHud's doc -- so an absence the user chose has to read
                // differently in the trail from an absence that means nothing is counting.
                EnforcementStatus.note("${currentTargetId()}: ring dismissed for this session")
            } }
        },
    )

    /** The squircle counterpart of [hud], shown over a paused app counting down to blocking. */
    private val pauseHud = TimerHud(
        context,
        onMoved = { x, y ->
            // Shares the one stored position with the ring: the two never show at once, so
            // "wherever you last put the countdown" is a single preference, not two.
            scope.launch { runCatching { persistHudPosition(x, y) } }
        },
        onDismissed = {
            handler.post { guard("pauseHudDismissed") {
                notePauseHudDismissed()
                EnforcementStatus.note("${currentTargetId()}: pause squircle dismissed for this session")
            } }
        },
        shape = HudShape.SQUIRCLE,
    )

    private val notification = TrackingNotification(context)

    /**
     * Whether the breathing countdown is still owed for this visit. Reset whenever the target
     * changes or the screen goes off, so friction is once per visit rather than once per
     * accessibility event -- and spent only by the countdown reaching zero, so backing out of it
     * does not buy the visit. See [FrictionGate].
     */
    private val friction = FrictionGate()

    fun overlayShowing(): Boolean = overlay.isShowing

    fun hideOverlay() {
        overlay.hide()
    }

    fun frictionServedFor(): String? = friction.servedFor

    fun endFrictionVisit() {
        friction.endVisit()
    }

    fun restoreFrictionServed(targetId: String?) {
        friction.restoreServed(targetId)
    }

    fun frictionAppeared(targetId: String) {
        friction.appeared(targetId)
    }

    fun frictionHidden(targetId: String) {
        friction.hidden(targetId)
    }

    fun frictionServed(targetId: String) {
        friction.served(targetId)
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
    fun showCountdown(label: String, decision: Decision.Allowed, nowMs: Long) {
        if (hudEnabled() && !hudDismissed()) {
            hud.show(decision.remainingMs, decision.quotaMs, hudOpacityPercent(), hudPositionX(), hudPositionY())
        } else {
            hud.hide()
        }
        if (hudNotification()) {
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
    fun hideCountdown() {
        hideAllowanceCountdown()
        hidePauseCountdown()
    }

    /**
     * Takes down just the allowance readouts (ring and notification) and expires the ring's
     * dismissal, without touching the pause squircle. Separate from [hideCountdown] so the paused
     * branch can turn the ring off -- a pause is not accrual -- while the squircle it is about to
     * show, and its own dismissal, are left alone.
     */
    fun hideAllowanceCountdown() {
        clearAllowanceDismissal()
        hud.hide()
        notification.hide()
    }

    /**
     * Shows the pause squircle over a paused app, counting down to when blocking resumes. The
     * mirror of [showCountdown]: same dismissal-and-enable gate, with the border drained against the
     * pause span from [pauseArcTotalMs].
     */
    fun showPauseCountdown(untilMs: Long, targetId: String, nowMs: Long) {
        if (!hudEnabled() || pauseHudDismissed()) {
            pauseHud.hide()
            return
        }
        val remainingMs = (untilMs - nowMs).coerceAtLeast(0L)
        val totalMs = pauseArcTotalMs(untilMs, pauseSince(targetId), nowMs)
        pauseHud.show(remainingMs, totalMs, hudOpacityPercent(), hudPositionX(), hudPositionY())
    }

    /** Takes the pause squircle down and expires its dismissal, the way [hideCountdown] does the ring's. */
    fun hidePauseCountdown() {
        clearPauseDismissal()
        pauseHud.hide()
    }

    /**
     * [targetId] is what is on screen; [policy] is the rule that refused it, and since groups they
     * are not always the same thing. The audit event is filed against the rule -- "most blocked"
     * is a question about rules -- while the escape and its verification are about the page, which
     * is what [verifyEscaped] compares against `currentTargetId`.
     */
    fun showBlock(policy: Policy, targetId: String, decision: Decision.Blocked) {
        val firstAppearance = !overlay.isShowing
        if (firstAppearance) {
            audit().record(
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
            onPauseRequested = { launchPauseRequest(targetId) },
            escape = escape,
            targetId = targetId,
        )
        if (firstAppearance && shown && decision.reason != BlockReason.WORKOUT_MODE) {
            scope.launch {
                runCatching {
                    val settings = Rotblock.settings(context).current()
                    val stats = loadDiscouragingStats(
                        usageDao = Rotblock.database(context).usageDao(),
                        policyDao = Rotblock.database(context).policyDao(),
                        auditDao = Rotblock.database(context).auditDao(),
                        dayStartHour = settings.dayStartHour,
                        nowMs = System.currentTimeMillis(),
                        targetId = targetId,
                    )
                    if (stats.hasHistory) {
                        withContext(Dispatchers.Main) {
                            overlay.setStats(stats, targetId)
                        }
                    }
                }
            }
        }
        if (!shown) {
            // No overlay permission, or the window manager refused. Blocking still has to block,
            // so the user gets ejected -- but for a website or feed, ejecting alone is the dead
            // end: the app reopens on the same page and ejects again, with no button anywhere to
            // escape it. Move the app off the blocked page first, then leave.
            escape?.action?.invoke()
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    /**
     * Draws the real block overlay with a synthetic label, so Setup can prove the enforcement
     * *output* actually paints without waiting for a real rule to fire. Refuses while a real block
     * is already up -- this exists to check the pipe works, not to interrupt one that is already
     * doing its job -- and refuses if the window itself could not be created, which is exactly the
     * "SYSTEM_ALERT_WINDOW granted but the OEM still drops the window" state this exists to catch.
     */
    fun runBlockSelfTest(): Boolean {
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

    fun endBlockSelfTest() {
        overlay.hide()
        BlockSelfTest.ended()
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
    fun maybeShowFriction(targetId: String, label: String, seconds: Int, nowMs: Long) {
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
        if (!targetConfirmed()) {
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
                Intent(context, FrictionActivity::class.java)
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
        return currentPackage()?.takeIf { BrowserRegistry.specFor(it) != null }
    }

    private fun launchPauseRequest(targetId: String) {
        guard("launchPauseRequest") {
            overlay.hide()
            // The scope selector needs the thing on screen and every group covering it: the target's
            // own name (not the blocking rule's, which may be a group), and each covering group's id,
            // label and members so "pause the whole group" can be offered as a rung.
            val covering = enabledPoliciesCovering(targetId)
            val targetLabel = covering.firstOrNull { it.id == targetId }?.label
                ?: targetId.substringAfter(':')
            val groups = covering.filter { it.kind == TargetKind.GROUP }
            startActivity(
                Intent(context, PauseRequestActivity::class.java)
                    // No CLEAR_TASK: the activity is singleTask, so a second request while a
                    // cooldown is already running reaches the live instance through onNewIntent
                    // instead of destroying and recreating it -- which is what lets the cooldown
                    // keep counting rather than restarting from the top on a repeat tap.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(PauseRequestActivity.EXTRA_TARGET_ID, targetId)
                    .putExtra(PauseRequestActivity.EXTRA_TARGET_LABEL, targetLabel)
                    .putStringArrayListExtra(PauseRequestActivity.EXTRA_GROUP_IDS, ArrayList(groups.map { it.id }))
                    .putStringArrayListExtra(PauseRequestActivity.EXTRA_GROUP_LABELS, ArrayList(groups.map { it.label }))
                    .putStringArrayListExtra(
                        PauseRequestActivity.EXTRA_GROUP_MEMBERS,
                        ArrayList(groups.map { Policy.encodeMembers(it.members) }),
                    ),
            )
        }
    }
}
