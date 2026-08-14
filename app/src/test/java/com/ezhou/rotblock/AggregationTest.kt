package com.ezhou.rotblock

import com.ezhou.rotblock.insights.Category
import com.ezhou.rotblock.insights.Classification
import com.ezhou.rotblock.insights.Productivity
import com.ezhou.rotblock.insights.TargetSession
import com.ezhou.rotblock.insights.bucketByDay
import com.ezhou.rotblock.insights.classifyTotals
import com.ezhou.rotblock.insights.countByDay
import com.ezhou.rotblock.insights.recentDayStarts
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
private const val MIN = 60_000L
private const val HOUR = 60 * MIN

private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    Calendar.getInstance(UTC).apply {
        clear()
        set(2026, Calendar.JULY, day, hour, minute, 0)
    }.timeInMillis

class RecentDayStartsTest {

    @Test
    fun `returns the requested number of days in ascending order`() {
        val starts = recentDayStarts(at(26, 10), dayStartHour = 4, days = 3, tz = UTC)
        assertEquals(listOf(at(24, 4), at(25, 4), at(26, 4)), starts)
    }

    @Test
    fun `before the rollover hour the newest bucket is yesterday`() {
        val starts = recentDayStarts(at(26, 2), dayStartHour = 4, days = 2, tz = UTC)
        assertEquals(listOf(at(24, 4), at(25, 4)), starts)
    }
}

class BucketByDayTest {

    private val days = listOf(at(24, 4), at(25, 4), at(26, 4))
    private val end = at(27, 4)

    @Test
    fun `a session lands entirely in its own day`() {
        val sessions = listOf(TargetSession("app:reddit", at(25, 10), at(25, 10, 30)))
        val buckets = bucketByDay(sessions, days, end)
        assertEquals(0L, buckets[0].totalMs)
        assertEquals(30 * MIN, buckets[1].perTarget["app:reddit"])
        assertEquals(0L, buckets[2].totalMs)
    }

    @Test
    fun `a session spanning the rollover is split between both days`() {
        // 3:50am to 4:10am with a 4am rollover: ten minutes each side.
        val sessions = listOf(TargetSession("app:reddit", at(25, 3, 50), at(25, 4, 10)))
        val buckets = bucketByDay(sessions, days, end)
        assertEquals(10 * MIN, buckets[0].perTarget["app:reddit"])
        assertEquals(10 * MIN, buckets[1].perTarget["app:reddit"])
    }

    @Test
    fun `sessions outside the range are ignored`() {
        val sessions = listOf(TargetSession("app:reddit", at(20, 10), at(20, 11)))
        assertEquals(0L, bucketByDay(sessions, days, end).sumOf { it.totalMs })
    }

    @Test
    fun `a session is clipped at the end of the range`() {
        val sessions = listOf(TargetSession("app:reddit", at(27, 3), at(27, 5)))
        val buckets = bucketByDay(sessions, days, end)
        assertEquals(1 * HOUR, buckets[2].perTarget["app:reddit"])
    }

    @Test
    fun `multiple targets accumulate separately`() {
        val sessions = listOf(
            TargetSession("app:reddit", at(25, 10), at(25, 10, 20)),
            TargetSession("web:youtube.com/shorts", at(25, 11), at(25, 11, 5)),
            TargetSession("app:reddit", at(25, 12), at(25, 12, 10)),
        )
        val buckets = bucketByDay(sessions, days, end)
        assertEquals(30 * MIN, buckets[1].perTarget["app:reddit"])
        assertEquals(5 * MIN, buckets[1].perTarget["web:youtube.com/shorts"])
        assertEquals(35 * MIN, buckets[1].totalMs)
    }

    @Test
    fun `empty day list yields no buckets`() {
        assertEquals(emptyList<Any>(), bucketByDay(listOf(TargetSession("a", 0, 1)), emptyList(), end))
    }
}

class CountByDayTest {

    private val days = listOf(at(24, 4), at(25, 4), at(26, 4))
    private val end = at(27, 4)

    @Test
    fun `counts events into their day`() {
        val stamps = listOf(at(24, 9), at(25, 9), at(25, 20), at(26, 6))
        assertEquals(listOf(1, 2, 1), countByDay(stamps, days, end))
    }

    @Test
    fun `an event exactly on a boundary belongs to the later day`() {
        assertEquals(listOf(0, 1, 0), countByDay(listOf(at(25, 4)), days, end))
    }

    @Test
    fun `events outside the range are dropped`() {
        assertEquals(listOf(0, 0, 0), countByDay(listOf(at(1, 9), at(28, 9)), days, end))
    }
}

class ClassifyTotalsTest {

    private val socialDistracting =
        Classification(Category.SOCIAL, Productivity.VERY_DISTRACTING)
    private val classify: (String) -> Classification =
        { pkg -> if (pkg == "com.reddit.frontpage") socialDistracting else Classification() }

    @Test
    fun `an app target classifies by its own package`() {
        val sessions = listOf(TargetSession("app:com.reddit.frontpage", 0, 10 * MIN))
        val totals = classifyTotals(sessions, classify)
        assertEquals(10 * MIN, totals.byCategory[Category.SOCIAL])
        assertEquals(10 * MIN, totals.byProductivity[Productivity.VERY_DISTRACTING])
    }

    @Test
    fun `a feed target classifies by the app it lives inside`() {
        // youtube_shorts is registered against com.google.android.youtube in FeedRegistry.
        val classifyYoutube: (String) -> Classification =
            { pkg -> if (pkg == "com.google.android.youtube") Classification(Category.VIDEO, Productivity.DISTRACTING) else Classification() }
        val sessions = listOf(TargetSession("feed:youtube_shorts", 0, 5 * MIN))
        val totals = classifyTotals(sessions, classifyYoutube)
        assertEquals(5 * MIN, totals.byCategory[Category.VIDEO])
        assertEquals(5 * MIN, totals.byProductivity[Productivity.DISTRACTING])
    }

    @Test
    fun `a website target has no offline package lookup and stays unclassified`() {
        // The classifier would answer for this host if it were ever asked -- it must not be.
        val classifyAnything: (String) -> Classification = { socialDistracting }
        val sessions = listOf(TargetSession("web:reddit.com", 0, 10 * MIN))
        val totals = classifyTotals(sessions, classifyAnything)
        assertEquals(10 * MIN, totals.byCategory[Category.UNKNOWN])
        assertEquals(10 * MIN, totals.byProductivity[Productivity.UNCLASSIFIED])
    }

    @Test
    fun `sessions with no matching catalog entry accumulate as unclassified`() {
        val sessions = listOf(TargetSession("app:com.unknown.thing", 0, 3 * MIN))
        val totals = classifyTotals(sessions, classify)
        assertEquals(3 * MIN, totals.byCategory[Category.UNKNOWN])
        assertEquals(3 * MIN, totals.byProductivity[Productivity.UNCLASSIFIED])
    }

    @Test
    fun `zero and negative duration sessions contribute nothing`() {
        val sessions = listOf(
            TargetSession("app:com.reddit.frontpage", 10, 10),
            TargetSession("app:com.reddit.frontpage", 20, 10),
        )
        val totals = classifyTotals(sessions, classify)
        assertEquals(emptyMap<Category, Long>(), totals.byCategory)
        assertEquals(emptyMap<Productivity, Long>(), totals.byProductivity)
    }

    @Test
    fun `multiple targets of different kinds accumulate separately`() {
        val sessions = listOf(
            TargetSession("app:com.reddit.frontpage", 0, 10 * MIN),
            TargetSession("app:com.unknown.thing", 0, 4 * MIN),
        )
        val totals = classifyTotals(sessions, classify)
        assertEquals(10 * MIN, totals.byCategory[Category.SOCIAL])
        assertEquals(4 * MIN, totals.byCategory[Category.UNKNOWN])
        assertEquals(14 * MIN, totals.byCategory.values.sum())
    }
}
