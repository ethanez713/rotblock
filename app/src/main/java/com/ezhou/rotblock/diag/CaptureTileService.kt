package com.ezhou.rotblock.diag

import android.service.quicksettings.TileService
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.block.formatDuration
import com.ezhou.rotblock.insights.DailyRollup
import com.ezhou.rotblock.setup.AccessibilityEnrolment
import kotlinx.coroutines.launch

/**
 * One tap from the shade to a diagnostic capture, modelled on
 * [com.ezhou.rotblock.pause.PauseTileService].
 *
 * The whole reason this is a tile and not a button in Setup: opening rotblock changes the
 * foreground app, which clears the exact state a capture exists to catch -- a dropped target, an
 * unread address bar, a countdown mid-flight. A tile tap never calls `startActivityAndCollapse`,
 * so the shade stays open and whatever was on screen underneath it is untouched.
 */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Same reasoning as PauseTileService: this is a moment rotblock is reliably running, and
        // it costs no wakeup of its own.
        runCatching { AccessibilityEnrolment.restoreIfDropped(this) }
        runCatching { Rotblock.scope.launch { DailyRollup.catchUp(this@CaptureTileService) } }
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        Diagnostics.request(this)
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val lastAt = Diagnostics.lastCaptureAtMs
        tile.label = "Capture diagnostics"
        tile.subtitle = if (lastAt == 0L) {
            "Never captured"
        } else {
            "Captured ${formatDuration((System.currentTimeMillis() - lastAt).coerceAtLeast(0))} ago"
        }
        runCatching { tile.updateTile() }
    }
}
