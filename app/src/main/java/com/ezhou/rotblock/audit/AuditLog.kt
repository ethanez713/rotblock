package com.ezhou.rotblock.audit

import android.util.Log
import com.ezhou.rotblock.data.AuditDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "rotblock"

/**
 * How long an audit event is kept.
 *
 * Six months rather than the [com.ezhou.rotblock.quota.USAGE_RETENTION_MS] month, because the two
 * answer different questions. Usage rows feed rolling windows and the charts, and nothing reads
 * one older than its window. The audit log is read by a *person*, later, asking whether they have
 * been leaning on the escape hatches -- and the failure this app exists to prevent ("disabled a
 * policy for one minute, forgot for three weeks") is a shape you only see across months. A window
 * shorter than the pattern hides the pattern.
 *
 * It is a cutoff and nothing else: see [com.ezhou.rotblock.audit.AuditEvent]'s note on why the
 * *blanket* form is what keeps this from being a tidy-up.
 */
const val AUDIT_RETENTION_MS = 180L * 24 * 60 * 60 * 1000

/**
 * Write side of the audit log.
 *
 * Every method is fire-and-forget on the supplied scope: logging must never be able to block or
 * crash the accessibility service. If the write itself fails, we drop to logcat rather than
 * letting the exception escape.
 */
class AuditLog(
    private val dao: AuditDao,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun record(
        type: AuditType,
        targetId: String? = null,
        detail: String = "",
        userReason: String? = null,
        durationSeconds: Int? = null,
        overBudget: Boolean = false,
        /**
         * When the thing actually happened, if that differs from now. Used for events noticed
         * late -- a pause expiring is detected whenever something next looks at the clock, but it
         * belongs in the log at its deadline, not at the moment of discovery.
         */
        tsMs: Long = clock(),
    ) {
        val event = AuditEvent(
            tsMs = tsMs,
            type = type,
            targetId = targetId,
            detail = detail,
            userReason = userReason,
            durationSeconds = durationSeconds,
            overBudget = overBudget,
        )
        scope.launch {
            try {
                dao.insert(event)
            } catch (t: Throwable) {
                Log.e(TAG, "failed to write audit event $type", t)
            }
        }
    }

    /**
     * Drops events past [AUDIT_RETENTION_MS], and is the only thing in the app that deletes one.
     *
     * Opportunistic, like every other periodic job here: called from the service's connect path,
     * never from an alarm. Nothing in this app may wake the phone, and a log pruned late costs
     * some rows staying a while longer.
     *
     * Note this also collects anything stamped at or near the epoch, which is "infinitely old" to
     * a cutoff. That is the right answer for a retention rule -- and the bug that produced such
     * rows is fixed in `reportPreviousOutage`, so it is a one-off sweep of records that rendered
     * as 1969 and could never have been read anyway.
     */
    fun prune(nowMs: Long = clock()) {
        scope.launch {
            try {
                dao.prune(nowMs - AUDIT_RETENTION_MS)
            } catch (t: Throwable) {
                Log.e(TAG, "failed to prune the audit log", t)
            }
        }
    }

    /** Records a swallowed exception. Called from the service's catch-all wrapper. */
    fun recordError(where: String, t: Throwable) {
        Log.e(TAG, "swallowed error in $where", t)
        record(AuditType.INTERNAL_ERROR, detail = "$where: ${t.javaClass.simpleName}: ${t.message}")
    }
}
