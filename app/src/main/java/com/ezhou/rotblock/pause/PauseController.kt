package com.ezhou.rotblock.pause

import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.data.AuditDao
import com.ezhou.rotblock.data.SettingsRepository

/**
 * Owns the one piece of state that decides whether blocking is currently relaxed:
 * `Settings.pausedUntilMs`.
 *
 * There is no "paused" boolean and no timer that ends a pause. A pause is a deadline written to
 * disk, and every read compares it against the clock, so a crash, a force-stop or a reboot all
 * resume blocking on their own. Removing that guarantee removes the whole point of the app.
 */
class PauseController(
    private val settings: SettingsRepository,
    private val auditDao: AuditDao,
    private val audit: AuditLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Cached for the accessibility service, which reads it on every window change and cannot
     * afford a suspending DataStore read. Kept fresh by [refresh] and by every mutation here.
     */
    @Volatile var pausedUntilMs: Long = 0L
        private set

    /** True if a pause was running the last time we checked, used to log expiry exactly once. */
    @Volatile private var sawActivePause = false

    suspend fun refresh() {
        pausedUntilMs = settings.current().pausedUntilMs
        noteExpiryIfNeeded()
    }

    fun isPaused(nowMs: Long = clock()): Boolean = nowMs < pausedUntilMs

    fun remainingMs(nowMs: Long = clock()): Long = (pausedUntilMs - nowMs).coerceAtLeast(0L)

    /** Logs PAUSE_EXPIRED the first time we notice a pause deadline has passed. */
    fun noteExpiryIfNeeded(nowMs: Long = clock()) {
        val active = nowMs < pausedUntilMs
        if (sawActivePause && !active && pausedUntilMs > 0L) {
            audit.record(
                AuditType.PAUSE_EXPIRED,
                detail = "Pause ended on its own; blocking resumed",
            )
        }
        sawActivePause = active
    }

    suspend fun budgetStatus(nowMs: Long = clock()): BudgetStatus {
        val s = settings.current()
        val since = dayStartMs(nowMs, s.dayStartHour)
        val events = auditDao.since(since).filter {
            it.type == AuditType.PAUSE_STARTED || it.type == AuditType.EMERGENCY_SKIP
        }
        // An emergency skip is logged alongside the PAUSE_STARTED it unlocks, so counting both
        // would double-count. Only PAUSE_STARTED carries the granted duration.
        val starts = events.filter { it.type == AuditType.PAUSE_STARTED }
        return BudgetStatus(
            pausesUsed = starts.size,
            minutesUsed = starts.sumOf { (it.durationSeconds ?: 0) } / 60,
            maxPauses = s.maxPausesPerDay,
            maxMinutes = s.maxPauseMinutesPerDay,
        )
    }

    /**
     * Starts a pause of [minutes], extending any pause already running.
     *
     * [emergency] records that the user skipped the cooldown. It never causes the request to be
     * refused -- "I actually need this now" has to have an answer, or you end up disabling
     * policies wholesale again, which is the failure this app exists to prevent. The cost of an
     * emergency skip is that it is loud in the log, not that it is denied.
     */
    suspend fun startPause(
        minutes: Int,
        emergency: Boolean = false,
        reason: String? = null,
        nowMs: Long = clock(),
    ): PauseResult {
        val status = budgetStatus(nowMs)
        val granted = status.grantableMinutes(minutes)

        if (granted == null && !emergency) {
            return PauseResult.Denied(status)
        }
        val effectiveMinutes = granted ?: minutes
        val overBudget = granted == null || granted < minutes

        val until = maxOf(pausedUntilMs, nowMs) + effectiveMinutes * 60_000L
        settings.setPausedUntil(until)
        pausedUntilMs = until
        sawActivePause = true

        if (emergency) {
            audit.record(
                AuditType.EMERGENCY_SKIP,
                detail = "Skipped the cooldown for a $effectiveMinutes min pause",
                userReason = reason,
                overBudget = overBudget,
            )
        }
        audit.record(
            AuditType.PAUSE_STARTED,
            detail = "Paused for $effectiveMinutes min" + if (overBudget) " (over budget)" else "",
            userReason = reason,
            durationSeconds = effectiveMinutes * 60,
            overBudget = overBudget,
        )
        return PauseResult.Started(untilMs = until, grantedMinutes = effectiveMinutes, overBudget = overBudget)
    }

    /** Ends the current pause immediately. Strengthening, so it always applies at once. */
    suspend fun endPauseNow(nowMs: Long = clock()) {
        if (pausedUntilMs <= nowMs) return
        val remainingMin = ((pausedUntilMs - nowMs) / 60_000L).toInt()
        settings.setPausedUntil(0L)
        pausedUntilMs = 0L
        sawActivePause = false
        audit.record(
            AuditType.PAUSE_ENDED_EARLY,
            detail = "Ended pause early with $remainingMin min left",
        )
    }
}

sealed interface PauseResult {
    data class Started(val untilMs: Long, val grantedMinutes: Int, val overBudget: Boolean) : PauseResult
    data class Denied(val status: BudgetStatus) : PauseResult
}
