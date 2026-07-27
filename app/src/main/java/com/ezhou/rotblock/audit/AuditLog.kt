package com.ezhou.rotblock.audit

import android.util.Log
import com.ezhou.rotblock.data.AuditDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "rotblock"

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

    /** Records a swallowed exception. Called from the service's catch-all wrapper. */
    fun recordError(where: String, t: Throwable) {
        Log.e(TAG, "swallowed error in $where", t)
        record(AuditType.INTERNAL_ERROR, detail = "$where: ${t.javaClass.simpleName}: ${t.message}")
    }
}
