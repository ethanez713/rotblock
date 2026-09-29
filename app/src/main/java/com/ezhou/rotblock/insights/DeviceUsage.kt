package com.ezhou.rotblock.insights

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.browser.BrowserRegistry
import com.ezhou.rotblock.data.DailyUsage
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.setup.hasUsageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What [android.app.usage.UsageStatsManager] reports, stripped down to the three fields the fold
 * needs so the fold can be tested without a device.
 *
 * Events are chronological, which is the order [UsageEvents] yields them. The fold does not sort:
 * a pause that arrives before its resume is dropped, and that is the direction that under-counts.
 */
data class ForegroundEvent(
    val pkg: String,
    val type: ForegroundEventType,
    val ts: Long,
)

enum class ForegroundEventType {
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
    SCREEN_NON_INTERACTIVE,
    KEYGUARD_SHOWN,
    DEVICE_SHUTDOWN,
}

private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

/**
 * The packages whose foreground time is not app use.
 *
 * [homePackage] is the default launcher (`ACTION_MAIN` / `CATEGORY_HOME`). Null when the phone
 * has not resolved one — system UI is still excluded, and a missing launcher degrades to counting
 * it, which is more time rather than a crash.
 */
fun excludedPackages(homePackage: String?): Set<String> = buildSet {
    add(SYSTEM_UI_PACKAGE)
    if (!homePackage.isNullOrBlank()) add(homePackage)
}

/**
 * Folds usage events into per-day totals keyed `app:<package>`.
 *
 * Foreground is exclusive: a resume of X closes whatever else was open at that timestamp, a pause
 * closes X, and screen-off, the keyguard and shutdown close everything. A resume with no matching
 * pause is closed at [endMs]. A pause with nothing open is dropped — under-count, never over-count.
 * A session that crosses a budget-day boundary is split, the same way [bucketByDay] splits one.
 *
 * [endMs] is the end of the range, exclusive. Events at or after it are ignored.
 */
fun foldUsageEvents(
    events: Iterable<ForegroundEvent>,
    endMs: Long,
    dayStarts: List<Long>,
    excludedPackages: Set<String>,
): List<DayBucket> = bucketByDay(foregroundSessions(events, endMs, excludedPackages), dayStarts, endMs)

internal fun foregroundSessions(
    events: Iterable<ForegroundEvent>,
    endMs: Long,
    excludedPackages: Set<String>,
): List<TargetSession> {
    val fold = ForegroundFold(endMs, excludedPackages)
    for (event in events) fold.add(event.pkg, event.type, event.ts)
    return fold.finish()
}

/**
 * One budget-day of screen time, with site and feed time taken out of the app that contained it.
 *
 * [deviceMsByPackage] is UsageStats, keyed by package. [ledgerMs] is the ledger, keyed by target
 * id. Ledger `app:` rows are ignored: with usage access the OS is the source for apps, and adding
 * them would count a ruled app twice. `web:` time is taken off that day's [browserPackages],
 * largest first, and each `feed:` target off the package [feedPackageByTarget] names. Every
 * subtraction clamps at zero. A group id is not a thing on screen and is dropped.
 *
 * The map this returns is what gets stored. Keys are target ids.
 */
fun mergeUsageDay(
    deviceMsByPackage: Map<String, Long>,
    ledgerMs: Map<String, Long>,
    browserPackages: Set<String>,
    feedPackageByTarget: Map<String, String>,
): Map<String, Long> {
    val merged = HashMap<String, Long>()
    for ((pkg, ms) in deviceMsByPackage) {
        if (pkg.isNotEmpty() && ms > 0L) merged[Policy.targetId(TargetKind.APP, pkg)] = ms
    }
    var webMs = 0L
    val kept = HashMap<String, Long>()
    for ((id, ms) in ledgerMs) {
        if (ms <= 0L) continue
        when {
            id.startsWith("web:") -> {
                webMs += ms
                kept[id] = ms
            }
            id.startsWith("feed:") -> kept[id] = ms
        }
    }
    subtractLargestFirst(merged, browserPackages, webMs)
    for (id in kept.keys.filter { it.startsWith("feed:") }.sorted()) {
        val pkg = feedPackageByTarget[id] ?: continue
        val key = Policy.targetId(TargetKind.APP, pkg)
        val left = (merged[key] ?: 0L) - (kept[id] ?: 0L)
        if (left > 0L) merged[key] = left else merged.remove(key)
    }
    for ((id, ms) in kept) {
        if (ms > 0L) merged[id] = ms
    }
    return merged
}

/**
 * The rows [com.ezhou.rotblock.insights.DailyRollup] writes for [dayStarts].
 *
 * [deviceSessions] carry `app:` keys, the shape [foregroundSessions] produces. [rangeEndMs] is the
 * start of the still-open day, so a session that runs past the last completed day is clipped
 * there rather than stored as part of today.
 */
fun rollCompletedDays(
    deviceSessions: List<TargetSession>,
    ledgerSessions: List<TargetSession>,
    dayStarts: List<Long>,
    rangeEndMs: Long,
    browserPackages: Set<String>,
    feedPackageByTarget: Map<String, String>,
): List<DailyUsage> {
    if (dayStarts.isEmpty()) return emptyList()
    val deviceDays = bucketByDay(deviceSessions, dayStarts, rangeEndMs)
    val ledgerDays = bucketByDay(ledgerSessions, dayStarts, rangeEndMs)
    val rows = ArrayList<DailyUsage>()
    for (i in dayStarts.indices) {
        val packages = HashMap<String, Long>()
        for ((key, ms) in deviceDays[i].perTarget) {
            if (!key.startsWith("app:") || ms <= 0L) continue
            val pkg = key.removePrefix("app:")
            if (pkg.isNotEmpty()) packages[pkg] = ms
        }
        val merged = mergeUsageDay(
            packages,
            ledgerDays[i].perTarget,
            browserPackages,
            feedPackageByTarget,
        )
        val day = dayStarts[i]
        for ((key, ms) in merged) {
            if (ms > 0L) rows.add(DailyUsage(day, key, ms))
        }
    }
    return rows
}

/**
 * Per-day screen time for [dayStarts], in that order, plus whether usage access is granted.
 *
 * With the grant, completed days are the rolled-up rows and the day containing [nowMs] is folded
 * from usage events and the ledger since that day's start. A package's key is `app:<package>`; a
 * rotblocked site or feed keeps its ledger id. Ledger `app:` rows are not included. Without the
 * grant, every day is the ledger alone — app, site and feed rows, with nothing subtracted.
 *
 * An empty or refused event read does not zero the phone. Today falls back to the ledger for that
 * one day; completed days still come from whatever was already rolled. The flag is the grant, not
 * whether this particular read returned events.
 *
 * Calls [DailyRollup.catchUp] first, so a day that ended overnight is on disk before it is read.
 */
suspend fun loadScreenTime(
    context: Context,
    dayStarts: List<Long>,
    nowMs: Long = System.currentTimeMillis(),
): ScreenTime = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    runCatching { DailyRollup.catchUp(app) }
    runCatching { assemble(app, dayStarts, nowMs) }.getOrElse {
        ScreenTime(
            runCatching { ledgerBuckets(app, dayStarts, nowMs) }
                .getOrDefault(dayStarts.map { DayBucket(it, emptyMap()) }),
            usageAccessGranted = false,
        )
    }
}

data class ScreenTime(
    val buckets: List<DayBucket>,
    val usageAccessGranted: Boolean,
)

internal fun feedPackagesByTarget(): Map<String, String> =
    FeedRegistry.specs.associate { spec ->
        Policy.targetId(TargetKind.FEED, spec.key) to spec.packageName
    }

internal fun defaultHomePackage(context: Context): String? = runCatching {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    @Suppress("DEPRECATION")
    context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
}.getOrNull()

/**
 * Blocking. One reused [UsageEvents.Event] — the class hands the same instance back on every
 * call, and copying its fields out before the next call is what keeps the event list from existing.
 *
 * Null is not zero usage. An empty cursor is what a missing grant returns on some phones instead
 * of [SecurityException], and storing that as a blank day would advance the rollup past a day
 * that actually had events. A cursor that contained only events this fold ignores is a real zero
 * and comes back as an empty list.
 */
internal fun readForegroundSessions(
    context: Context,
    startMs: Long,
    endMs: Long,
    excludedPackages: Set<String>,
): List<TargetSession>? {
    if (startMs >= endMs) return null
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return null
    val events = try {
        manager.queryEvents(startMs, endMs)
    } catch (_: Exception) {
        return null
    } ?: return null
    if (!events.hasNextEvent()) return null
    val fold = ForegroundFold(endMs, excludedPackages)
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val type = foregroundType(event.eventType) ?: continue
        val pkg = event.packageName.orEmpty()
        val ts = event.timeStamp
        fold.add(pkg, type, ts)
    }
    return fold.finish()
}

private fun foregroundType(eventType: Int): ForegroundEventType? = when (eventType) {
    UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundEventType.ACTIVITY_RESUMED
    UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundEventType.ACTIVITY_PAUSED
    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ForegroundEventType.SCREEN_NON_INTERACTIVE
    UsageEvents.Event.KEYGUARD_SHOWN -> ForegroundEventType.KEYGUARD_SHOWN
    UsageEvents.Event.DEVICE_SHUTDOWN -> ForegroundEventType.DEVICE_SHUTDOWN
    else -> null
}

private class ForegroundFold(
    private val endMs: Long,
    private val excludedPackages: Set<String>,
) {
    private var openPkg: String? = null
    private var openSince: Long = 0L
    private val sessions = ArrayList<TargetSession>()

    fun add(pkg: String, type: ForegroundEventType, ts: Long) {
        if (ts >= endMs) return
        when (type) {
            ForegroundEventType.ACTIVITY_RESUMED -> onResumed(pkg, ts)
            ForegroundEventType.ACTIVITY_PAUSED -> onPaused(pkg, ts)
            ForegroundEventType.SCREEN_NON_INTERACTIVE,
            ForegroundEventType.KEYGUARD_SHOWN,
            ForegroundEventType.DEVICE_SHUTDOWN,
            -> closeAt(ts)
        }
    }

    fun finish(): List<TargetSession> {
        closeAt(endMs)
        return sessions
    }

    private fun onResumed(pkg: String, ts: Long) {
        if (pkg.isNotEmpty() && pkg == openPkg) return
        closeAt(ts)
        if (pkg.isNotEmpty() && pkg !in excludedPackages) {
            openPkg = pkg
            openSince = ts
        }
    }

    private fun onPaused(pkg: String, ts: Long) {
        if (pkg.isEmpty() || pkg != openPkg) return
        closeAt(ts)
    }

    private fun closeAt(ts: Long) {
        val pkg = openPkg ?: return
        val since = openSince
        openPkg = null
        if (ts > since) sessions.add(TargetSession(Policy.targetId(TargetKind.APP, pkg), since, ts))
    }
}

/**
 * Takes [amount] off the browser rows, biggest first.
 *
 * Largest-first so the overlap comes off the browser that held the time when several are
 * installed. Ties break by target id so two equal browsers do not trade which one was reduced
 * between runs. A browser with no time this day is skipped rather than written as a zero row.
 */
private fun subtractLargestFirst(apps: MutableMap<String, Long>, browserPackages: Set<String>, amount: Long) {
    if (amount <= 0L || browserPackages.isEmpty()) return
    var remaining = amount
    val keys = browserPackages
        .filter { it.isNotEmpty() }
        .map { Policy.targetId(TargetKind.APP, it) }
        .sortedWith(compareByDescending<String> { apps[it] ?: 0L }.thenBy { it })
    for (key in keys) {
        if (remaining <= 0L) return
        val have = apps[key] ?: 0L
        if (have <= 0L) continue
        val take = minOf(have, remaining)
        remaining -= take
        val left = have - take
        if (left > 0L) apps[key] = left else apps.remove(key)
    }
}

private suspend fun assemble(context: Context, dayStarts: List<Long>, nowMs: Long): ScreenTime {
    val granted = hasUsageAccess(context)
    if (!granted) return ScreenTime(ledgerBuckets(context, dayStarts, nowMs), usageAccessGranted = false)
    if (dayStarts.isEmpty()) return ScreenTime(emptyList(), usageAccessGranted = true)
    val todayStart = dayStarts.filter { it <= nowMs }.maxOrNull()
        ?: return ScreenTime(dayStarts.map { DayBucket(it, emptyMap()) }, usageAccessGranted = true)
    val completed = dayStarts.filter { it < todayStart }
    val stored = HashMap<Long, Map<String, Long>>()
    if (completed.isNotEmpty()) {
        val grouped = HashMap<Long, HashMap<String, Long>>()
        for (row in Rotblock.database(context).dailyUsageDao().forDays(completed)) {
            grouped.getOrPut(row.dayStartMs) { HashMap() }[row.key] = row.ms
        }
        stored.putAll(grouped)
    }
    val today = liveToday(context, todayStart, nowMs)
    val buckets = dayStarts.map { start ->
        when {
            start == todayStart -> today
            start < todayStart -> DayBucket(start, stored[start].orEmpty())
            else -> DayBucket(start, emptyMap())
        }
    }
    return ScreenTime(buckets, usageAccessGranted = true)
}

private suspend fun liveToday(context: Context, todayStart: Long, nowMs: Long): DayBucket {
    val ledger = ledgerByDay(context, listOf(todayStart), nowMs)[todayStart].orEmpty()
    val device = readForegroundSessions(
        context,
        todayStart,
        nowMs,
        excludedPackages(defaultHomePackage(context)),
    ) ?: return DayBucket(todayStart, ledger)
    val deviceDay = bucketByDay(device, listOf(todayStart), nowMs).firstOrNull()?.perTarget.orEmpty()
    val packages = HashMap<String, Long>()
    for ((key, ms) in deviceDay) {
        if (!key.startsWith("app:") || ms <= 0L) continue
        val pkg = key.removePrefix("app:")
        if (pkg.isNotEmpty()) packages[pkg] = ms
    }
    return DayBucket(
        todayStart,
        mergeUsageDay(packages, ledger, BrowserRegistry.packages, feedPackagesByTarget()),
    )
}

private suspend fun ledgerBuckets(context: Context, dayStarts: List<Long>, nowMs: Long): List<DayBucket> {
    if (dayStarts.isEmpty()) return emptyList()
    return bucketByDay(ledgerSessions(context, dayStarts.first()), dayStarts, nowMs)
}

private suspend fun ledgerByDay(
    context: Context,
    dayStarts: List<Long>,
    nowMs: Long,
): Map<Long, Map<String, Long>> {
    if (dayStarts.isEmpty()) return emptyMap()
    return bucketByDay(ledgerSessions(context, dayStarts.first()), dayStarts, nowMs)
        .associate { it.dayStartMs to it.perTarget }
}

private suspend fun ledgerSessions(context: Context, sinceMs: Long): List<TargetSession> =
    Rotblock.database(context).usageDao().since(sinceMs).map {
        TargetSession(it.targetId, it.startMs, it.endMs)
    }
