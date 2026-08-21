package com.ezhou.rotblock

import com.ezhou.rotblock.service.EvaluationRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rates here are the ones measured on device: a healthy loop runs a few evaluations a second,
 * and the storm this detector exists for ran at about sixty-two.
 */
class EvaluationRateTest {

    private fun quietRun(rate: EvaluationRate, perSecond: Int, seconds: Int, startMs: Long = 0L): Long {
        var t = startMs
        val step = 1000L / perSecond
        repeat(perSecond * seconds) {
            assertEquals(EvaluationRate.Verdict.Quiet, rate.evaluated(t))
            t += step
        }
        return t
    }

    @Test
    fun `the ticker floor alone never storms`() {
        quietRun(EvaluationRate(), perSecond = 1, seconds = 600)
    }

    @Test
    fun `a busy page at the event ceiling never storms`() {
        // Two event types coalesced at notificationTimeout=200ms is about ten a second, and that
        // is a page being scrolled -- the single most common thing this service sees.
        quietRun(EvaluationRate(), perSecond = 10, seconds = 300)
    }

    @Test
    fun `the measured storm rate is caught within the first second`() {
        val rate = EvaluationRate()
        var t = 0L
        var began: EvaluationRate.Verdict.Began? = null
        repeat(62) {
            val v = rate.evaluated(t)
            if (v is EvaluationRate.Verdict.Began && began == null) began = v
            t += 1000L / 62
        }
        assertTrue("a 62/s storm must be reported", began != null)
        assertEquals(20, began!!.perSecond)
        assertTrue(rate.storming)
    }

    @Test
    fun `a storm is bracketed when the rate comes back down`() {
        val rate = EvaluationRate()
        var t = 0L
        // Storm for about two seconds at the measured rate.
        repeat(124) { rate.evaluated(t); t += 1000L / 62 }
        assertTrue(rate.storming)

        // Then back to the ticker floor. The first completed quiet window ends it.
        var ended: EvaluationRate.Verdict.Ended? = null
        repeat(5) {
            val v = rate.evaluated(t)
            if (v is EvaluationRate.Verdict.Ended && ended == null) ended = v
            t += 1000L
        }
        assertTrue("a storm that stops must be bracketed", ended != null)
        assertTrue("duration covers the storm", ended!!.durationMs >= 1900)
        assertTrue("evaluations are counted", ended!!.evaluations >= 100)
        assertFalse(rate.storming)
    }

    @Test
    fun `one storm reports once, however long it runs`() {
        val rate = EvaluationRate()
        var t = 0L
        var begins = 0
        repeat(62 * 100) {
            if (rate.evaluated(t) is EvaluationRate.Verdict.Began) begins++
            t += 1000L / 62
        }
        assertEquals("a 100-second storm is one event, not thousands", 1, begins)
    }

    @Test
    fun `a storm ended by the target going away is still bracketed`() {
        val rate = EvaluationRate()
        var t = 0L
        repeat(62) { rate.evaluated(t); t += 1000L / 62 }
        assertTrue(rate.storming)

        val ended = rate.settle(5_000L)
        assertTrue(ended is EvaluationRate.Verdict.Ended)
        // Measured from where the storm was declared -- the 20th evaluation, at 16ms apart --
        // rather than from the first evaluation, which was not yet a storm.
        assertEquals(5_000L - 19 * (1000L / 62), (ended as EvaluationRate.Verdict.Ended).durationMs)
        assertFalse(rate.storming)
    }

    @Test
    fun `settling when nothing is wrong reports nothing`() {
        val rate = EvaluationRate()
        quietRun(rate, perSecond = 5, seconds = 10)
        assertEquals(EvaluationRate.Verdict.Quiet, rate.settle(60_000L))
    }

    @Test
    fun `a clock that jumps backwards does not invent a storm`() {
        val rate = EvaluationRate()
        repeat(50) {
            // Every call reports an earlier time than the last.
            assertEquals(EvaluationRate.Verdict.Quiet, rate.evaluated(10_000L - it * 100L))
        }
    }
}
