package com.ezhou.rotblock.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.pause.BudgetStatus
import com.ezhou.rotblock.pause.PauseResult
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.RollingQuota
import com.ezhou.rotblock.quota.UsageSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    val policies: StateFlow<List<Policy>> = db.policyDao().observeAll()
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

    fun addAppPolicy(packageName: String, label: String, quotaSeconds: Int?, windowSeconds: Int) =
        addPolicy(TargetKind.APP, packageName, label, quotaSeconds, windowSeconds)

    fun addPolicy(
        kind: TargetKind,
        matcher: String,
        label: String,
        quotaSeconds: Int?,
        windowSeconds: Int,
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = Policy.targetId(kind, matcher)
            db.policyDao().upsert(
                Policy(
                    id = id,
                    kind = kind,
                    matcher = matcher,
                    label = label,
                    quotaSeconds = quotaSeconds,
                    windowSeconds = windowSeconds,
                    createdAtMs = now,
                    updatedAtMs = now,
                ),
            )
            Rotblock.audit(getApplication()).record(
                AuditType.POLICY_CREATED,
                targetId = id,
                detail = if (quotaSeconds == null) {
                    "Blocked $label completely"
                } else {
                    "Limited $label to ${quotaSeconds / 60} min per ${windowSeconds / 60} min"
                },
            )
            refresh()
        }
    }

    fun deletePolicy(policy: Policy) {
        viewModelScope.launch {
            db.policyDao().delete(policy)
            // Deleting a policy is the strongest possible weakening. Phase 5 routes this through
            // the delay queue; until then it is at least loudly recorded.
            Rotblock.audit(getApplication()).record(
                AuditType.POLICY_DELETED,
                targetId = policy.id,
                detail = "Removed the policy for ${policy.label}",
            )
            refresh()
        }
    }

    fun setPolicyEnabled(policy: Policy, enabled: Boolean) {
        viewModelScope.launch {
            db.policyDao().upsert(
                policy.copy(enabled = enabled, updatedAtMs = System.currentTimeMillis()),
            )
            Rotblock.audit(getApplication()).record(
                if (enabled) AuditType.POLICY_STRENGTHENED else AuditType.WEAKENING_APPLIED,
                targetId = policy.id,
                detail = if (enabled) "Re-enabled ${policy.label}" else "Disabled ${policy.label}",
            )
            refresh()
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

/** Convenience for the UI: total foreground time inside a window. */
fun List<UsageSession>.usedIn(nowMs: Long, windowMs: Long): Long =
    RollingQuota.usedInWindow(this, nowMs, windowMs)
