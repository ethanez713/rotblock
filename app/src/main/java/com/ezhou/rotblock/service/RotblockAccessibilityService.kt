package com.ezhou.rotblock.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.block.BlockOverlay
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** How often an in-quota target is re-checked while it is on screen. */
private const val TICK_MS = 1_000L

/**
 * The single service that enforces everything.
 *
 * Two rules govern this class, both learned the hard way by every app in this category:
 *
 * 1. **It must never crash.** A dead accessibility service is a silently unblocked phone, which
 *    is worse than no app at all. Every entry point is wrapped in [guard].
 * 2. **Never walk the node tree in [onAccessibilityEvent].** Events arrive at a furious rate;
 *    anything more than a map lookup belongs on a worker. Phase 1 only reads `event.packageName`,
 *    which is free. Browser URL reading (phase 4) will go through a conflated channel.
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

    /** Packages whose windows are not "the app you are using": ours, system UI, keyboards. */
    @Volatile private var ignoredPackages: Set<String> = emptySet()

    private var currentPackage: String? = null
    private var currentTargetId: String? = null
    private var ticking = false
    private var watchJob: Job? = null

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

            watchJob?.cancel()
            watchJob = scope.launch {
                ledger.load(System.currentTimeMillis())
                ledger.prune(System.currentTimeMillis())
                pause.refresh()
                launch {
                    Rotblock.settings(this@RotblockAccessibilityService).settings.collectLatest {
                        pause.refresh()
                        // A pause ending or starting changes what should be on screen right now.
                        handler.post { guard("pauseChanged") { reEvaluateCurrent() } }
                    }
                }
                Rotblock.database(this@RotblockAccessibilityService).policyDao().observeAll()
                    .collectLatest { list ->
                        policiesByTarget = list.associateBy { it.id }
                        handler.post { guard("policiesChanged") { reEvaluateCurrent() } }
                    }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        guard("onAccessibilityEvent") {
            if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return@guard
            val pkg = event.packageName?.toString() ?: return@guard
            if (pkg in ignoredPackages) return@guard
            if (pkg == currentPackage) return@guard

            val now = System.currentTimeMillis()
            onForegroundChanged(pkg, now)
        }
    }

    private fun onForegroundChanged(pkg: String, nowMs: Long) {
        currentPackage = pkg

        // Whatever was accruing time has stopped.
        ledger.closeSession(nowMs)
        stopTicking()

        val targetId = Policy.targetId(TargetKind.APP, pkg)
        val policy = policiesByTarget[targetId]
        if (policy == null || !policy.enabled) {
            currentTargetId = null
            overlay.hide()
            return
        }
        currentTargetId = targetId
        evaluateAndEnforce(targetId, nowMs)
    }

    private fun reEvaluateCurrent() {
        val pkg = currentPackage ?: return
        val now = System.currentTimeMillis()
        val targetId = Policy.targetId(TargetKind.APP, pkg)
        val policy = policiesByTarget[targetId]
        if (policy == null || !policy.enabled) {
            currentTargetId = null
            ledger.closeSession(now)
            stopTicking()
            overlay.hide()
            return
        }
        currentTargetId = targetId
        evaluateAndEnforce(targetId, now)
    }

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
            // No overlay permission, or the window manager refused. Falling back to ejecting the
            // user is blunt, but blocking has to actually block.
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
        if (::ledger.isInitialized) ledger.closeSession(now)
        if (::overlay.isInitialized) overlay.hide()
        // Recorded so that turning rotblock off leaves a mark in the log rather than a silent gap.
        if (::audit.isInitialized) audit.record(AuditType.SERVICE_DISABLED, detail = reason)
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
