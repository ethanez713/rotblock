package com.ezhou.rotblock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    /**
     * Epoch ms the *global* pause ends -- the widest scope, unblocking every target at once. 0
     * means no global pause. Blocks resume the instant this passes. See [pausedTargets] for the
     * narrower scopes that unblock one app or one group without touching the rest.
     */
    val pausedUntilMs: Long = 0L,
    /**
     * Per-target pause deadlines: `targetId -> epoch ms it ends`. The narrow scopes live here, so
     * pausing a game never unblocks Instagram. "This app" writes one entry; "this group" writes one
     * per member target; "everything" is [pausedUntilMs] instead. A target is relaxed when the
     * later of its entry and the global deadline is still in the future -- see [pausedUntilFor].
     *
     * Same fail-closed invariant as every relaxation here: each value is a deadline compared to the
     * clock, so a crash, force-stop or reboot resumes blocking on its own. An entry in the past is
     * simply ignored, which is why nothing prunes it for correctness.
     */
    val pausedTargets: Map<String, Long> = emptyMap(),
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
     * On by default. It used to default off because it was an overlay on top of whatever you were
     * doing -- but it is draggable now, and a flick onto the dismiss target puts it out of the way
     * for the rest of the visit at no cost worse than a swipe. That undercuts the old reason to
     * hide it, and its second job outweighs the first regardless: every failure this app has had
     * looks like "the app is open and nothing is happening", and a ring that is *absent* while you
     * sit in a quota'd app says the service is not tracking, without opening rotblock to find out.
     * That signal is only honest from the first run if the ring starts on.
     */
    val hudEnabled: Boolean = true,
    /** How opaque the ring is, 10..100. Low enough to ignore, high enough to notice missing. */
    val hudOpacityPercent: Int = 55,
    /** The same countdown as a notification instead of, or as well as, the ring. */
    val hudNotification: Boolean = false,
    /**
     * Where the ring was last dropped, in absolute screen px. -1 for either means "never placed",
     * which falls back to the default corner. Not a relaxation and not a deadline: losing this is
     * merely annoying, not a fail-open, so it is persisted for the same reason a window remembers
     * where you left it -- nothing here changes what gets enforced.
     *
     * A drag-to-*dismiss* is deliberately not stored here or anywhere else on disk -- see
     * [com.ezhou.rotblock.block.TimerHud]. Only the position survives a restart; whether the ring
     * was dismissed does not, on purpose.
     */
    val hudPositionX: Int = -1,
    val hudPositionY: Int = -1,
    /** When the first-run walkthrough was finished or dismissed. 0 means it has never been shown. */
    val onboardedAtMs: Long = 0L,
    /**
     * When the user accepted what the accessibility service reads and does. 0 means they have not.
     *
     * Recorded rather than assumed, and checked before anything sends them to Android's
     * accessibility settings. Partly because Play requires an in-app disclosure and affirmative
     * consent for any non-accessibility use of that API, and partly because it is true: this is the
     * one permission in the app that can see every screen you open, and turning it on should be a
     * decision rather than a tap on a button that said "Open settings".
     */
    val accessibilityConsentAtMs: Long = 0L,
    /**
     * How often each feed's host app was inspected and how often its view ids were found, encoded
     * by [com.ezhou.rotblock.setup.DetectionCounters].
     *
     * Purely diagnostic: nothing in enforcement reads it, and losing it costs a later warning and
     * nothing else.
     */
    val detectionCounters: String = "",
    /**
     * Let rotblock tap a browser's toolbar-reveal control to bring the address bar back when a
     * scrolled page has hidden it and every passive read has failed. Off by default: unlike every
     * other signal in [com.ezhou.rotblock.browser.LinkConsensus] and
     * [com.ezhou.rotblock.browser.PageSignature], this one acts on the screen -- a synthesised tap
     * can focus the bar and raise the keyboard on a page that may carry no rule at all -- so it is
     * the one mechanism in the app opted into rather than always on.
     */
    val revealAddressBar: Boolean = false,
    /**
     * When Setup last ran the block-screen self test, and whether the user reported seeing it.
     * 0 / false means the test has never been run. See
     * [com.ezhou.rotblock.block.BlockSelfTest] and [com.ezhou.rotblock.setup.Readiness].
     */
    val blockTestAtMs: Long = 0L,
    val blockTestSeen: Boolean = false,
    /** Whether a live Strong workout should block every enabled rule. See [com.ezhou.rotblock.workout.WorkoutMode]. */
    val workoutModeEnabled: Boolean = false,
    /** The package [com.ezhou.rotblock.workout.WorkoutListener] watches for an ongoing notification. */
    val workoutAppPackage: String = "io.strongapp.strong",
    /** How long a workout may override enforcement before it lapses on its own, in minutes. */
    val workoutCapMinutes: Int = 180,
    /** How long you may be away from the workout app before it reminds you to go back. */
    val workoutReminderSeconds: Int = 60,
    /**
     * Packages a workout does not override -- music, podcasts, anything that helps rather than
     * distracts. See [com.ezhou.rotblock.workout.WorkoutMode.overrideUntilMs] for what being on
     * this list does and does not buy an app.
     */
    val workoutAllowedPackages: Set<String> = emptySet(),
) {
    /** True when the *whole phone* is paused. Narrow scopes do not make this true -- see [isTargetPaused]. */
    fun isPaused(nowMs: Long): Boolean = nowMs < pausedUntilMs

    /**
     * When blocking is relaxed for [targetId]: the later of the global pause and this target's own
     * deadline, or 0 if neither applies. This is the one number enforcement folds -- a global pause
     * covers every target, a scoped one covers just its own.
     */
    fun pausedUntilFor(targetId: String): Long = maxOf(pausedUntilMs, pausedTargets[targetId] ?: 0L)

    fun isTargetPaused(targetId: String, nowMs: Long): Boolean = nowMs < pausedUntilFor(targetId)

    /** The scoped pauses still in the future, global excluded. What the tile counts. */
    fun activeScopedPauses(nowMs: Long): Map<String, Long> = pausedTargets.filterValues { it > nowMs }
}

private const val PAUSED_TARGET_ENTRY = "\n"

/**
 * Encodes [pausedTargets] as `targetId=untilMs` lines. Split on the *last* `=` on decode, so a
 * target id that itself contains `=` (a website matcher with a query string) still round-trips --
 * the value is always trailing digits.
 */
fun encodePausedTargets(map: Map<String, Long>): String =
    map.entries
        .filter { it.key.isNotBlank() && it.value > 0L }
        .joinToString(PAUSED_TARGET_ENTRY) { "${it.key}=${it.value}" }

fun decodePausedTargets(text: String?): Map<String, Long> {
    if (text.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, Long>()
    for (line in text.split(PAUSED_TARGET_ENTRY)) {
        if (line.isBlank()) continue
        val sep = line.lastIndexOf('=')
        if (sep <= 0) continue
        val until = line.substring(sep + 1).toLongOrNull() ?: continue
        out[line.substring(0, sep)] = until
    }
    return out
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "rotblock")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val pausedUntil = longPreferencesKey("paused_until_ms")
        val pausedTargets = stringPreferencesKey("paused_targets")
        val cooldownSeconds = intPreferencesKey("cooldown_seconds")
        val maxPausesPerDay = intPreferencesKey("max_pauses_per_day")
        val maxPauseMinutesPerDay = intPreferencesKey("max_pause_minutes_per_day")
        val dayStartHour = intPreferencesKey("day_start_hour")
        val weakeningDelayMinutes = intPreferencesKey("weakening_delay_minutes")
        val blocksSort = stringPreferencesKey("blocks_sort")
        val hudEnabled = booleanPreferencesKey("hud_enabled")
        val hudOpacityPercent = intPreferencesKey("hud_opacity_percent")
        val hudNotification = booleanPreferencesKey("hud_notification")
        val hudPositionX = intPreferencesKey("hud_position_x")
        val hudPositionY = intPreferencesKey("hud_position_y")
        val onboardedAt = longPreferencesKey("onboarded_at_ms")
        val accessibilityConsentAt = longPreferencesKey("accessibility_consent_at_ms")
        val detectionCounters = stringPreferencesKey("detection_counters")
        val revealAddressBar = booleanPreferencesKey("reveal_address_bar")
        val blockTestAt = longPreferencesKey("block_test_at_ms")
        val blockTestSeen = booleanPreferencesKey("block_test_seen")
        val workoutModeEnabled = booleanPreferencesKey("workout_mode_enabled")
        val workoutAppPackage = stringPreferencesKey("workout_app_package")
        val workoutCapMinutes = intPreferencesKey("workout_cap_minutes")
        val workoutReminderSeconds = intPreferencesKey("workout_reminder_seconds")
        val workoutAllowedPackages = stringSetPreferencesKey("workout_allowed_packages")
    }

    val settings: Flow<RotblockSettings> = store.data.map { p ->
        val d = RotblockSettings()
        RotblockSettings(
            pausedUntilMs = p[Keys.pausedUntil] ?: d.pausedUntilMs,
            pausedTargets = decodePausedTargets(p[Keys.pausedTargets]),
            cooldownSeconds = p[Keys.cooldownSeconds] ?: d.cooldownSeconds,
            maxPausesPerDay = p[Keys.maxPausesPerDay] ?: d.maxPausesPerDay,
            maxPauseMinutesPerDay = p[Keys.maxPauseMinutesPerDay] ?: d.maxPauseMinutesPerDay,
            dayStartHour = p[Keys.dayStartHour] ?: d.dayStartHour,
            weakeningDelayMinutes = p[Keys.weakeningDelayMinutes] ?: d.weakeningDelayMinutes,
            blocksSort = PolicySort.decode(p[Keys.blocksSort]),
            hudEnabled = p[Keys.hudEnabled] ?: d.hudEnabled,
            hudOpacityPercent = p[Keys.hudOpacityPercent] ?: d.hudOpacityPercent,
            hudNotification = p[Keys.hudNotification] ?: d.hudNotification,
            hudPositionX = p[Keys.hudPositionX] ?: d.hudPositionX,
            hudPositionY = p[Keys.hudPositionY] ?: d.hudPositionY,
            onboardedAtMs = p[Keys.onboardedAt] ?: d.onboardedAtMs,
            accessibilityConsentAtMs = p[Keys.accessibilityConsentAt] ?: d.accessibilityConsentAtMs,
            detectionCounters = p[Keys.detectionCounters] ?: d.detectionCounters,
            revealAddressBar = p[Keys.revealAddressBar] ?: d.revealAddressBar,
            blockTestAtMs = p[Keys.blockTestAt] ?: d.blockTestAtMs,
            blockTestSeen = p[Keys.blockTestSeen] ?: d.blockTestSeen,
            workoutModeEnabled = p[Keys.workoutModeEnabled] ?: d.workoutModeEnabled,
            workoutAppPackage = p[Keys.workoutAppPackage] ?: d.workoutAppPackage,
            workoutCapMinutes = p[Keys.workoutCapMinutes] ?: d.workoutCapMinutes,
            workoutReminderSeconds = p[Keys.workoutReminderSeconds] ?: d.workoutReminderSeconds,
            workoutAllowedPackages = p[Keys.workoutAllowedPackages] ?: d.workoutAllowedPackages,
        )
    }

    suspend fun current(): RotblockSettings = settings.first()

    suspend fun setPausedUntil(untilMs: Long) {
        store.edit { it[Keys.pausedUntil] = untilMs }
    }

    /** Replaces the whole scoped-pause map. Callers prune expired entries before writing. */
    suspend fun setPausedTargets(map: Map<String, Long>) {
        store.edit { it[Keys.pausedTargets] = encodePausedTargets(map) }
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

    /** One setter for both coordinates, so a caller can never persist an x from one drop and a y from another. */
    suspend fun setHudPosition(x: Int, y: Int) {
        store.edit {
            it[Keys.hudPositionX] = x
            it[Keys.hudPositionY] = y
        }
    }

    suspend fun setOnboardedAt(atMs: Long) {
        store.edit { it[Keys.onboardedAt] = atMs }
    }

    /** Only ever set forward, never cleared: consent given is a fact about what happened. */
    suspend fun setAccessibilityConsentAt(atMs: Long) {
        store.edit { it[Keys.accessibilityConsentAt] = atMs }
    }

    suspend fun setDetectionCounters(encoded: String) {
        store.edit { it[Keys.detectionCounters] = encoded }
    }

    suspend fun setRevealAddressBar(enabled: Boolean) {
        store.edit { it[Keys.revealAddressBar] = enabled }
    }

    /** One setter for both, so a caller can never persist a timestamp without its outcome. */
    suspend fun setBlockTestResult(atMs: Long, seen: Boolean) {
        store.edit {
            it[Keys.blockTestAt] = atMs
            it[Keys.blockTestSeen] = seen
        }
    }

    suspend fun setWorkoutModeEnabled(enabled: Boolean) {
        store.edit { it[Keys.workoutModeEnabled] = enabled }
    }

    suspend fun setWorkoutAppPackage(pkg: String) {
        store.edit { it[Keys.workoutAppPackage] = pkg }
    }

    suspend fun setWorkoutCapMinutes(minutes: Int) {
        store.edit { it[Keys.workoutCapMinutes] = minutes.coerceIn(30, 24 * 60) }
    }

    suspend fun setWorkoutReminderSeconds(seconds: Int) {
        store.edit { it[Keys.workoutReminderSeconds] = seconds.coerceIn(30, 3600) }
    }

    suspend fun setWorkoutAllowedPackages(packages: Set<String>) {
        store.edit { it[Keys.workoutAllowedPackages] = packages }
    }
}
