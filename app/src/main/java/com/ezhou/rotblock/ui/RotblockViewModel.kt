package com.ezhou.rotblock.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.insights.DayBucket
import com.ezhou.rotblock.insights.TargetSession
import com.ezhou.rotblock.insights.bucketByDay
import com.ezhou.rotblock.insights.countByDay
import com.ezhou.rotblock.insights.recentDayStarts
import com.ezhou.rotblock.data.PendingChange
import com.ezhou.rotblock.data.PendingChangeQueue
import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.pause.BudgetStatus
import com.ezhou.rotblock.pause.PauseResult
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.RollingQuota
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Insights screen renders, assembled in one pass. */
data class InsightsState(
    val days: Int,
    val dayStarts: List<Long>,
    val buckets: List<DayBucket>,
    val targetLabels: Map<String, String>,
    /** Stable series order: creation order, so a colour keeps meaning the same target. */
    val targetOrder: List<String>,
    val pauseCounts: List<Int>,
    val emergencyCounts: List<Int>,
    val totalPauseMinutes: Int,
    val emergencyCount: Int,
    val pauseCount: Int,
    val weakeningCount: Int,
) {
    val totalUsageMs: Long get() = buckets.sumOf { it.totalMs }

    /** Per-target totals over the range, largest first. Feeds the breakdown table. */
    val totalsByTarget: List<Pair<String, Long>>
        get() = buckets
            .flatMap { it.perTarget.entries }
            .groupBy({ it.key }, { it.value })
            .map { (id, values) -> id to values.sum() }
            .sortedByDescending { it.second }
}

/** Live per-policy usage, recomputed whenever the Blocks screen refreshes. */
data class PolicyUsage(
    val usedMs: Long,
    val quotaMs: Long?,
    val nextReleaseMs: Long?,
) {
    val remainingMs: Long? get() = quotaMs?.let { (it - usedMs).coerceAtLeast(0L) }
    val isExhausted: Boolean get() = quotaMs != null && usedMs >= quotaMs
}

class RotblockViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Rotblock.database(app)
    private val settingsRepo = Rotblock.settings(app)
    private val ledger = Rotblock.ledger(app)
    private val pauseController = Rotblock.pause(app)
    private val queue = Rotblock.changeQueue(app)

    val policies: StateFlow<List<Policy>> = db.policyDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingChanges: StateFlow<List<PendingChange>> = db.pendingChangeDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<RotblockSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RotblockSettings())

    val recentEvents: StateFlow<List<AuditEvent>> = db.auditDao().observeRecent(300)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _usage = MutableStateFlow<Map<String, PolicyUsage>>(emptyMap())
    val usage: StateFlow<Map<String, PolicyUsage>> = _usage.asStateFlow()

    private val _budget = MutableStateFlow<BudgetStatus?>(null)
    val budget: StateFlow<BudgetStatus?> = _budget.asStateFlow()

    /**
     * Recomputes usage straight from the database rather than from the ledger's in-memory cache.
     * The UI runs in the same process as the service today, but reading from disk keeps this
     * correct even before the service has ever connected and loaded its cache.
     */
    fun refresh() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // Opportunistic: queued weakenings land whenever someone looks. Applying them late is
            // harmless (the stricter rule simply stayed on longer); applying them early is not.
            queue.applyDue(now)
            val all = db.policyDao().getAll()
            val result = buildMap {
                for (p in all) {
                    val windowMs = p.windowMs
                    val rows = db.usageDao().forTargetSince(p.id, now - windowMs)
                    val sessions = rows.map { it.toSession() }
                    val used = RollingQuota.usedInWindow(sessions, now, windowMs)
                    put(
                        p.id,
                        PolicyUsage(
                            usedMs = used,
                            quotaMs = p.quotaMs,
                            nextReleaseMs = p.quotaMs?.let {
                                RollingQuota.nextRelease(sessions, now, windowMs, it)
                            },
                        ),
                    )
                }
            }
            _usage.value = result
            _budget.value = pauseController.budgetStatus(now)
            pauseController.refresh()
        }
    }

    /**
     * Every policy mutation goes through [PendingChangeQueue], which decides whether it lands now
     * or waits. Nothing in the UI writes to the policy table directly -- that is what keeps the
     * "loosening is delayed" rule from being bypassable by a screen someone forgot to route.
     */
    fun savePolicy(
        kind: TargetKind,
        matcher: String,
        label: String,
        quotaSeconds: Int?,
        windowSeconds: Int,
        frictionSeconds: Int = 0,
        grayscale: Boolean = false,
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = Policy.targetId(kind, matcher)
            val existing = db.policyDao().get(id)
            submit(
                existing,
                Policy(
                    id = id,
                    kind = kind,
                    matcher = matcher,
                    label = label,
                    // An edit must not silently re-enable a rule that was switched off.
                    enabled = existing?.enabled ?: true,
                    quotaSeconds = quotaSeconds,
                    windowSeconds = windowSeconds,
                    frictionSeconds = frictionSeconds,
                    grayscale = grayscale,
                    createdAtMs = existing?.createdAtMs ?: now,
                    updatedAtMs = now,
                ),
            )
        }
    }

    fun deletePolicy(policy: Policy) {
        viewModelScope.launch { submit(policy, null) }
    }

    fun setPolicyEnabled(policy: Policy, enabled: Boolean) {
        viewModelScope.launch { submit(policy, policy.copy(enabled = enabled)) }
    }

    fun cancelPending(change: PendingChange) {
        viewModelScope.launch {
            queue.cancel(change)
            refresh()
        }
    }

    private suspend fun submit(old: Policy?, new: Policy?) {
        // The outcome needs no return channel: an instant apply shows up in the policy list, and
        // a queued weakening shows up as its own countdown card on the Blocks screen.
        queue.submit(old, new)
        refresh()
    }

    private val _insights = MutableStateFlow<InsightsState?>(null)
    val insights: StateFlow<InsightsState?> = _insights.asStateFlow()

    /**
     * Builds everything the Insights screen shows in one pass.
     *
     * Series slots are assigned by the order policies were created, never by how much time each
     * one used -- a colour has to keep meaning the same target between refreshes, or the chart
     * repaints itself every time the ranking shifts.
     */
    fun loadInsights(days: Int) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val settings = settingsRepo.current()
            val dayStarts = recentDayStarts(now, settings.dayStartHour, days)
            val from = dayStarts.first()

            val sessions = db.usageDao().since(from)
                .map { TargetSession(it.targetId, it.startMs, it.endMs) }
            val buckets = bucketByDay(sessions, dayStarts, now)

            val allPolicies = db.policyDao().getAll().sortedBy { it.createdAtMs }
            val labels = allPolicies.associate { it.id to it.label }
            val seen = buckets.flatMap { it.perTarget.keys }.toSet()
            val order = (allPolicies.map { it.id } + seen.sorted()).distinct().filter { it in seen }

            val events = db.auditDao().since(from)
            val pauses = events.filter { it.type == AuditType.PAUSE_STARTED }
            val emergencies = events.filter { it.type == AuditType.EMERGENCY_SKIP }

            _insights.value = InsightsState(
                days = days,
                dayStarts = dayStarts,
                buckets = buckets,
                targetLabels = labels,
                targetOrder = order,
                pauseCounts = countByDay(pauses.map { it.tsMs }, dayStarts, now),
                emergencyCounts = countByDay(emergencies.map { it.tsMs }, dayStarts, now),
                totalPauseMinutes = pauses.sumOf { (it.durationSeconds ?: 0) } / 60,
                emergencyCount = emergencies.size,
                pauseCount = pauses.size,
                weakeningCount = events.count {
                    it.type == AuditType.WEAKENING_QUEUED || it.type == AuditType.POLICY_DELETED
                },
            )
        }
    }

    suspend fun startPause(minutes: Int, emergency: Boolean, reason: String?): PauseResult =
        pauseController.startPause(minutes, emergency, reason).also { refresh() }

    fun endPause() {
        viewModelScope.launch {
            pauseController.endPauseNow()
            refresh()
        }
    }
}

