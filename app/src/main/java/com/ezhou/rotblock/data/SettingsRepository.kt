package com.ezhou.rotblock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ezhou.rotblock.policy.PolicySort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Global knobs.
 *
 * Note what is *not* here: any counter of pauses used today. That is derived from the audit log
 * instead, so there is exactly one source of truth and no daily counter that could drift, fail to
 * reset, or disagree with the history the user is looking at.
 */
data class RotblockSettings(
    /** Epoch ms the current pause ends. 0 means not paused. Blocks resume the instant this passes. */
    val pausedUntilMs: Long = 0L,
    /** Seconds of forced waiting before a pause takes effect. 0 disables the cooldown. */
    val cooldownSeconds: Int = 60,
    /** Max pauses that can be started per day. 0 means unlimited. */
    val maxPausesPerDay: Int = 3,
    /** Max total paused minutes per day. 0 means unlimited. */
    val maxPauseMinutesPerDay: Int = 60,
    /** Hour of day the daily budget resets, e.g. 4 for 4am. */
    val dayStartHour: Int = 4,
    /** How long a weakening policy edit is held before it applies. (Phase 5) */
    val weakeningDelayMinutes: Int = 60,
    /**
     * How the Blocks screen orders its cards. The one presentation knob stored here: it belongs
     * with the user's other choices, and nothing in enforcement reads it.
     */
    val blocksSort: PolicySort = PolicySort.DEFAULT,
    /**
     * Show a countdown ring over an app while its allowance is being spent.
     *
     * Off by default, because it is an overlay on top of whatever you are doing. Its second job
     * matters more than its first: every failure this app has had looks like "the app is open and
     * nothing is happening", and a ring that is *absent* while you sit in a quota'd app says the
     * service is not tracking, without opening rotblock to find out.
     */
    val hudEnabled: Boolean = false,
    /** How opaque the ring is, 10..100. Low enough to ignore, high enough to notice missing. */
    val hudOpacityPercent: Int = 55,
    /** The same countdown as a notification instead of, or as well as, the ring. */
    val hudNotification: Boolean = false,
) {
    fun isPaused(nowMs: Long): Boolean = nowMs < pausedUntilMs
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "rotblock")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val pausedUntil = longPreferencesKey("paused_until_ms")
        val cooldownSeconds = intPreferencesKey("cooldown_seconds")
        val maxPausesPerDay = intPreferencesKey("max_pauses_per_day")
        val maxPauseMinutesPerDay = intPreferencesKey("max_pause_minutes_per_day")
        val dayStartHour = intPreferencesKey("day_start_hour")
        val weakeningDelayMinutes = intPreferencesKey("weakening_delay_minutes")
        val blocksSort = stringPreferencesKey("blocks_sort")
        val hudEnabled = booleanPreferencesKey("hud_enabled")
        val hudOpacityPercent = intPreferencesKey("hud_opacity_percent")
        val hudNotification = booleanPreferencesKey("hud_notification")
    }

    val settings: Flow<RotblockSettings> = store.data.map { p ->
        val d = RotblockSettings()
        RotblockSettings(
            pausedUntilMs = p[Keys.pausedUntil] ?: d.pausedUntilMs,
            cooldownSeconds = p[Keys.cooldownSeconds] ?: d.cooldownSeconds,
            maxPausesPerDay = p[Keys.maxPausesPerDay] ?: d.maxPausesPerDay,
            maxPauseMinutesPerDay = p[Keys.maxPauseMinutesPerDay] ?: d.maxPauseMinutesPerDay,
            dayStartHour = p[Keys.dayStartHour] ?: d.dayStartHour,
            weakeningDelayMinutes = p[Keys.weakeningDelayMinutes] ?: d.weakeningDelayMinutes,
            blocksSort = PolicySort.decode(p[Keys.blocksSort]),
            hudEnabled = p[Keys.hudEnabled] ?: d.hudEnabled,
            hudOpacityPercent = p[Keys.hudOpacityPercent] ?: d.hudOpacityPercent,
            hudNotification = p[Keys.hudNotification] ?: d.hudNotification,
        )
    }

    suspend fun current(): RotblockSettings = settings.first()

    suspend fun setPausedUntil(untilMs: Long) {
        store.edit { it[Keys.pausedUntil] = untilMs }
    }

    suspend fun setCooldownSeconds(seconds: Int) {
        store.edit { it[Keys.cooldownSeconds] = seconds.coerceIn(0, 600) }
    }

    suspend fun setMaxPausesPerDay(count: Int) {
        store.edit { it[Keys.maxPausesPerDay] = count.coerceIn(0, 50) }
    }

    suspend fun setMaxPauseMinutesPerDay(minutes: Int) {
        store.edit { it[Keys.maxPauseMinutesPerDay] = minutes.coerceIn(0, 24 * 60) }
    }

    suspend fun setDayStartHour(hour: Int) {
        store.edit { it[Keys.dayStartHour] = hour.coerceIn(0, 23) }
    }

    suspend fun setWeakeningDelayMinutes(minutes: Int) {
        store.edit { it[Keys.weakeningDelayMinutes] = minutes.coerceIn(0, 7 * 24 * 60) }
    }

    suspend fun setBlocksSort(sort: PolicySort) {
        store.edit { it[Keys.blocksSort] = sort.name }
    }

    suspend fun setHudEnabled(enabled: Boolean) {
        store.edit { it[Keys.hudEnabled] = enabled }
    }

    /** Floored at 10: a ring nobody can see cannot report that tracking stopped. */
    suspend fun setHudOpacityPercent(percent: Int) {
        store.edit { it[Keys.hudOpacityPercent] = percent.coerceIn(10, 100) }
    }

    suspend fun setHudNotification(enabled: Boolean) {
        store.edit { it[Keys.hudNotification] = enabled }
    }
}
