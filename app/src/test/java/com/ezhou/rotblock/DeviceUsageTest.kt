package com.ezhou.rotblock

import com.ezhou.rotblock.data.DailyUsage
import com.ezhou.rotblock.insights.DayBucket
import com.ezhou.rotblock.insights.ForegroundEvent
import com.ezhou.rotblock.insights.ForegroundEventType
import com.ezhou.rotblock.insights.TargetSession
import com.ezhou.rotblock.insights.excludedPackages
import com.ezhou.rotblock.insights.foldUsageEvents
import com.ezhou.rotblock.insights.mergeUsageDay
import com.ezhou.rotblock.insights.rollCompletedDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
private const val MIN = 60_000L
private const val HOUR = 60 * MIN

private const val CHROME = "com.android.chrome"
private const val SAMSUNG = "com.sec.android.app.sbrowser"
private const val YOUTUBE = "com.google.android.youtube"
private const val LAUNCHER = "com.sec.android.app.launcher"
private const val SYSTEMUI = "com.android.systemui"

private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    Calendar.getInstance(UTC).apply {
        clear()
        set(2026, Calendar.JULY, day, hour, minute, 0)
    }.timeInMillis

private fun resumed(pkg: String, ts: Long) =
    ForegroundEvent(pkg, ForegroundEventType.ACTIVITY_RESUMED, ts)

private fun paused(pkg: String, ts: Long) =
    ForegroundEvent(pkg, ForegroundEventType.ACTIVITY_PAUSED, ts)

private fun closed(type: ForegroundEventType, ts: Long) = ForegroundEvent("", type, ts)

private fun oneDay(
    events: List<ForegroundEvent>,
    end: Long = at(27, 4),
    excluded: Set<String> = emptySet(),
): DayBucket = foldUsageEvents(events, end, listOf(at(26, 4)), excluded).single()

class FoldUsageEventsTest {

    @Test
    fun `a resume and its pause land in that day`() {
        val bucket = oneDay(listOf(resumed(CHROME, at(26, 10)), paused(CHROME, at(26, 10, 20))))
        assertEquals(20 * MIN, bucket.perTarget["app:$CHROME"])
        assertEquals(20 * MIN, bucket.totalMs)
    }

    @Test
    fun `a resume of a different package closes the one that was open`() {
        val bucket = oneDay(
            listOf(
                resumed(CHROME, at(26, 10)),
                resumed(YOUTUBE, at(26, 10, 10)),
                paused(YOUTUBE, at(26, 10, 25)),
            ),
        )
        assertEquals(10 * MIN, bucket.perTarget["app:$CHROME"])
        assertEquals(15 * MIN, bucket.perTarget["app:$YOUTUBE"])
        assertEquals(25 * MIN, bucket.totalMs)
    }

    @Test
    fun `a pause for a package that is not open is dropped`() {
        val bucket = oneDay(
            listOf(
                resumed(CHROME, at(26, 10)),
                paused(YOUTUBE, at(26, 10, 10)),
                paused(CHROME, at(26, 10, 30)),
            ),
        )
        assertEquals(30 * MIN, bucket.perTarget["app:$CHROME"])
        assertNull(bucket.perTarget["app:$YOUTUBE"])
    }

    @Test
    fun `a pause with no open resume is dropped`() {
        val bucket = oneDay(listOf(paused(CHROME, at(26, 10, 30))))
        assertEquals(0L, bucket.totalMs)
    }

    @Test
    fun `a resume with no pause is closed at the end of the fold`() {
        val bucket = oneDay(
            listOf(resumed(CHROME, at(26, 10))),
            end = at(26, 10, 30),
        )
        assertEquals(30 * MIN, bucket.perTarget["app:$CHROME"])
    }

    @Test
    fun `screen off closes the open app and a later pause of it is dropped`() {
        assertClosedBy(ForegroundEventType.SCREEN_NON_INTERACTIVE)
    }

    @Test
    fun `the keyguard closes the open app and a later pause of it is dropped`() {
        assertClosedBy(ForegroundEventType.KEYGUARD_SHOWN)
    }

    @Test
    fun `shutdown closes the open app and a later pause of it is dropped`() {
        assertClosedBy(ForegroundEventType.DEVICE_SHUTDOWN)
    }

    @Test
    fun `a second resume of the same package keeps the time since the first`() {
        val bucket = oneDay(
            listOf(
                resumed(CHROME, at(26, 10)),
                resumed(CHROME, at(26, 10, 10)),
                paused(CHROME, at(26, 10, 40)),
            ),
        )
        assertEquals(40 * MIN, bucket.perTarget["app:$CHROME"])
    }

    @Test
    fun `the launcher and system ui are not counted and their resume ends the previous app`() {
        val bucket = oneDay(
            listOf(
                resumed(CHROME, at(26, 10)),
                resumed(LAUNCHER, at(26, 10, 20)),
                resumed(SYSTEMUI, at(26, 10, 25)),
                resumed(CHROME, at(26, 10, 30)),
                paused(CHROME, at(26, 10, 40)),
            ),
            excluded = excludedPackages(LAUNCHER),
        )
        assertEquals(30 * MIN, bucket.perTarget["app:$CHROME"])
        assertEquals(setOf("app:$CHROME"), bucket.perTarget.keys)
    }

    @Test
    fun `a resume with no package closes the open app and opens nothing`() {
        val bucket = oneDay(
            listOf(
                resumed(CHROME, at(26, 10)),
                resumed("", at(26, 10, 12)),
            ),
            end = at(26, 11),
        )
        assertEquals(12 * MIN, bucket.perTarget["app:$CHROME"])
        assertEquals(12 * MIN, bucket.totalMs)
    }

    @Test
    fun `events at or after the end of the fold are ignored`() {
        val end = at(26, 12)
        val bucket = oneDay(
            listOf(
                resumed(CHROME, at(26, 11)),
                paused(CHROME, end),
                resumed(YOUTUBE, end),
            ),
            end = end,
        )
        assertEquals(HOUR, bucket.perTarget["app:$CHROME"])
        assertNull(bucket.perTarget["app:$YOUTUBE"])
    }

    @Test
    fun `a session is split across the budget-day boundary`() {
        val buckets = foldUsageEvents(
            listOf(resumed(CHROME, at(26, 3)), paused(CHROME, at(26, 5))),
            at(27, 4),
            listOf(at(25, 4), at(26, 4)),
            emptySet(),
        )
        assertEquals(HOUR, buckets[0].perTarget["app:$CHROME"])
        assertEquals(HOUR, buckets[1].perTarget["app:$CHROME"])
    }

    private fun assertClosedBy(type: ForegroundEventType) {
        val bucket = oneDay(
            listOf(
                resumed(CHROME, at(26, 10)),
                closed(type, at(26, 10, 5)),
                paused(CHROME, at(26, 10, 20)),
            ),
        )
        assertEquals(5 * MIN, bucket.perTarget["app:$CHROME"])
        assertEquals(5 * MIN, bucket.totalMs)
    }
}

class ExcludedPackagesTest {

    @Test
    fun `a missing or blank home package still excludes system ui`() {
        assertEquals(setOf(SYSTEMUI), excludedPackages(null))
        assertEquals(setOf(SYSTEMUI), excludedPackages("  "))
    }

    @Test
    fun `the resolved home package is excluded alongside system ui`() {
        assertEquals(setOf(SYSTEMUI, LAUNCHER), excludedPackages(LAUNCHER))
    }
}

class MergeUsageDayTest {

    private val browsers = setOf(CHROME, SAMSUNG)
    private val feeds = mapOf("feed:youtube_shorts" to YOUTUBE)

    @Test
    fun `web time comes off the largest browser and a non-browser is left alone`() {
        val merged = mergeUsageDay(
            mapOf(CHROME to 50 * MIN, SAMSUNG to 20 * MIN, YOUTUBE to 40 * MIN),
            mapOf("web:reddit.com" to 30 * MIN),
            browsers,
            feeds,
        )
        assertEquals(
            mapOf(
                "app:$CHROME" to 20 * MIN,
                "app:$SAMSUNG" to 20 * MIN,
                "app:$YOUTUBE" to 40 * MIN,
                "web:reddit.com" to 30 * MIN,
            ),
            merged,
        )
    }

    @Test
    fun `an equal browser tie breaks toward the earlier target id`() {
        val merged = mergeUsageDay(
            mapOf(CHROME to 40 * MIN, SAMSUNG to 40 * MIN),
            mapOf("web:reddit.com" to 30 * MIN),
            browsers,
            emptyMap(),
        )
        assertEquals(10 * MIN, merged["app:$CHROME"])
        assertEquals(40 * MIN, merged["app:$SAMSUNG"])
        assertEquals(30 * MIN, merged["web:reddit.com"])
    }

    @Test
    fun `web time past every browser clamps them out and ignores ledger app and group rows`() {
        val merged = mergeUsageDay(
            mapOf(CHROME to 40 * MIN, SAMSUNG to 20 * MIN),
            mapOf(
                "web:reddit.com" to 100 * MIN,
                "app:$CHROME" to 999 * MIN,
                "group:brainrot" to 50 * MIN,
            ),
            browsers,
            emptyMap(),
        )
        assertEquals(mapOf("web:reddit.com" to 100 * MIN), merged)
    }

    @Test
    fun `a feed is taken out of its host app`() {
        val merged = mergeUsageDay(
            mapOf(YOUTUBE to 40 * MIN),
            mapOf("feed:youtube_shorts" to 15 * MIN),
            emptySet(),
            feeds,
        )
        assertEquals(25 * MIN, merged["app:$YOUTUBE"])
        assertEquals(15 * MIN, merged["feed:youtube_shorts"])
    }

    @Test
    fun `a feed larger than its host removes the host and keeps the feed`() {
        val merged = mergeUsageDay(
            mapOf(YOUTUBE to 10 * MIN),
            mapOf("feed:youtube_shorts" to 50 * MIN),
            emptySet(),
            feeds,
        )
        assertNull(merged["app:$YOUTUBE"])
        assertEquals(50 * MIN, merged["feed:youtube_shorts"])
    }

    @Test
    fun `two feeds on one package both come off it`() {
        val merged = mergeUsageDay(
            mapOf(YOUTUBE to 40 * MIN),
            mapOf("feed:a" to 15 * MIN, "feed:b" to 10 * MIN),
            emptySet(),
            mapOf("feed:a" to YOUTUBE, "feed:b" to YOUTUBE),
        )
        assertEquals(15 * MIN, merged["app:$YOUTUBE"])
        assertEquals(15 * MIN, merged["feed:a"])
        assertEquals(10 * MIN, merged["feed:b"])
    }

    @Test
    fun `a feed whose package was not on screen is kept and subtracts nothing`() {
        val merged = mergeUsageDay(
            mapOf(CHROME to 40 * MIN),
            mapOf("feed:youtube_shorts" to 15 * MIN),
            browsers,
            feeds,
        )
        assertEquals(40 * MIN, merged["app:$CHROME"])
        assertEquals(15 * MIN, merged["feed:youtube_shorts"])
        assertNull(merged["app:$YOUTUBE"])
    }

    @Test
    fun `zero and negative durations are dropped`() {
        val merged = mergeUsageDay(
            mapOf(CHROME to 0L, YOUTUBE to -1L),
            mapOf("web:reddit.com" to 0L, "feed:youtube_shorts" to -5L),
            browsers,
            feeds,
        )
        assertEquals(emptyMap<String, Long>(), merged)
    }
}

class RollCompletedDaysTest {

    @Test
    fun `completed days split at the boundary, subtract sites and feeds, and ignore ledger apps`() {
        val chrome = "app:$CHROME"
        val rows = rollCompletedDays(
            deviceSessions = listOf(
                TargetSession(chrome, at(26, 3, 50), at(26, 4, 40)),
                TargetSession("app:$YOUTUBE", at(26, 5), at(26, 5, 30)),
            ),
            ledgerSessions = listOf(
                TargetSession("web:reddit.com", at(26, 3, 50), at(26, 4)),
                TargetSession(chrome, at(26, 10), at(26, 12)),
                TargetSession("feed:youtube_shorts", at(26, 5), at(26, 5, 10)),
                TargetSession("group:brainrot", at(26, 5), at(26, 6)),
            ),
            dayStarts = listOf(at(25, 4), at(26, 4)),
            rangeEndMs = at(27, 4),
            browserPackages = setOf(CHROME, SAMSUNG),
            feedPackageByTarget = mapOf("feed:youtube_shorts" to YOUTUBE),
        )
        assertEquals(4, rows.size)
        assertEquals(
            setOf(
                DailyUsage(at(25, 4), "web:reddit.com", 10 * MIN),
                DailyUsage(at(26, 4), chrome, 40 * MIN),
                DailyUsage(at(26, 4), "app:$YOUTUBE", 20 * MIN),
                DailyUsage(at(26, 4), "feed:youtube_shorts", 10 * MIN),
            ),
            rows.toSet(),
        )
    }
}
