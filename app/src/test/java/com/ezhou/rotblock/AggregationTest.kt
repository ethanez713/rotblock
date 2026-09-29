package com.ezhou.rotblock

import com.ezhou.rotblock.insights.DayBucket
import com.ezhou.rotblock.insights.ScreenSegment
import com.ezhou.rotblock.insights.ScreenTimeSlots
import com.ezhou.rotblock.insights.SegmentSlot
import com.ezhou.rotblock.insights.TargetSession
import com.ezhou.rotblock.insights.TimeSplit
import com.ezhou.rotblock.insights.bucketByDay
import com.ezhou.rotblock.insights.chartIsWeekly
import com.ezhou.rotblock.insights.countByDay
import com.ezhou.rotblock.insights.foldBucketsIntoWeeks
import com.ezhou.rotblock.insights.foldCountsIntoWeeks
import com.ezhou.rotblock.insights.recentDayStarts
import com.ezhou.rotblock.insights.rotblockedKeys
import com.ezhou.rotblock.insights.screenTimeSegments
import com.ezhou.rotblock.insights.screenTimeSlots
import com.ezhou.rotblock.insights.splitPercents
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

class WeekFoldTest {

    @Test
    fun `only the 90 and 365 ranges draw weeks`() {
        assertFalse(chartIsWeekly(7))
        assertFalse(chartIsWeekly(30))
        assertTrue(chartIsWeekly(90))
        assertTrue(chartIsWeekly(365))
    }

    @Test
    fun `the newest bucket is the last seven days and the oldest keeps the remainder`() {
        val counts = (1..8).toList()
        assertEquals(listOf(1, 2 + 3 + 4 + 5 + 6 + 7 + 8), foldCountsIntoWeeks(counts))
    }

    @Test
    fun `an exact multiple of seven has no partial bucket`() {
        assertEquals(listOf(7, 7), foldCountsIntoWeeks(List(14) { 1 }))
    }

    @Test
    fun `fewer than seven days is one partial bucket`() {
        assertEquals(listOf(6), foldCountsIntoWeeks(List(3) { 2 }))
    }

    @Test
    fun `an empty range stays empty`() {
        assertEquals(emptyList<Int>(), foldCountsIntoWeeks(emptyList()))
        assertEquals(emptyList<DayBucket>(), foldBucketsIntoWeeks(emptyList()))
    }

    @Test
    fun `ninety and three hundred sixty five days end on a full week`() {
        val ninety = foldCountsIntoWeeks(List(90) { 1 })
        assertEquals(13, ninety.size)
        assertEquals(6, ninety.first())
        assertEquals(7, ninety.last())
        assertEquals(90, ninety.sum())

        val year = foldCountsIntoWeeks(List(365) { 1 })
        assertEquals(53, year.size)
        assertEquals(1, year.first())
        assertEquals(7, year.last())
        assertEquals(365, year.sum())
    }

    @Test
    fun `a week sums its days and is labelled by its oldest day`() {
        val days = (0 until 8).map { index ->
            DayBucket(
                dayStartMs = index.toLong(),
                perTarget = buildMap {
                    put("app:a", 10L)
                    if (index == 0) put("app:b", 5L)
                },
            )
        }
        val weeks = foldBucketsIntoWeeks(days)
        val counts = foldCountsIntoWeeks(List(days.size) { 1 })

        assertEquals(counts.size, weeks.size)
        assertEquals(0L, weeks[0].dayStartMs)
        assertEquals(10L, weeks[0].perTarget["app:a"])
        assertEquals(5L, weeks[0].perTarget["app:b"])
        assertEquals(1L, weeks[1].dayStartMs)
        assertEquals(70L, weeks[1].perTarget["app:a"])
        assertEquals(null, weeks[1].perTarget["app:b"])
    }
}

class RotblockedKeysTest {

    @Test
    fun `every policy counts, including a disabled one and a group's members`() {
        val disabled = Policy(
            id = "app:com.reddit.frontpage",
            kind = TargetKind.APP,
            matcher = "com.reddit.frontpage",
            label = "Reddit",
            enabled = false,
        )
        val group = Policy(
            id = "group:rot",
            kind = TargetKind.GROUP,
            matcher = "rot",
            label = "Rot",
            members = listOf("app:a", "web:b"),
        )
        assertEquals(
            setOf("app:com.reddit.frontpage", "app:a", "web:b"),
            rotblockedKeys(listOf(disabled, group)),
        )
    }

    @Test
    fun `no policies covers nothing`() {
        assertEquals(emptySet<String>(), rotblockedKeys(emptyList()))
    }
}

class ScreenTimeSlotsTest {

    @Test
    fun `the top three on each side keep slots and ties break by key`() {
        val buckets = listOf(
            DayBucket(0, mapOf("app:a" to 10, "app:b" to 50, "app:c" to 30, "app:d" to 5, "app:e" to 100)),
            DayBucket(1, mapOf("app:a" to 10, "app:g" to 40, "app:f" to 1)),
        )
        val slots = screenTimeSlots(
            buckets,
            rotblocked = setOf("app:a", "app:b", "app:c", "app:d"),
        )
        // a = 20, b = 50, c = 30, d = 5. e = 100, g = 40, f = 1.
        assertEquals(listOf("app:b", "app:c", "app:a"), slots.warm)
        assertEquals(listOf("app:e", "app:g", "app:f"), slots.cool)
    }

    @Test
    fun `equal totals keep the earlier key in the earlier slot`() {
        val buckets = listOf(DayBucket(0, mapOf("app:b" to 10, "app:a" to 10, "app:c" to 10)))
        val slots = screenTimeSlots(buckets, rotblocked = setOf("app:a", "app:b", "app:c"))
        assertEquals(listOf("app:a", "app:b", "app:c"), slots.warm)
        assertEquals(emptyList<String>(), slots.cool)
    }

    @Test
    fun `a zero total takes no slot`() {
        val slots = screenTimeSlots(listOf(DayBucket(0, mapOf("app:a" to 0))), setOf("app:a"))
        assertEquals(emptyList<String>(), slots.warm)
    }
}

class ScreenTimeSegmentsTest {

    @Test
    fun `slot order is the validated stack, baseline first`() {
        assertEquals(
            listOf(
                SegmentSlot.WARM_0,
                SegmentSlot.WARM_1,
                SegmentSlot.WARM_2,
                SegmentSlot.OTHER_WARM,
                SegmentSlot.COOL_0,
                SegmentSlot.COOL_1,
                SegmentSlot.COOL_2,
                SegmentSlot.OTHER_COOL,
            ),
            SegmentSlot.entries,
        )
    }

    @Test
    fun `a taller later slot stays above the smaller one on the baseline`() {
        val slots = ScreenTimeSlots(
            warm = listOf("a", "b", "c"),
            cool = listOf("d", "e", "f"),
        )
        val rotblocked = setOf("a", "b", "c", "z", "z2")
        val segments = screenTimeSegments(
            mapOf("c" to 100, "a" to 1, "z" to 4, "z2" to 3, "e" to 50, "d" to 4, "x" to 9, "b" to 0),
            slots,
            rotblocked,
        )
        assertEquals(
            listOf(
                ScreenSegment(SegmentSlot.WARM_0, 1),
                ScreenSegment(SegmentSlot.WARM_2, 100),
                ScreenSegment(SegmentSlot.OTHER_WARM, 7),
                ScreenSegment(SegmentSlot.COOL_0, 4),
                ScreenSegment(SegmentSlot.COOL_1, 50),
                ScreenSegment(SegmentSlot.OTHER_COOL, 9),
            ),
            segments,
        )
    }

    @Test
    fun `a rotblocked key stays warm even when a slot list names it as cool`() {
        val slots = ScreenTimeSlots(warm = emptyList(), cool = listOf("app:ruled"))
        val segments = screenTimeSegments(
            mapOf("app:ruled" to 5),
            slots,
            rotblocked = setOf("app:ruled"),
        )
        assertEquals(listOf(ScreenSegment(SegmentSlot.OTHER_WARM, 5)), segments)
    }
}

class SplitPercentsTest {

    @Test
    fun `percentages add to 100 and a sliver stays visible`() {
        assertEquals(0 to 0, splitPercents(TimeSplit(0, 0)))
        assertEquals(100 to 0, splitPercents(TimeSplit(5, 0)))
        assertEquals(0 to 100, splitPercents(TimeSplit(0, 5)))
        assertEquals(40 to 60, splitPercents(TimeSplit(2, 3)))
        val sliver = splitPercents(TimeSplit(1, 10_000))
        assertEquals(100, sliver.first + sliver.second)
        assertTrue(sliver.first >= 1)
    }
}
