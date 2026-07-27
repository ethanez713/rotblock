package com.ezhou.rotblock

import com.ezhou.rotblock.quota.RollingQuota
import com.ezhou.rotblock.quota.UsageSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val MIN = 60_000L
private const val HOUR = 60 * MIN

/** t=0 is "now" for readability; sessions are written as offsets back from it. */
private fun ago(minutes: Long) = NOW - minutes * MIN
private const val NOW = 1_000 * HOUR

class RollingQuotaTest {

    @Test
    fun `no sessions means nothing used and nothing to wait for`() {
        assertEquals(0L, RollingQuota.usedInWindow(emptyList(), NOW, HOUR))
        assertNull(RollingQuota.nextRelease(emptyList(), NOW, HOUR, 3 * MIN))
    }

    @Test
    fun `session entirely before the window does not count`() {
        val sessions = listOf(UsageSession(ago(90), ago(75)))
        assertEquals(0L, RollingQuota.usedInWindow(sessions, NOW, HOUR))
    }

    @Test
    fun `session straddling the window start counts only the part inside`() {
        // 10 min long, starting 65 min ago: 5 min of it is inside the trailing hour.
        val sessions = listOf(UsageSession(ago(65), ago(55)))
        assertEquals(5 * MIN, RollingQuota.usedInWindow(sessions, NOW, HOUR))
    }

    @Test
    fun `session is clipped at now`() {
        val sessions = listOf(UsageSession(ago(2), NOW + 5 * MIN))
        assertEquals(2 * MIN, RollingQuota.usedInWindow(sessions, NOW, HOUR))
    }

    @Test
    fun `overlapping sessions are not double counted`() {
        val sessions = listOf(
            UsageSession(ago(30), ago(20)),
            UsageSession(ago(25), ago(10)), // overlaps the first by 5 min
        )
        assertEquals(20 * MIN, RollingQuota.usedInWindow(sessions, NOW, HOUR))
    }

    @Test
    fun `touching sessions merge without gaps or double counting`() {
        val sessions = listOf(
            UsageSession(ago(30), ago(20)),
            UsageSession(ago(20), ago(15)),
        )
        assertEquals(15 * MIN, RollingQuota.usedInWindow(sessions, NOW, HOUR))
    }

    @Test
    fun `zero length sessions are ignored`() {
        val sessions = listOf(UsageSession(ago(10), ago(10)), UsageSession(ago(5), ago(4)))
        assertEquals(1 * MIN, RollingQuota.usedInWindow(sessions, NOW, HOUR))
    }

    @Test
    fun `under quota releases immediately`() {
        val sessions = listOf(UsageSession(ago(10), ago(8))) // 2 min used
        assertNull(RollingQuota.nextRelease(sessions, NOW, HOUR, 3 * MIN))
    }

    @Test
    fun `exactly at quota must wait`() {
        val sessions = listOf(UsageSession(ago(10), ago(7))) // exactly 3 min
        val release = RollingQuota.nextRelease(sessions, NOW, HOUR, 3 * MIN)
        // Needs 1 ms of the session to age out: the window start must reach session start + 1 ms,
        // which happens one hour after that point.
        assertEquals(ago(10) + 1 + HOUR, release)
    }

    @Test
    fun `single session over quota releases partway through that session`() {
        // 5 min spent 50 min ago against a 3 min quota: 2 min (+1 ms) has to age out.
        val sessions = listOf(UsageSession(ago(50), ago(45)))
        val release = RollingQuota.nextRelease(sessions, NOW, HOUR, 3 * MIN)
        assertEquals(ago(50) + 2 * MIN + 1 + HOUR, release)
    }

    @Test
    fun `multiple sessions erode oldest first`() {
        // 2 min at -50, 2 min at -30 = 4 min used against a 3 min quota. Only 1 min (+1 ms) has
        // to go, and it comes out of the oldest session.
        val sessions = listOf(
            UsageSession(ago(50), ago(48)),
            UsageSession(ago(30), ago(28)),
        )
        val release = RollingQuota.nextRelease(sessions, NOW, HOUR, 3 * MIN)
        assertEquals(ago(50) + 1 * MIN + 1 + HOUR, release)
    }

    @Test
    fun `erosion skips the gap between sessions`() {
        // 1 min at -50 and 3 min at -20 = 4 min used, quota 3 min, so 1 min (+1 ms) must erode.
        // The oldest session only supplies 1 min, so the release lands just past its far edge --
        // the dead time between the two sessions costs nothing to cross.
        val sessions = listOf(
            UsageSession(ago(50), ago(49)),
            UsageSession(ago(20), ago(17)),
        )
        val release = RollingQuota.nextRelease(sessions, NOW, HOUR, 3 * MIN)
        assertEquals(ago(20) + 1 + HOUR, release)
    }

    @Test
    fun `release is always in the future when blocked`() {
        val sessions = listOf(UsageSession(ago(59), ago(50)))
        val release = RollingQuota.nextRelease(sessions, NOW, HOUR, 3 * MIN)!!
        assert(release > NOW) { "release $release should be after now $NOW" }
    }

    @Test
    fun `usage that has fully aged out at the release time is under quota`() {
        // Property check: at the returned instant, usage really is below quota.
        val sessions = listOf(
            UsageSession(ago(55), ago(52)),
            UsageSession(ago(40), ago(37)),
            UsageSession(ago(5), ago(3)),
        )
        val quota = 3 * MIN
        val release = RollingQuota.nextRelease(sessions, NOW, HOUR, quota)!!
        assert(RollingQuota.usedInWindow(sessions, release, HOUR) < quota) {
            "expected usage below quota at release"
        }
        // ...and one millisecond earlier it was not.
        assert(RollingQuota.usedInWindow(sessions, release - 1, HOUR) >= quota) {
            "release should be the first instant under quota"
        }
    }
}
