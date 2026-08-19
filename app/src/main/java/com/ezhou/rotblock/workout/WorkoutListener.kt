package com.ezhou.rotblock.workout

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Answers one question -- is there a live Strong workout, and when did it start -- and pushes the
 * answer into [WorkoutMode]. Nothing else. See the manifest entry for why this grant is scoped in
 * code rather than in the framework, and what it deliberately never reads.
 */
class WorkoutListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // Re-derive from scratch rather than trusting whatever WorkoutMode already believes -- this
        // is what makes the state survive this listener's own process dying and reconnecting, the
        // same way PolicyEngine re-derives a schedule from the clock instead of a stored flag.
        runCatching {
            val start = liveWorkoutStart(activeNotifications, WorkoutMode.appPackage)
            if (start != null) WorkoutMode.onWorkoutSeen(start) else WorkoutMode.onWorkoutGone()
        }
    }

    override fun onListenerDisconnected() {
        runCatching { WorkoutMode.onListenerDisconnected() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != WorkoutMode.appPackage) return
        runCatching {
            if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
                WorkoutMode.onWorkoutSeen(sbn.notification.`when`)
            } else {
                // Strong's own notification exists but is no longer ongoing -- re-scan rather than
                // assume, since a non-ongoing post does not by itself say the workout ended.
                val start = liveWorkoutStart(activeNotifications, WorkoutMode.appPackage)
                if (start != null) WorkoutMode.onWorkoutSeen(start) else WorkoutMode.onWorkoutGone()
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != WorkoutMode.appPackage) return
        // Re-scan rather than assume the removed notification was the only ongoing one Strong had
        // posted -- a stale reference to what was removed cannot tell.
        runCatching {
            val start = liveWorkoutStart(activeNotifications, WorkoutMode.appPackage)
            if (start != null) WorkoutMode.onWorkoutSeen(start) else WorkoutMode.onWorkoutGone()
        }
    }

    companion object {
        /**
         * The workout's start time if [pkg] currently has a live (FLAG_ONGOING_EVENT) notification
         * up, or null. The ongoing flag is structural -- part of the notification's own contract --
         * unlike anything in its title, text or channel, which are Strong's localized strings a
         * redesign can change overnight and which this deliberately never reads.
         */
        internal fun liveWorkoutStart(notifications: Array<StatusBarNotification>?, pkg: String): Long? =
            notifications
                ?.firstOrNull { it.packageName == pkg && it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 }
                ?.notification
                ?.`when`
    }
}
