package com.ezhou.rotblock.workout

/**
 * Whether a live Strong workout should override every enabled rule -- derived, not persisted.
 *
 * There is deliberately no DataStore key or Room row here, for the same reason a [Schedule] is
 * never stored: the fact this is derived from -- Strong's own ongoing notification -- already
 * lives durably outside this process and survives a rotblock crash on its own. A process restart
 * re-derives liveness from [android.service.notification.NotificationListenerService.getActiveNotifications],
 * exactly the way a schedule re-derives "inside its hours" from the wall clock rather than from a
 * flag that would need restoring.
 *
 * No Android imports on purpose -- every android.* type (the listener, the notification, its
 * flags) stays in [WorkoutListener], so this object is a plain fold over timestamps a unit test
 * can drive on the JVM.
 */
object WorkoutMode {

    enum class Source { NONE, NOTIFICATION, MANUAL }

    // --- Config, pushed from settings by RotblockApp's collector. See its own comment for why
    // that is the one writer -- both services in this process can independently be the only one
    // alive, and two writers to the same field would be two places to keep in step. ---

    @Volatile var enabled: Boolean = false
    @Volatile var appPackage: String = "io.strongapp.strong"
    @Volatile var capMs: Long = 3 * 60 * 60 * 1000L
    @Volatile var reminderMs: Long = 60_000L

    /** Diagnostics only -- nothing here feeds a decision except through [activeUntilMs]. */
    @Volatile var startedAtMs: Long = 0L
        private set

    @Volatile var source: Source = Source.NONE
        private set

    /**
     * The resolved start of a NOTIFICATION-sourced workout that [endManually] most recently
     * dismissed, or null. Strong re-posts its ongoing notification on every logged set, and
     * without this a dismissal would be undone by the very next re-post -- see [endManually].
     */
    @Volatile private var dismissedNotificationStartMs: Long? = null

    /**
     * Fired whenever liveness or the start time actually changes -- never on a call that leaves
     * both the same, which is the common case while Strong keeps re-posting.
     *
     * On the main thread, but only because every caller happens to be there: a
     * NotificationListenerService dispatches its callbacks on the process main looper, and the
     * manual start/stop comes from the UI. The service's mutable state is main-thread-only, so if
     * a caller is ever added off it, that caller marshals -- this object cannot, having no Android
     * types to do it with.
     *
     * Note what does *not* fire this: [enabled] flipping. Config arrives on the settings flow, and
     * whatever reads that flow is already re-evaluating for its own reasons.
     */
    @Volatile var onChanged: (() -> Unit)? = null

    /**
     * The deadline a workout-mode block lifts at, or null when nothing is overriding. A pure fold
     * over the clock and the two fields above, exactly like [PolicyEngine.evaluate] itself -- so a
     * crash mid-workout re-derives the same answer once [WorkoutListener] reconnects, rather than
     * needing a flag that says "currently blocking" restored from somewhere.
     */
    fun activeUntilMs(nowMs: Long = System.currentTimeMillis()): Long? {
        if (!enabled || source == Source.NONE) return null
        val until = startedAtMs + capMs
        return if (nowMs < until) until else null
    }

    /**
     * Which timestamp the cap is measured from.
     *
     * Strong sets `Notification.when` to the real workout start, which is *heuristic*: it lets the
     * cap run from when the workout actually began rather than from whenever this process happened
     * to notice, but nothing enforces that Strong keeps doing so. A future version that stops
     * setting it, or a bad clock, must degrade to measuring from first sighting rather than
     * producing a cap in the past or absurdly far in the future -- so anything that is not a sane
     * past timestamp (strictly positive, and not more than a minute of clock slack ahead of
     * [seenAtMs]) falls back to [seenAtMs].
     */
    fun workoutStartFrom(notificationWhenMs: Long, seenAtMs: Long): Long =
        if (notificationWhenMs > 0 && notificationWhenMs <= seenAtMs + 60_000L) {
            notificationWhenMs
        } else {
            seenAtMs
        }

    /** Called by [WorkoutListener] only, on every sighting of the ongoing notification. */
    fun onWorkoutSeen(notificationWhenMs: Long, nowMs: Long = System.currentTimeMillis()) {
        // A manual start is not overridden by the listener seeing the same app's notification --
        // whichever source is already live stays live, and the two never fight over startedAtMs.
        if (source == Source.MANUAL) return
        val resolvedStart = workoutStartFrom(notificationWhenMs, nowMs)
        // A dismissal names a specific resolved start, not "any notification" -- Strong re-posts
        // seconds after "end workout mode" is tapped, and without this the very next re-post would
        // resurrect the mode the user just ended. A *different* start (a new workout) is not what
        // was dismissed and must start the mode again normally.
        if (resolvedStart == dismissedNotificationStartMs) return
        val changed = source != Source.NOTIFICATION || startedAtMs != resolvedStart
        source = Source.NOTIFICATION
        startedAtMs = resolvedStart
        if (changed) onChanged?.invoke()
    }

    /** Called by [WorkoutListener] when Strong's ongoing notification is gone. */
    fun onWorkoutGone() = clearNotificationSource()

    /** Called by [WorkoutListener] on disconnect, and at its teardown. Clears NOTIFICATION only. */
    fun onListenerDisconnected() = clearNotificationSource()

    private fun clearNotificationSource() {
        if (source != Source.NOTIFICATION) return
        source = Source.NONE
        startedAtMs = 0L
        onChanged?.invoke()
    }

    /** Manual start/stop, for when notification access was never granted. */
    fun startManually(nowMs: Long = System.currentTimeMillis()) {
        val changed = source != Source.MANUAL || startedAtMs != nowMs
        source = Source.MANUAL
        startedAtMs = nowMs
        dismissedNotificationStartMs = null
        if (changed) onChanged?.invoke()
    }

    /**
     * Ends whichever source is live -- this is how "end workout mode" works from the UI and from
     * an emergency skip, even while Strong's own notification is still up and would otherwise keep
     * the mode alive forever.
     */
    fun endManually() {
        if (source == Source.NONE) return
        if (source == Source.NOTIFICATION) dismissedNotificationStartMs = startedAtMs
        source = Source.NONE
        startedAtMs = 0L
        onChanged?.invoke()
    }
}
