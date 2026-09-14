package com.ezhou.rotblock

import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.data.decodePausedTargets
import com.ezhou.rotblock.data.encodePausedTargets
import com.ezhou.rotblock.pause.PauseScope
import com.ezhou.rotblock.policy.Decision
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.PolicyEngine
import com.ezhou.rotblock.policy.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_000_000L
private const val GAME = "app:com.grumpyrhinogames.necromerger"
private const val INSTAGRAM = "web:instagram.com"

class PausedTargetsCodecTest {

    @Test
    fun `round trips a map`() {
        val map = mapOf(GAME to 1_700_000_000_000L, INSTAGRAM to 1_700_000_600_000L)
        assertEquals(map, decodePausedTargets(encodePausedTargets(map)))
    }

    @Test
    fun `a target id containing an equals sign survives`() {
        // Website matchers can carry a query string; splitting on the last `=` keeps the value
        // (always trailing digits) intact and hands the rest back as the id.
        val map = mapOf("web:example.com/search?q=x" to 42L)
        assertEquals(map, decodePausedTargets(encodePausedTargets(map)))
    }

    @Test
    fun `blank and expired entries are dropped on encode`() {
        val encoded = encodePausedTargets(mapOf(GAME to 5L, "" to 9L, INSTAGRAM to 0L))
        assertEquals(mapOf(GAME to 5L), decodePausedTargets(encoded))
    }

    @Test
    fun `malformed lines are ignored rather than throwing`() {
        assertEquals(emptyMap<String, Long>(), decodePausedTargets("garbage\n=5\nfoo=notanumber"))
        assertEquals(emptyMap<String, Long>(), decodePausedTargets(null))
        assertEquals(emptyMap<String, Long>(), decodePausedTargets(""))
    }
}

class ScopedPauseFoldTest {

    private fun settings(global: Long = 0L, targets: Map<String, Long> = emptyMap()) =
        RotblockSettings(pausedUntilMs = global, pausedTargets = targets)

    @Test
    fun `a scoped pause relaxes only its own target`() {
        val s = settings(targets = mapOf(GAME to NOW + 60_000L))
        assertTrue(s.isTargetPaused(GAME, NOW))
        assertFalse("pausing the game must not unblock instagram", s.isTargetPaused(INSTAGRAM, NOW))
    }

    @Test
    fun `a group scope pauses every member and nothing else`() {
        // "This group" writes a deadline per member. A non-member sharing no group stays blocked.
        val members = listOf(GAME, INSTAGRAM)
        val s = settings(targets = members.associateWith { NOW + 60_000L })
        assertTrue(members.all { s.isTargetPaused(it, NOW) })
        assertFalse(s.isTargetPaused("web:reddit.com", NOW))
    }

    @Test
    fun `the global pause covers a target with no scoped entry`() {
        val s = settings(global = NOW + 60_000L)
        assertTrue(s.isTargetPaused(INSTAGRAM, NOW))
        assertEquals(NOW + 60_000L, s.pausedUntilFor(INSTAGRAM))
    }

    @Test
    fun `pausedUntilFor takes the later of global and scoped`() {
        val s = settings(global = NOW + 10_000L, targets = mapOf(GAME to NOW + 60_000L))
        assertEquals(NOW + 60_000L, s.pausedUntilFor(GAME))
        assertEquals(NOW + 10_000L, s.pausedUntilFor(INSTAGRAM))
    }

    @Test
    fun `an elapsed scoped deadline re-enforces on its own`() {
        val s = settings(targets = mapOf(GAME to NOW - 1L))
        assertFalse(s.isTargetPaused(GAME, NOW))
        assertTrue("an expired entry is ignored, not surfaced", s.activeScopedPauses(NOW).isEmpty())
    }

    @Test
    fun `activeScopedPauses excludes expired and excludes the global pause`() {
        val s = settings(
            global = NOW + 5_000L,
            targets = mapOf(GAME to NOW + 60_000L, INSTAGRAM to NOW - 1L),
        )
        assertEquals(mapOf(GAME to NOW + 60_000L), s.activeScopedPauses(NOW))
    }

    // The scoped deadline reaches the engine as the effective pause for that target: the engine
    // itself is unchanged and still takes one deadline, so a hard block is Allowed for the paused
    // target and Blocked for one the pause does not cover.
    private val hardBlock = Policy(
        id = GAME, kind = TargetKind.APP, matcher = "com.grumpyrhinogames.necromerger",
        label = "NecroMerger", quotaSeconds = null,
    )

    @Test
    fun `engine allows a hard block for a target whose scoped pause is live`() {
        val s = settings(targets = mapOf(GAME to NOW + 60_000L))
        val decision = PolicyEngine.evaluate(hardBlock, emptyList(), s.pausedUntilFor(GAME), null, NOW)
        assertTrue(decision is Decision.Allow)
    }

    @Test
    fun `engine still blocks a hard block with no pause covering it`() {
        val s = settings(targets = mapOf(GAME to NOW + 60_000L))
        val decision = PolicyEngine.evaluate(hardBlock, emptyList(), s.pausedUntilFor(INSTAGRAM), null, NOW)
        assertTrue(decision is Decision.Blocked)
    }
}

class PauseScopeTypeTest {

    @Test
    fun `a single-target scope files its target on the audit event`() {
        val scope = PauseScope.Scoped(listOf(GAME), "NecroMerger")
        assertEquals(GAME, scope.auditTargetId)
    }

    @Test
    fun `a multi-target group scope files no single target`() {
        val scope = PauseScope.Scoped(listOf(GAME, INSTAGRAM), "group Brainrot")
        assertNull(scope.auditTargetId)
    }

    @Test
    fun `everything carries no target and its own label`() {
        assertNull(PauseScope.Everything.auditTargetId)
        assertEquals("everything", PauseScope.Everything.label)
    }
}
