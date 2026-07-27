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

    /**
     * Deadline of the most recently logged expiry, so one is never recorded twice.
     *
     * Seeded from the audit log itself rather than a separate flag, which means it survives
     * process death without adding state that could disagree with the history you are reading.
     */
    @Volatile private var lastLoggedExpiryMs: Long = 0L

    /** Separate from the 0 sentinel: "no expiry has ever been logged" is a real, cacheable answer. */
    @Volatile private var expirySeedLoaded = false

    suspend fun refresh() {
        pausedUntilMs = settings.current().pausedUntilMs
        if (!expirySeedLoaded) {
            lastLoggedExpiryMs =
                runCatching { auditDao.lastTimestampOf(AuditType.PAUSE_EXPIRED.name) }
                    .getOrNull() ?: 0L
            expirySeedLoaded = true
        }
        noteExpiryIfNeeded()
    }

    fun isPaused(nowMs: Long = clock()): Boolean = nowMs < pausedUntilMs

    fun remainingMs(nowMs: Long = clock()): Long = (pausedUntilMs - nowMs).coerceAtLeast(0L)

    /**
     * Records PAUSE_EXPIRED once a pause deadline has passed.
     *
     * Nothing fires when a deadline merely elapses, so this is called opportunistically from
     * whatever happens to run next. The event is therefore stamped with the deadline itself
     * rather than the moment of discovery -- otherwise a pause that ended at 14:30 and was
     * noticed at 21:00 would read, wrongly, as six and a half hours of unblocked phone.
     */
    fun noteExpiryIfNeeded(nowMs: Long = clock()) {
        val deadline = pausedUntilMs
        if (deadline <= 0L || nowMs < deadline || deadline <= lastLoggedExpiryMs) return
        lastLoggedExpiryMs = deadline
        audit.record(
            AuditType.PAUSE_EXPIRED,
            detail = "Pause ended on its own; blocking resumed",
            tsMs = deadline,
        )
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
