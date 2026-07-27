package com.ezhou.rotblock.data

import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.policy.ChangeDirection
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyChange

/**
 * The gate every policy edit goes through.
 *
 * Tightening the rules applies immediately. Loosening them is parked and applied later by
 * [applyDue]. That is fail-closed by construction: if [applyDue] never runs -- process killed,
 * phone off, app never opened -- the stricter old policy simply stays in force. Nothing can be
 * loosened by something failing to happen.
 *
 * There is deliberately no alarm driving this. [applyDue] is called opportunistically whenever
 * the service or the UI looks at policies, so a queued change lands late rather than early.
 */
class PendingChangeQueue(
    private val policyDao: PolicyDao,
    private val pendingDao: PendingChangeDao,
    private val settings: SettingsRepository,
    private val audit: AuditLog,
) {

    /** Result of submitting an edit, so the UI can say what happened. */
    sealed interface Outcome {
        data object AppliedNow : Outcome
        data class Queued(val applyAtMs: Long, val description: String) : Outcome
    }

    /**
     * Submits an edit. [new] being null means "delete this policy".
     *
     * Any queued change for the same target is dropped first: the newest intent wins, and a
     * strengthening edit should not leave an older weakening one waiting to undo it.
     */
    suspend fun submit(old: Policy?, new: Policy?, nowMs: Long = System.currentTimeMillis()): Outcome {
        val targetId = old?.id ?: new?.id ?: return Outcome.AppliedNow
        val direction = PolicyChange.classify(old, new)
        val description = PolicyChange.describe(old, new)

        pendingDao.deleteForTarget(targetId)

        if (direction != ChangeDirection.WEAKENING) {
            applyNow(old, new, nowMs)
            audit.record(
                if (old == null) AuditType.POLICY_CREATED else AuditType.POLICY_STRENGTHENED,
                targetId = targetId,
                detail = description,
            )
            return Outcome.AppliedNow
        }

        val delayMinutes = settings.current().weakeningDelayMinutes
        if (delayMinutes <= 0) {
            applyNow(old, new, nowMs)
            audit.record(AuditType.WEAKENING_APPLIED, targetId = targetId, detail = description)
            return Outcome.AppliedNow
        }

        val applyAtMs = nowMs + delayMinutes * 60_000L
        val change = if (new == null) {
            PendingChange.deletion(targetId, applyAtMs, description, nowMs)
        } else {
            PendingChange.from(new, applyAtMs, description, nowMs)
        }
        pendingDao.insert(change)
        audit.record(
            AuditType.WEAKENING_QUEUED,
            targetId = targetId,
            detail = "$description — takes effect in $delayMinutes min",
            durationSeconds = delayMinutes * 60,
        )
        return Outcome.Queued(applyAtMs, description)
    }

    /** Applies every queued change whose time has come. Safe to call as often as you like. */
    suspend fun applyDue(nowMs: Long = System.currentTimeMillis()) {
        for (change in pendingDao.due(nowMs)) {
            val current = policyDao.get(change.targetId)
            if (change.isDeletion) {
                if (current != null) policyDao.delete(current)
            } else if (current != null) {
                policyDao.upsert(change.applyTo(current, nowMs))
            }
            pendingDao.delete(change.id)
            audit.record(
                AuditType.WEAKENING_APPLIED,
                targetId = change.targetId,
                detail = change.description,
            )
        }
    }

    /** Cancelling a queued weakening keeps the stricter rule, so it takes effect immediately. */
    suspend fun cancel(change: PendingChange) {
        pendingDao.delete(change.id)
        audit.record(
            AuditType.WEAKENING_CANCELLED,
            targetId = change.targetId,
            detail = "Cancelled: ${change.description}",
        )
    }

    private suspend fun applyNow(old: Policy?, new: Policy?, nowMs: Long) {
        when {
            new == null && old != null -> policyDao.delete(old)
            new != null -> policyDao.upsert(new.copy(updatedAtMs = nowMs))
        }
    }
}
