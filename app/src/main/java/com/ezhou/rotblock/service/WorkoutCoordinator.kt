package com.ezhou.rotblock.service

import android.content.Context
import android.content.Intent
import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.block.Interstitial
import com.ezhou.rotblock.block.WorkoutReminder
import com.ezhou.rotblock.workout.WorkoutMode

/**
 * How much extra time a snoozed workout reminder buys, on top of the ordinary interval.
 *
 * Longer than [WorkoutMode.reminderMs] on purpose: the ordinary interval is measuring "you
 * drifted without noticing", and a snooze is you saying you did not -- you are doing something
 * else deliberately, so re-nagging thirty seconds later would just be ignored and trains people
 * to dismiss the reminder on reflex.
 */
private const val REMINDER_SNOOZE_MS = 5 * 60 * 1000L

class WorkoutCoordinator(
    context: Context,
    private val audit: () -> AuditLog,
    private val serviceGuard: ServiceGuard,
    private val belief: ForegroundBelief,
    private val actions: AccessibilityActions,
    private val hooks: WorkoutHooks,
) {
    private fun guard(where: String, block: () -> Unit) = serviceGuard.guard(where, block)
    private fun currentPackage() = belief.currentPackage
    private fun blockOverlayShowing() = hooks.blockOverlayShowing()
    private fun reEvaluate() = hooks.reEvaluate()
    private fun screenOn() = hooks.screenOn()
    private fun launchIntent(packageName: String) = hooks.launchIntent(packageName)
    private fun startActivity(intent: Intent) = actions.startActivity(intent)
    private fun soonestMs(a: Long?, b: Long?) = hooks.soonest(a, b)

    private val reminder = WorkoutReminder(context)

    /**
     * Whether the last look found workout mode live. Diagnostic only -- [PolicyEngine] re-derives
     * liveness from the clock every time and never reads this. It exists so the audit log gets
     * one entry per transition rather than one per evaluation.
     */
    private var workoutWasLive = false

    /**
     * When the phone last left the workout app with a workout live, or 0 when it has not. Not a
     * deadline that outlives the process on purpose -- an away-timer that survived a restart
     * would fire a reminder for time spent before rotblock was even running. Written only by
     * [refreshWorkoutReminder], plus the screen-off reset below, never anywhere else.
     */
    private var awaySinceMs = 0L

    /** When the next reminder is due, or 0 when none is pending. See [awaySinceMs]. */
    private var nextReminderAtMs = 0L

    fun hideReminder() {
        reminder.hide()
    }

    fun onScreenOff() {
        // A locked phone is not you being distracted, so the away-timer starts
        // again from the unlock rather than counting the whole night. USER_PRESENT
        // already calls reEvaluateCurrent, which restarts the clock through the
        // normal path -- no reseeding needed here.
        reminder.hide()
        awaySinceMs = 0L
        nextReminderAtMs = 0L
    }

    /**
     * The away-timer for the "get back to your workout" reminder.
     *
     * A deadline like every other relaxation in this file, not a countdown that ticks on its own
     * -- [nextReminderAtMs] is compared to the clock, and the single ticker gets armed for it
     * through [workoutDeadlineMs]. Called from every site that already calls [refreshGrayscale],
     * because the two ask the same "is this the workout app, is a workout live" question, even
     * though they answer differently once they have it -- greyscale degrades to a colour flip,
     * a reminder can outright fail to show, so they stay separate functions.
     */
    fun refreshWorkoutReminder(nowMs: Long) {
        // Being in an allowed app is not being away: choosing the next podcast is not drifting, and
        // nagging you out of the app you deliberately allowed is the fastest way to lose the whole
        // feature.
        val away = WorkoutMode.overrideUntilMs(currentPackage(), nowMs) != null
        if (!away) {
            awaySinceMs = 0L
            nextReminderAtMs = 0L
            reminder.hide()
            return
        }
        if (awaySinceMs == 0L) {
            awaySinceMs = nowMs
            nextReminderAtMs = nowMs + WorkoutMode.reminderMs
        }
        // Checked before the deadline, not after, because this has to take a reminder *down* as
        // well as decline to put one up: walking from an unblocked app into a blocked one leaves
        // an already-showing nudge stranded on top of the block otherwise, and only the not-away
        // branch above would ever have cleared it.
        //
        // Suppressed rather than rescheduled: a block screen already says a workout is in
        // progress, and one of our own screens means you are looking at rotblock, not being
        // distracted by something else. Leaving it due means it appears the moment the cover
        // goes away, rather than being silently skipped for this interval.
        if (blockOverlayShowing() || Interstitial.isShowing(nowMs)) {
            reminder.hide()
            return
        }
        if (nowMs < nextReminderAtMs) return
        if (showWorkoutReminder()) nextReminderAtMs = nowMs + WorkoutMode.reminderMs
    }

    /**
     * Puts the reminder up, wired to its two ways out -- both dismiss it, so it is never a dead
     * end. The primary action gets you back to what you chose to do; the quiet one is the way
     * out, same ranking as the block screen's and for the same reason.
     */
    private fun showWorkoutReminder(): Boolean = reminder.show(
        onResume = { guard("workoutReminderResume") { reminder.hide(); launchWorkoutApp() } },
        onSnooze = {
            guard("workoutReminderSnooze") {
                reminder.hide()
                nextReminderAtMs = System.currentTimeMillis() + REMINDER_SNOOZE_MS
                EnforcementStatus.note("workout reminder: snoozed")
            }
        },
    )

    /**
     * A null launch intent means the configured package is not installed, or is not visible
     * under scoped package visibility -- the manifest's `<queries>` LAUNCHER entry already makes
     * every launchable app visible, so this is a bad setting rather than a permissions problem.
     */
    private fun launchWorkoutApp() {
        val intent = launchIntent(WorkoutMode.appPackage)
        if (intent == null) {
            EnforcementStatus.note("workout reminder: cannot launch ${WorkoutMode.appPackage}")
            return
        }
        runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { EnforcementStatus.note("workout reminder: launch failed") }
    }

    fun onWorkoutModeChanged() {
        val nowMs = System.currentTimeMillis()
        noteWorkoutTransition(nowMs)
        // Screen-off is the one state this must not re-evaluate from. A workout ends when you rack
        // the last set, which is routinely with the phone locked in a pocket -- and screen-off does
        // not clear [currentTargetId], so re-evaluating would find the app you locked the phone
        // inside, decide it is allowed again now the workout is over, and open a ledger session for
        // it. That charges quota for a phone nobody is looking at, and over-charging is the one
        // direction the ledger may not err in. It would arm the ticker too, which is exactly the
        // pending-tick-with-the-screen-off that [MAX_IDLE_TICK_MS] argues cannot happen.
        //
        // Nothing is lost by waiting: ACTION_USER_PRESENT re-evaluates on the way back in, which is
        // where the greyscale skipped here gets put right. The transition above is still recorded,
        // because when the workout ended is a fact about the past and does not depend on the screen.
        if (screenOn()) reEvaluate()
    }

    fun noteWorkoutTransition(nowMs: Long) {
        val live = WorkoutMode.activeUntilMs(nowMs) != null
        if (live == workoutWasLive) return
        workoutWasLive = live
        audit().record(
            if (live) AuditType.WORKOUT_MODE_STARTED else AuditType.WORKOUT_MODE_ENDED,
            detail = if (live) "Workout in progress; every enabled rule is blocking"
                     else "Workout mode ended; normal rules resume",
        )
        EnforcementStatus.note(if (live) "workout mode: started" else "workout mode: ended")
    }

    /**
     * The next moment workout mode changes what is on screen: the cap lapsing, or a reminder
     * coming due. Callers must run this after [refreshWorkoutReminder] has already run for the
     * same pass, so [nextReminderAtMs] reflects the current away-time rather than the last one.
     */
    fun workoutDeadlineMs(nowMs: Long): Long? =
        soonestMs(WorkoutMode.activeUntilMs(nowMs), nextReminderAtMs.takeIf { it > 0L })
}
