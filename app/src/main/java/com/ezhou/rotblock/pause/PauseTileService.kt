package com.ezhou.rotblock.pause

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.block.formatDuration
import com.ezhou.rotblock.setup.AccessibilityEnrolment
import com.ezhou.rotblock.ui.PauseRequestActivity
import kotlinx.coroutines.launch

/**
 * One tap from the notification shade to the pause flow.
 *
 * This is the ergonomic fix for the thing that actually broke the old setup: needing a legitimate
 * five minutes on a blocked app meant opening a settings screen and switching a policy off, which
 * is both slow and permanent. From here it is a swipe and a tap, and it always expires.
 */
class PauseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Pulling the shade down is a system event this tile is already bound for, so checking here
        // costs no wakeup of rotblock's own -- which matters, because there is no other moment the
        // app is reliably running to notice it has been dropped from Android's accessibility list.
        // Opening rotblock does it too, but a blocker nobody opens is exactly the one that needs it.
        runCatching { AccessibilityEnrolment.restoreIfDropped(this) }
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, PauseRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The Intent overload throws on Android 14+; a PendingIntent is the only route.
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        Rotblock.scope.launch {
            val settings = Rotblock.settings(this@PauseTileService).current()
            val now = System.currentTimeMillis()
            val paused = settings.isPaused(now)
            tile.state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.label = "rotblock"
            tile.subtitle = if (paused) {
                formatDuration(settings.pausedUntilMs - now) + " left"
            } else {
                "Blocking on"
            }
            runCatching { tile.updateTile() }
        }
    }
}
