package com.ezhou.rotblock.insights

import android.content.Context
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.pause.dayStartMs
import com.ezhou.rotblock.setup.hasUsageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.TimeZone

object DailyRollup {

    /**
     * How many completed days [catchUp] will still try to fold out of usage events.
     *
     * Android drops those events after about ten days. Nine leaves a day of margin, so the oldest
     * day written was still queryable when the hook ran. A day that aged out first is simply missing.
     */
    const val USAGE_EVENT_WINDOW_DAYS = 9

    /** Rows older than this are deleted inside [catchUp]. About a year of ~40 keys a day. */
    const val DAILY_USAGE_RETENTION_MS = 365L * 24 * 60 * 60 * 1000

    /**
     * Soonest a roll may follow the day it stored.
     *
     * The marker is the start of the last completed day written. The next one is not finished until
     * the day after *that* begins, which is two budget-days later — 48h, or 47h when one of them is
     * the 23h day a DST spring-forward produces. This is only a cheap pre-check, so it takes the
     * shortest gap: [daysToRoll] is the real decision and never includes today, while a longer gap
     * would leave yesterday reading as empty for the first hour after the rollover.
     */
    const val ROLL_EARLIEST_GAP_MS = 47L * 60 * 60 * 1000

    private const val PREFS = "daily_rollup"
    private const val KEY_LAST_ROLLED_DAY = "last_rolled_day_start"

    private val rollGate = Mutex()

    /**
     * Whether [markerMs] — the start of the last day rolled, or 0 when nothing has been — is far
     * enough behind [nowMs] that a completed day might be waiting.
     */
    fun rollDue(nowMs: Long, markerMs: Long): Boolean =
        markerMs == 0L || nowMs >= markerMs + ROLL_EARLIEST_GAP_MS

    /**
     * Completed budget-days strictly after [markerMs], limited to the last [windowDays] before the
     * day containing [nowMs].
     *
     * Today is absent: it is folded live, and writing it would freeze a partial day. Ascending.
     */
    fun daysToRoll(
        nowMs: Long,
        dayStartHour: Int,
        markerMs: Long,
        windowDays: Int = USAGE_EVENT_WINDOW_DAYS,
        tz: TimeZone = TimeZone.getDefault(),
    ): List<Long> {
        if (windowDays <= 0) return emptyList()
        val span = recentDayStarts(nowMs, dayStartHour, windowDays + 1, tz)
        if (span.size < 2) return emptyList()
        val today = span.last()
        return span.filter { it < today && it > markerMs }
    }

    /**
     * Writes completed days into `daily_usage` once a whole day has finished since the last write.
     *
     * The common path is one prefs read, then a return. Otherwise, on [Dispatchers.IO], every
     * completed day newer than the marker and inside [USAGE_EVENT_WINDOW_DAYS] is folded, upserted,
     * and the marker advances. Rows older than [DAILY_USAGE_RETENTION_MS] are dropped in the same
     * pass. Without usage access this returns without moving the marker, so granting it later still
     * backfills the days Android has not pruned. An empty event read does the same: an empty cursor
     * is not evidence the day was idle.
     *
     * Opportunistic on purpose. There is no alarm behind it. Safe to call from the main thread, and
     * it does not throw.
     */
    suspend fun catchUp(context: Context) {
        runCatching { catchUpOrThrow(context.applicationContext) }
    }

    private suspend fun catchUpOrThrow(context: Context) {
        val now = System.currentTimeMillis()
        val marker = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_ROLLED_DAY, 0L)
        if (!rollDue(now, marker)) return
        if (!hasUsageAccess(context)) return
        withContext(Dispatchers.IO) {
            rollGate.withLock { rollLocked(context) }
        }
    }

    private suspend fun rollLocked(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val marker = prefs.getLong(KEY_LAST_ROLLED_DAY, 0L)
        if (!rollDue(now, marker)) return
        if (!hasUsageAccess(context)) return
        val hour = Rotblock.settings(context).current().dayStartHour
        val days = daysToRoll(now, hour, marker)
        if (days.isEmpty()) return
        val todayStart = dayStartMs(now, hour)
        val excluded = excludedPackages(defaultHomePackage(context))
        val device = readForegroundSessions(context, days.first(), todayStart, excluded) ?: return
        val ledger = Rotblock.database(context).usageDao().since(days.first()).map {
            TargetSession(it.targetId, it.startMs, it.endMs)
        }
        val rows = rollCompletedDays(
            device,
            ledger,
            days,
            todayStart,
            BrowserRegistry.packages,
            feedPackagesByTarget(),
        )
        val dao = Rotblock.database(context).dailyUsageDao()
        dao.replaceDays(days, rows)
        dao.prune(now - DAILY_USAGE_RETENTION_MS)
        prefs.edit().putLong(KEY_LAST_ROLLED_DAY, days.last()).apply()
    }
}
