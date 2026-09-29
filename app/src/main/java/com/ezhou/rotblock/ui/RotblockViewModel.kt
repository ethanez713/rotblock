package com.ezhou.rotblock.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.browser.WebMatcher
import com.ezhou.rotblock.insights.DayBucket
import com.ezhou.rotblock.insights.DiscouragingStatsResult
import com.ezhou.rotblock.insights.RuleEffect
import com.ezhou.rotblock.insights.ScreenTimeSlots
import com.ezhou.rotblock.insights.chartIsWeekly
import com.ezhou.rotblock.insights.countByDay
import com.ezhou.rotblock.insights.effectOf
import com.ezhou.rotblock.insights.foldBucketsIntoWeeks
import com.ezhou.rotblock.insights.foldCountsIntoWeeks
import com.ezhou.rotblock.insights.loadDiscouragingStats
import com.ezhou.rotblock.insights.loadScreenTime
import com.ezhou.rotblock.insights.recentDayStarts
import com.ezhou.rotblock.insights.rotblockedKeys
import com.ezhou.rotblock.insights.screenTimeSlots
import com.ezhou.rotblock.data.PendingChange
import com.ezhou.rotblock.data.PendingChangeQueue
import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.pause.BudgetStatus
import com.ezhou.rotblock.pause.PauseResult
import com.ezhou.rotblock.pause.PauseScope
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyEngine
import com.ezhou.rotblock.policy.PolicySort
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.RollingQuota
import com.ezhou.rotblock.quota.USAGE_RETENTION_MS
import com.ezhou.rotblock.workout.WorkoutMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One page of the audit log. [page] is zero for the newest rows. */
const val AUDIT_PAGE_SIZE = 5

data class AuditPage(
    val events: List<AuditEvent>,
    val count: Int,
    val page: Int,
) {
    val from: Int get() = if (events.isEmpty()) 0 else page * AUDIT_PAGE_SIZE + 1
    val to: Int get() = if (events.isEmpty()) 0 else page * AUDIT_PAGE_SIZE + events.size

    /** "11–15 of 212". */
    val position: String get() = if (count == 0 || events.isEmpty()) "" else "$from–$to of $count"
    val hasNewer: Boolean get() = page > 0
    val hasOlder: Boolean get() = to < count
}

/**
 * Everything the Insights screen renders for one range, folded once.
 *
 * [slots] belong to this load. The screen reads them; it does not re-rank when a bar is tapped,
 * because that would move colours that were assigned for the whole range.
 */
data class InsightsState(
    val days: Int,
    val weekly: Boolean,
    /** Start of each chart bucket: a budget-day, or the oldest day of a week. */
    val dayStarts: List<Long>,
    val buckets: List<DayBucket>,
    val targetLabels: Map<String, String>,
    val rotblockedKeys: Set<String>,
    val slots: ScreenTimeSlots,
    val usageAccessGranted: Boolean,
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

    /**
     * False until the stored settings have actually been read off disk.
     *
     * [settings] starts at its defaults, and one of those defaults is "has never been onboarded" —
     * so without this every cold start flashes the first-run walkthrough at someone who finished it
     * months ago, for as long as DataStore takes to answer. Anything keyed on a settings value
     * meaning *absence* has to wait for this.
     */
    val settingsLoaded: StateFlow<Boolean> = settingsRepo.settings
        .map { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _auditPageIndex = MutableStateFlow(0)

    /** The page the screen asked for. Compared with [AuditPage.page] so a stale page is not shown. */
    val auditPageIndex: StateFlow<Int> = _auditPageIndex.asStateFlow()

    /**
     * Five rows and a count. Collected only while Insights is on screen: [SharingStarted.WhileSubscribed]
     * drops the query once the tab is left. The page index resets to the newest rows on entry.
     */
    val auditLog: StateFlow<AuditPage?> = _auditPageIndex
        .flatMapLatest { page ->
            combine(
                db.auditDao().observePage(AUDIT_PAGE_SIZE, page * AUDIT_PAGE_SIZE),
                db.auditDao().observeCount(),
            ) { events, count ->
                AuditPage(events = events, count = count, page = page)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    fun showAuditPage(page: Int) {
        _auditPageIndex.value = page.coerceAtLeast(0)
    }

    /** Package labels for `app:` keys that are not themselves a policy. Kept for the ViewModel's life. */
    private val applicationLabels = HashMap<String, String>()

    private val _usage = MutableStateFlow<Map<String, PolicyUsage>>(emptyMap())
    val usage: StateFlow<Map<String, PolicyUsage>> = _usage.asStateFlow()

    private val _budget = MutableStateFlow<BudgetStatus?>(null)
    val budget: StateFlow<BudgetStatus?> = _budget.asStateFlow()

    private val _ruleEffects = MutableStateFlow<Map<String, RuleEffect>>(emptyMap())
    val ruleEffects: StateFlow<Map<String, RuleEffect>> = _ruleEffects.asStateFlow()

    /**
     * What each rule has actually done, over the ledger's full retention window. Kept off the
     * one-second [refresh] heartbeat -- it scans 31 days of audit events and, per policy, 31 days
     * of sessions -- and reloaded only when the Blocks screen's rule list changes.
     */
    fun loadRuleEffects() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val since = now - USAGE_RETENTION_MS
            val events = db.auditDao().since(since)
            val all = db.policyDao().getAll()
            _ruleEffects.value = all.associate { p ->
                val sessions = p.chargedTargets
                    .flatMap { db.usageDao().forTargetSince(it, since) }
                    .map { it.toSession() }
                p.id to effectOf(p, sessions, events, now)
            }
        }
    }

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
                    // A group's bar has to add up its members, and every rule's bar has to leave
                    // out hours the rule was not live for -- both through the same two calls the
                    // engine makes, so the bar cannot say something the block screen disagrees with.
                    val rows = p.chargedTargets.flatMap { db.usageDao().forTargetSince(it, now - windowMs) }
                    val sessions = PolicyEngine.chargeable(p, rows.map { it.toSession() }, now)
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
    fun savePolicy(kind: TargetKind, matcher: String, label: String, draft: RuleDraft) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // Website rules are canonicalised so "Reddit.com" and "reddit.com" are one policy
            // rather than two that both fire on the same page.
            val canonical = if (kind == TargetKind.WEBSITE) {
                WebMatcher.canonicalRule(matcher)
            } else {
                matcher
            }
            val id = Policy.targetId(kind, canonical)
            val existing = db.policyDao().get(id)
            // A member typed as a bare host has to end up as the same target id a read produces,
            // or the group covers a site that never matches anything.
            val members = draft.members.map { member ->
                if (Policy.kindOf(member) == TargetKind.WEBSITE) {
                    Policy.targetId(
                        TargetKind.WEBSITE,
                        WebMatcher.canonicalRule(member.removePrefix("web:")),
                    )
                } else {
                    member
                }
            }.distinct()
            submit(
                existing,
                Policy(
                    id = id,
                    kind = kind,
                    matcher = canonical,
                    label = label,
                    // An edit must not silently re-enable a rule that was switched off.
                    enabled = existing?.enabled ?: true,
                    quotaSeconds = draft.quotaSeconds,
                    windowSeconds = draft.windowSeconds,
                    frictionSeconds = draft.frictionSeconds,
                    grayscale = draft.grayscale,
                    poolSeconds = draft.poolSeconds,
                    schedule = draft.schedule,
                    members = members,
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

    private val _discouragingStats = MutableStateFlow<DiscouragingStatsResult?>(null)
    val discouragingStats: StateFlow<DiscouragingStatsResult?> = _discouragingStats.asStateFlow()

    fun loadDiscouragingStatsFor(targetId: String?) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val settings = settingsRepo.current()
            _discouragingStats.value = loadDiscouragingStats(
                usageDao = db.usageDao(),
                policyDao = db.policyDao(),
                auditDao = db.auditDao(),
                dayStartHour = settings.dayStartHour,
                nowMs = now,
                targetId = targetId,
            )
        }
    }

    private val _insights = MutableStateFlow<InsightsState?>(null)
    val insights: StateFlow<InsightsState?> = _insights.asStateFlow()

    private var insightsLoad: Job? = null

    /**
     * One pass for the range Insights is showing. A chip change cancels the previous pass so a
     * slow 365-day read cannot land on top of the range asked for afterwards.
     *
     * [loadScreenTime] rolls completed days up before it reads. Slots are ranked here, once, over
     * that whole range.
     */
    fun loadInsights(days: Int) {
        insightsLoad?.cancel()
        insightsLoad = viewModelScope.launch {
            val built = assembleInsights(days)
            ensureActive()
            _insights.value = built
        }
    }

    private suspend fun assembleInsights(days: Int): InsightsState = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val settings = settingsRepo.current()
        val dayStarts = recentDayStarts(now, settings.dayStartHour, days)
        val from = dayStarts.first()
        val screen = loadScreenTime(getApplication(), dayStarts, now)
        val policies = db.policyDao().getAll()
        val events = db.auditDao().since(from)
        val pauses = events.filter { it.type == AuditType.PAUSE_STARTED }
        val emergencies = events.filter { it.type == AuditType.EMERGENCY_SKIP }
        val weekly = chartIsWeekly(days)
        val chartBuckets = if (weekly) foldBucketsIntoWeeks(screen.buckets) else screen.buckets
        val pauseByDay = countByDay(pauses.map { it.tsMs }, dayStarts, now)
        val emergencyByDay = countByDay(emergencies.map { it.tsMs }, dayStarts, now)
        val rotblocked = rotblockedKeys(policies)
        val keys = screen.buckets.flatMapTo(HashSet()) { it.perTarget.keys }
        InsightsState(
            days = days,
            weekly = weekly,
            dayStarts = chartBuckets.map { it.dayStartMs },
            buckets = chartBuckets,
            targetLabels = labelsFor(keys, policies),
            rotblockedKeys = rotblocked,
            slots = screenTimeSlots(chartBuckets, rotblocked),
            usageAccessGranted = screen.usageAccessGranted,
            pauseCounts = if (weekly) foldCountsIntoWeeks(pauseByDay) else pauseByDay,
            emergencyCounts = if (weekly) foldCountsIntoWeeks(emergencyByDay) else emergencyByDay,
            totalPauseMinutes = pauses.sumOf { (it.durationSeconds ?: 0) } / 60,
            emergencyCount = emergencies.size,
            pauseCount = pauses.size,
            weakeningCount = events.count {
                it.type == AuditType.WEAKENING_QUEUED || it.type == AuditType.POLICY_DELETED
            },
        )
    }

    /**
     * A policy's own label when its id is the key. Otherwise the installed app's label for an
     * `app:` key, and the matcher for anything else. A package outside what this app can see
     * resolves to the package name, and that miss is cached too so the next range does not ask again.
     */
    private fun labelsFor(keys: Set<String>, policies: List<Policy>): Map<String, String> {
        val byId = policies.associate { it.id to it.label }
        return keys.associateWith { key ->
            byId[key]?.takeIf { it.isNotBlank() } ?: if (key.startsWith("app:")) {
                applicationLabel(key.removePrefix("app:"))
            } else {
                key.substringAfter(':', key)
            }
        }
    }

    private fun applicationLabel(pkg: String): String = synchronized(applicationLabels) {
        applicationLabels[pkg]?.let { return it }
        val resolved = runCatching {
            val pm = getApplication<Application>().packageManager
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
        applicationLabels[pkg] = resolved
        resolved
    }

    suspend fun startPause(minutes: Int, scope: PauseScope, emergency: Boolean, reason: String?): PauseResult =
        pauseController.startPause(minutes, scope, emergency, reason).also { refresh() }

    /** The walkthrough is shown once; the Blocks banner is what keeps saying it if it was skipped. */
    fun completeOnboarding() {
        viewModelScope.launch { settingsRepo.setOnboardedAt(System.currentTimeMillis()) }
    }

    /** Presentation only, but stored: an order you have to re-pick every launch is not a setting. */
    fun setBlocksSort(sort: PolicySort) {
        viewModelScope.launch { settingsRepo.setBlocksSort(sort) }
    }

    fun endPause() {
        viewModelScope.launch {
            pauseController.endPauseNow()
            refresh()
        }
    }

    /** Ends the scoped pause on [targetIds] (a card's own target, or a group's members). */
    fun endTargetPause(targetIds: Collection<String>) {
        viewModelScope.launch {
            pauseController.endTargetPauses(targetIds)
            refresh()
        }
    }

    fun setWorkoutModeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setWorkoutModeEnabled(enabled) }
    }

    fun setWorkoutAppPackage(pkg: String) {
        viewModelScope.launch { settingsRepo.setWorkoutAppPackage(pkg) }
    }

    fun setWorkoutReminderSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepo.setWorkoutReminderSeconds(seconds) }
    }

    fun setWorkoutCapMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepo.setWorkoutCapMinutes(minutes) }
    }

    fun setWorkoutAllowedPackages(packages: Set<String>) {
        viewModelScope.launch { settingsRepo.setWorkoutAllowedPackages(packages) }
    }

    /** For when notification access was never granted, or the listener is not running right now. */
    fun startWorkoutNow() {
        WorkoutMode.startManually()
    }

    fun endWorkoutNow() {
        WorkoutMode.endManually()
    }
}

