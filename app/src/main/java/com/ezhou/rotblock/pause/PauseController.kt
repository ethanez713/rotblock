package com.ezhou.rotblock.pause

import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.data.AuditDao
import com.ezhou.rotblock.data.SettingsRepository
import com.ezhou.rotblock.workout.WorkoutMode

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
     * Per-target pause deadlines, cached for the service the same way [pausedUntilMs] is. The narrow
     * scopes: "this app" is one entry, "this group" is one per member, "everything" is
     * [pausedUntilMs] instead. Kept fresh by [refresh] and every mutation here.
     */
    @Volatile var pausedTargets: Map<String, Long> = emptyMap()
        private set

    /**
     * Grant times for the current pauses -- [pausedSinceMs] for the global one, [pausedTargetsSince]
     * per scoped target -- cached the same way the deadlines above are. The pause squircle drains
     * its border against `until - since`; nothing in enforcement reads either. See
     * [effectivePauseSince].
     */
    @Volatile var pausedSinceMs: Long = 0L
        private set

    @Volatile var pausedTargetsSince: Map<String, Long> = emptyMap()
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
        val s = settings.current()
        pausedUntilMs = s.pausedUntilMs
        pausedTargets = s.pausedTargets
        pausedSinceMs = s.pausedSinceMs
        pausedTargetsSince = s.pausedTargetsSince
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
     * When blocking is relaxed for [targetId]: the later of the global pause and this target's own
     * scoped pause. This is the single value enforcement folds -- the engine is unchanged and still
     * takes one pause deadline, because a global pause covers every target and a scoped one covers
     * exactly itself.
     */
    fun effectivePauseUntil(targetId: String): Long =
        maxOf(pausedUntilMs, pausedTargets[targetId] ?: 0L)

    /**
     * The grant time of whichever pause [effectivePauseUntil] returns -- the scoped one when it is
     * the later deadline, the global one otherwise. Paired with that deadline, it gives the pause
     * squircle the span to drain its border against. 0 when the governing pause predates anchor
     * tracking, which the caller reads as "draw a full, non-draining border".
     */
    fun effectivePauseSince(targetId: String): Long {
        val scopedUntil = pausedTargets[targetId] ?: 0L
        return if (scopedUntil > pausedUntilMs) pausedTargetsSince[targetId] ?: 0L else pausedSinceMs
    }

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
        scope: PauseScope = PauseScope.Everything,
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
        val addMs = effectiveMinutes * 60_000L

        // Extend any pause already covering the same scope rather than replacing it, so asking again
        // never shortens what is running -- the same rule the global pause always followed.
        val until = when (scope) {
            is PauseScope.Everything -> {
                // Anchor the squircle's total only when starting from rest; extending a running
                // pause keeps the original grant, so the border measures against the whole span.
                val wasRunning = pausedUntilMs > nowMs
                val end = maxOf(pausedUntilMs, nowMs) + addMs
                settings.setPausedUntil(end)
                pausedUntilMs = end
                if (!wasRunning) {
                    settings.setPausedSince(nowMs)
                    pausedSinceMs = nowMs
                }
                end
            }
            is PauseScope.Scoped -> {
                // Prune deadlines already elapsed while writing, so the map cannot grow without
                // bound and the tile's count stays honest. A past entry was ignored anyway.
                val next = pausedTargets.filterValues { it > nowMs }.toMutableMap()
                val nextSince = pausedTargetsSince.filterKeys { next.containsKey(it) }.toMutableMap()
                var end = nowMs
                for (id in scope.targets) {
                    val wasRunning = (next[id] ?: 0L) > nowMs
                    val e = maxOf(next[id] ?: 0L, nowMs) + addMs
                    next[id] = e
                    if (!wasRunning) nextSince[id] = nowMs
                    end = maxOf(end, e)
                }
                settings.setPausedTargets(next)
                settings.setPausedTargetsSince(nextSince)
                pausedTargets = next
                pausedTargetsSince = nextSince
                end
            }
        }

        if (emergency) {
            // Captured before endManually() clears it -- otherwise a skip taken with no workout
            // running would read the post-call state and wrongly claim to have ended one.
            val endedWorkout = WorkoutMode.activeUntilMs(nowMs) != null
            WorkoutMode.endManually()
            audit.record(
                AuditType.EMERGENCY_SKIP,
                targetId = scope.auditTargetId,
                detail = "Skipped the cooldown for a $effectiveMinutes min pause of ${scope.label}" +
                    if (endedWorkout) "; ended workout mode" else "",
                userReason = reason,
                overBudget = overBudget,
                // Same stamp as the PAUSE_STARTED below: foldEmergencyMinutes joins the two rows on
                // an identical timestamp, and two separate clock reads can straddle a millisecond
                // and drop the skip from the emergency stats.
                tsMs = nowMs,
            )
        }
        audit.record(
            AuditType.PAUSE_STARTED,
            targetId = scope.auditTargetId,
            detail = "Paused ${scope.label} for $effectiveMinutes min" +
                if (overBudget) " (over budget)" else "",
            userReason = reason,
            durationSeconds = effectiveMinutes * 60,
            overBudget = overBudget,
            tsMs = nowMs,
        )
        return PauseResult.Started(untilMs = until, grantedMinutes = effectiveMinutes, overBudget = overBudget)
    }

    /** Ends the current pause immediately. Strengthening, so it always applies at once. */
    suspend fun endPauseNow(nowMs: Long = clock()) {
        if (pausedUntilMs <= nowMs) return
        val remainingMin = ((pausedUntilMs - nowMs) / 60_000L).toInt()
        settings.setPausedUntil(0L)
        pausedUntilMs = 0L
        settings.setPausedSince(0L)
        pausedSinceMs = 0L
        audit.record(
            AuditType.PAUSE_ENDED_EARLY,
            detail = "Ended pause early with $remainingMin min left",
        )
    }

    /**
     * Ends the scoped pause on [targetIds] immediately, leaving the global pause and other targets
     * untouched. Strengthening, so it applies at once. A no-op for targets not currently paused.
     */
    suspend fun endTargetPauses(targetIds: Collection<String>, nowMs: Long = clock()) {
        val next = pausedTargets.filterValues { it > nowMs }.toMutableMap()
        val removed = targetIds.filter { next.remove(it) != null }
        if (removed.isEmpty() && next.size == pausedTargets.size) return
        val nextSince = pausedTargetsSince.filterKeys { next.containsKey(it) }
        settings.setPausedTargets(next)
        settings.setPausedTargetsSince(nextSince)
        pausedTargets = next
        pausedTargetsSince = nextSince
        if (removed.isNotEmpty()) {
            audit.record(
                AuditType.PAUSE_ENDED_EARLY,
                detail = "Ended scoped pause early: ${removed.joinToString(", ")}",
            )
        }
    }
}

/**
 * How wide a pause reaches. [Everything] is the global deadline that unblocks the whole phone;
 * [Scoped] unblocks just the listed target ids -- one for "this app", a group's members for
 * "this group" -- so pausing a game never unblocks a social app.
 */
sealed interface PauseScope {
    /** For the audit log: the human-readable scope, e.g. "everything", "NecroMerger", "group Brainrot". */
    val label: String

    /** Filed on the audit event when the scope is a single target, so history can point at it; null otherwise. */
    val auditTargetId: String?

    data object Everything : PauseScope {
        override val label: String = "everything"
        override val auditTargetId: String? = null
    }

    data class Scoped(val targets: List<String>, override val label: String) : PauseScope {
        override val auditTargetId: String? = targets.singleOrNull()
    }
}

sealed interface PauseResult {
    data class Started(val untilMs: Long, val grantedMinutes: Int, val overBudget: Boolean) : PauseResult
    data class Denied(val status: BudgetStatus) : PauseResult
}
