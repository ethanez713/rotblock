package com.ezhou.rotblock.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import com.ezhou.rotblock.block.Escape
import com.ezhou.rotblock.pause.BudgetStatus
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.quota.UsageSession
import kotlinx.coroutines.CoroutineScope

interface ForegroundBelief {
    val currentPackage: String?
    val currentTargetId: String?
    val lastWindowReadAtMs: Long
}

interface ServiceGuard {
    fun guard(where: String, block: () -> Unit)
    fun trace(message: String)
}

interface AccessibilityActions {
    fun rootInActiveWindow(): AccessibilityNodeInfo?
    fun performGlobalAction(action: Int): Boolean
    fun startActivity(intent: Intent)
}

interface ServiceDispatch {
    val context: Context
    val handler: Handler
    val scope: CoroutineScope
}

interface VisitMemoryOps {
    fun remember(pkg: String, targetId: String)
    fun forget(pkg: String)
}

interface HeldConfidence {
    val confirmed: Boolean
    fun clear()
}

interface CoverageIndex {
    val ignoredPackages: Set<String>
    val websiteMatchers: List<String>
}

interface InspectCoordination {
    val revealAttemptedThisVisit: Boolean
    fun markRevealAttempted()
    fun foregroundSettlePending(): Boolean
    fun scheduleForegroundSettle()
}

interface InspectedTargetSink {
    fun recordInspectError(error: Throwable)
    fun noteDetectionLook(pkg: String, foundFeedKey: String?)
    fun rememberVisit(pkg: String, targetId: String)
    fun forgetVisit(pkg: String)
    fun appTargetFor(pkg: String): String
    fun applyTarget(targetId: String?, nowMs: Long, confirmed: Boolean)
}

interface EscapeFollowUp {
    fun requestInspect(pkg: String)
    fun evaluateAndEnforce(targetId: String, nowMs: Long)
    fun persistVisit()
}

interface BlockScreenFacts {
    val targetConfirmed: Boolean
    fun policiesCovering(targetId: String): List<Policy>
    fun pauseSince(targetId: String): Long
    fun escapeFor(targetId: String): Escape?
}

interface WorkoutHooks {
    fun blockOverlayShowing(): Boolean
    fun reEvaluate()
    fun screenOn(): Boolean
    fun launchIntent(packageName: String): Intent?
    fun soonest(a: Long?, b: Long?): Long?
}

interface CaptureAccounting {
    fun sessionsForTarget(targetId: String, nowMs: Long): List<UsageSession>
    fun sessionsForPolicy(policy: Policy, targetId: String, nowMs: Long): List<UsageSession>
    fun policiesCovering(targetId: String): List<Policy>
    fun pauseUntil(targetId: String): Long
    suspend fun pauseBudget(nowMs: Long): BudgetStatus?
}
