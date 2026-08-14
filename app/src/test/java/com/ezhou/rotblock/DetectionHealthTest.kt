package com.ezhou.rotblock

import com.ezhou.rotblock.block.FeedSpec
import com.ezhou.rotblock.setup.DetectionCounters
import com.ezhou.rotblock.setup.FeedProbe
import com.ezhou.rotblock.setup.MIN_LOOKS
import com.ezhou.rotblock.setup.MIN_WINDOW_MS
import com.ezhou.rotblock.setup.WINDOW_RESET_MS
import com.ezhou.rotblock.setup.rottedFeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When "this feed was never recognised" means the view id rotted, and when it means nothing.
 *
 * The whole risk here is a false alarm. A registry warning that fires because you have not opened
 * Instagram this week is a warning that gets ignored, and then the real one is ignored too.
 */
class DetectionHealthTest {

    private val old = MIN_WINDOW_MS * 2

    private fun probe(looks: Int, hits: Int, key: String = "youtube_shorts") =
        FeedProbe(feedKey = key, label = "YouTube Shorts", looks = looks, hits = hits)

    @Test
    fun `a feed that was looked for and never found is rotted`() {
        val rotted = rottedFeeds(listOf(probe(looks = MIN_LOOKS, hits = 0)), old)
        assertEquals(listOf("youtube_shorts"), rotted.map { it.feedKey })
    }

    @Test
    fun `a rule you have barely exercised does not alarm`() {
        assertTrue(rottedFeeds(listOf(probe(looks = MIN_LOOKS - 1, hits = 0)), old).isEmpty())
    }

    @Test
    fun `one hit proves the id still matches, however many misses follow`() {
        assertTrue(rottedFeeds(listOf(probe(looks = 5_000, hits = 1)), old).isEmpty())
    }

    @Test
    fun `a fresh install does not alarm however hard it is used`() {
        // Opening YouTube fifty times on day one without once opening Shorts is ordinary use, not
        // a broken view id.
        assertTrue(rottedFeeds(listOf(probe(looks = 10_000, hits = 0)), MIN_WINDOW_MS - 1).isEmpty())
    }

    @Test
    fun `feeds are judged one at a time`() {
        val probes = listOf(
            probe(looks = MIN_LOOKS, hits = 0, key = "youtube_shorts"),
            probe(looks = MIN_LOOKS, hits = 3, key = "instagram_reels"),
        )
        assertEquals(listOf("youtube_shorts"), rottedFeeds(probes, old).map { it.feedKey })
    }

    @Test
    fun `looks count per package and hits per feed`() {
        // Two feeds in one app share a denominator and rot independently, which is why the counters
        // are keyed differently from each other.
        val specs = listOf(
            FeedSpec("a", "A", "com.example", listOf("x")),
            FeedSpec("b", "B", "com.example", listOf("y")),
        )
        var counters = DetectionCounters(windowStartMs = 1L)
        repeat(3) { counters = counters.withLook("com.example") }
        counters = counters.withHit("a")

        val probes = counters.probes(specs).associateBy { it.feedKey }
        assertEquals(3, probes.getValue("a").looks)
        assertEquals(3, probes.getValue("b").looks)
        assertEquals(1, probes.getValue("a").hits)
        assertEquals(0, probes.getValue("b").hits)
    }

    @Test
    fun `counters survive a round trip through their encoding`() {
        val counters = DetectionCounters(windowStartMs = 12_345L)
            .withLook("com.example").withLook("com.example").withLook("com.other")
            .withHit("a")
        assertEquals(counters, DetectionCounters.decode(counters.encode()))
    }

    @Test
    fun `an unreadable tally decodes to no alarm rather than throwing`() {
        for (junk in listOf("", "   ", "not a tally", "abc|x=1|y=2", "1|2", "1|x=|y=2")) {
            val decoded = DetectionCounters.decode(junk)
            assertTrue("'$junk' should not produce probes", decoded.probes(emptyList()).isEmpty())
        }
        assertEquals(DetectionCounters(), DetectionCounters.decode(null))
    }

    @Test
    fun `an empty tally round-trips`() {
        assertEquals(DetectionCounters(), DetectionCounters.decode(DetectionCounters().encode()))
    }

    @Test
    fun `the window starts on first use and is thrown away once it is old enough`() {
        val fresh = DetectionCounters().rolled(1_000L)
        assertEquals(1_000L, fresh.windowStartMs)

        val counted = fresh.withLook("com.example")
        assertEquals(counted, counted.rolled(1_000L + WINDOW_RESET_MS - 1))

        val rolled = counted.rolled(1_000L + WINDOW_RESET_MS)
        assertTrue(rolled.looks.isEmpty())
        assertEquals(1_000L + WINDOW_RESET_MS, rolled.windowStartMs)
    }
}
