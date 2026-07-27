package com.ezhou.rotblock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

        @Suppress("unused") // reserved so a stale key name is never silently reused
        val unusedMarker = booleanPreferencesKey("_reserved")
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
}
