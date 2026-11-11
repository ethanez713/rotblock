package com.ezhou.rotblock.service

import com.ezhou.rotblock.data.RotblockSettings

class SettingsMirror {

    /**
     * The countdown-readout settings, copied out of the settings flow.
     *
     * Volatile and read on the main thread; written by the collector. Nothing about enforcement
     * depends on them -- if they were stale for a second, the ring would be a second late, and
     * that is the entire consequence.
     */
    @Volatile var hudEnabled: Boolean = true
        private set

    @Volatile var hudOpacityPercent: Int = 55
        private set

    @Volatile var dayStartHour: Int = RotblockSettings().dayStartHour
        private set

    @Volatile var hudNotification: Boolean = false
        private set

    /** Copied out of the settings flow the same way [hudEnabled] is. See [shouldAttemptReveal]. */
    @Volatile var revealAddressBarEnabled: Boolean = false
        private set

    /** Where the ring was last dropped; -1 means never, and [TimerHud] falls back to the corner. */
    @Volatile var hudPositionX: Int = -1
        private set

    @Volatile var hudPositionY: Int = -1
        private set

    fun apply(settings: RotblockSettings) {
        hudEnabled = settings.hudEnabled
        hudOpacityPercent = settings.hudOpacityPercent
        dayStartHour = settings.dayStartHour
        hudNotification = settings.hudNotification
        hudPositionX = settings.hudPositionX
        hudPositionY = settings.hudPositionY
        revealAddressBarEnabled = settings.revealAddressBar
    }
}
