package com.ezhou.rotblock.block

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ezhou.rotblock.R

private const val CHANNEL_ID = "tracking"
private const val NOTIFICATION_ID = 1001

/** Re-post only when the deadline has genuinely moved, not on every evaluation. */
private const val DEADLINE_SLACK_MS = 2_000L

/**
 * The countdown as a notification, for people who would rather not have a ring on the screen.
 *
 * Same two jobs as [TimerHud] -- what is left, and proof that something is counting -- and the same
 * lifetime: posted while a target is accruing, cancelled the moment it stops. It is a readout of an
 * open session, not a reminder, so it must never outlive one.
 *
 * The countdown itself is the notification's own chronometer, counting down to [show]'s deadline.
 * That matters more than it looks: the alternative is re-posting once a second to update a string,
 * which is a wakeup and a system call every second for a number the system will happily count on
 * its own. Nothing here needs to run for the number to keep moving.
 *
 * Not a foreground-service notification, and it must not become one. Nothing in this app may keep
 * the process alive or wake a sleeping phone; if it is killed, the deadlines on disk still decide
 * everything, which is the whole design.
 */
class TrackingNotification(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    private var channelReady = false
    private var shownLabel: String? = null
    private var shownDeadlineMs = 0L

    /**
     * Posts or updates the countdown. [deadlineMs] is when the allowance runs out.
     *
     * Silently does nothing without the notification permission -- the setting is opt-in and Setup
     * asks for the grant there, and a service whose only job is to keep running does not throw over
     * a notification.
     */
    fun show(label: String, deadlineMs: Long) {
        if (!allowed()) return
        if (label == shownLabel && kotlin.math.abs(deadlineMs - shownDeadlineMs) < DEADLINE_SLACK_MS) {
            return
        }
        ensureChannel()

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.ezhou.rotblock.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_rotblock)
            .setContentTitle(label)
            .setContentText("Time left in your allowance")
            .setContentIntent(open)
            // The system counts this down for us. See the class doc.
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(deadlineMs)
            .setShowWhen(true)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onSuccess {
                shownLabel = label
                shownDeadlineMs = deadlineMs
            }
    }

    fun hide() {
        if (shownLabel == null) return
        shownLabel = null
        shownDeadlineMs = 0L
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Time remaining",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows what is left of an allowance while you are using the app it covers."
                setShowBadge(false)
            }
            runCatching { manager.createNotificationChannel(channel) }
        }
        channelReady = true
    }
}
