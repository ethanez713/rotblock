package com.ezhou.rotblock.service

import android.content.Context
import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.block.formatDuration

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

/** How often the service records that it was still running. */
private const val HEARTBEAT_MS = 60_000L

class OutageJournal(
    private val context: Context,
    private val audit: () -> AuditLog,
) {

    /** Last time aliveness was written to disk; throttles [beat] to one write a minute. */
    private var lastBeatMs = 0L

    /**
     * Records that enforcement was running, at most once a minute.
     *
     * Piggy-backs on accessibility events rather than a timer: they arrive whenever the phone is
     * being used, which is exactly when an enforcement gap would matter.
     */
    fun beat() {
        val now = System.currentTimeMillis()
        if (now - lastBeatMs < HEARTBEAT_MS) return
        lastBeatMs = now
        runCatching {
            context.getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_ALIVE, now).apply()
        }
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
    fun reportPreviousOutage() {
        val prefs = context.getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE)
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
            audit().record(AuditType.SERVICE_ENABLED, detail = "Enforcement started")
            return
        }
        val outage = System.currentTimeMillis() - stoppedAt
        if (outage < MIN_REPORTABLE_OUTAGE_MS) return // a rebind, not an event

        audit().record(
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
        audit().record(
            AuditType.SERVICE_ENABLED,
            detail = "Enforcement back on after ${formatDuration(outage)} with nothing blocked",
        )
    }

    fun noteDown(nowMs: Long, reason: String) {
        // onUnbind and onDestroy both land here; keep the first timestamp, which is when
        // enforcement actually stopped.
        runCatching {
            val prefs = context.getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_DOWN_SINCE, 0L) <= 0L) {
                prefs.edit()
                    .putLong(KEY_DOWN_SINCE, nowMs)
                    .putString(KEY_DOWN_REASON, reason)
                    .commit()
            }
        }
    }
}
