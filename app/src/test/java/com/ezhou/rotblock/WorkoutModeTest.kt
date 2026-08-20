package com.ezhou.rotblock

import com.ezhou.rotblock.workout.WorkoutMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val NOW = 1_000 * HOUR

/**
 * [WorkoutMode] is a process-wide singleton, so its state carries between tests unless reset --
 * there is no production reset, since nothing but a test ever needs one.
 */
class WorkoutModeTest {

    @Before
    fun resetState() {
        WorkoutMode.onChanged = null
        // startManually() is the only public path that clears a pending dismissal -- going
        // through it and then ending immediately is a full reset with no test-only backdoor.
        WorkoutMode.startManually()
        WorkoutMode.endManually()
        WorkoutMode.enabled = true
        WorkoutMode.appPackage = "io.strongapp.strong"
        WorkoutMode.capMs = 3 * HOUR
        WorkoutMode.reminderMs = MIN
        WorkoutMode.allowedPackages = emptySet()
    }

    @After
    fun clearCallback() {
        WorkoutMode.onChanged = null
        WorkoutMode.endManually()
    }

    // --- workoutStartFrom ---

    @Test
    fun `workoutStartFrom uses a sane past timestamp`() {
        assertEquals(NOW - 10 * MIN, WorkoutMode.workoutStartFrom(NOW - 10 * MIN, NOW))
    }

    @Test
    fun `workoutStartFrom falls back to seenAtMs for zero`() {
        assertEquals(NOW, WorkoutMode.workoutStartFrom(0L, NOW))
    }

    @Test
    fun `workoutStartFrom falls back to seenAtMs for a negative value`() {
        assertEquals(NOW, WorkoutMode.workoutStartFrom(-1L, NOW))
    }

    @Test
    fun `workoutStartFrom falls back to seenAtMs when more than 60s in the future`() {
        assertEquals(NOW, WorkoutMode.workoutStartFrom(NOW + 60_001L, NOW))
    }

    @Test
    fun `workoutStartFrom accepts a timestamp within 60s of clock slack`() {
        assertEquals(NOW + 60_000L, WorkoutMode.workoutStartFrom(NOW + 60_000L, NOW))
    }

    // --- activeUntilMs ---

    @Test
    fun `activeUntilMs is null before any workout`() {
        assertNull(WorkoutMode.activeUntilMs(NOW))
    }

    @Test
    fun `activeUntilMs is non-null during a live workout`() {
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        assertEquals(NOW + WorkoutMode.capMs, WorkoutMode.activeUntilMs(NOW))
    }

    @Test
    fun `activeUntilMs lapses on its own once the cap passes`() {
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        val cap = WorkoutMode.capMs
        assertTrue(WorkoutMode.activeUntilMs(NOW + cap - 1) != null)
        assertNull("the cap must lapse with nothing called", WorkoutMode.activeUntilMs(NOW + cap))
    }

    @Test
    fun `activeUntilMs is null while disabled even with a live workout seen`() {
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        WorkoutMode.enabled = false
        assertNull(WorkoutMode.activeUntilMs(NOW))
    }

    // --- overrideUntilMs, the per-package answer ---

    @Test
    fun `overrideUntilMs blocks an ordinary package during a workout`() {
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        assertEquals(NOW + WorkoutMode.capMs, WorkoutMode.overrideUntilMs("com.instagram.android", NOW))
    }

    @Test
    fun `overrideUntilMs exempts an allowed package`() {
        WorkoutMode.allowedPackages = setOf("com.spotify.music")
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        assertNull(WorkoutMode.overrideUntilMs("com.spotify.music", NOW))
    }

    @Test
    fun `overrideUntilMs exempts the workout app itself`() {
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        assertNull(WorkoutMode.overrideUntilMs(WorkoutMode.appPackage, NOW))
    }

    /** Not knowing what is in front is when the override matters most. */
    @Test
    fun `overrideUntilMs applies to an unknown package`() {
        WorkoutMode.allowedPackages = setOf("com.spotify.music")
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        assertEquals(NOW + WorkoutMode.capMs, WorkoutMode.overrideUntilMs(null, NOW))
    }

    /** The allowlist subtracts the override; it never adds one. */
    @Test
    fun `overrideUntilMs is null for every package with no workout live`() {
        WorkoutMode.allowedPackages = setOf("com.spotify.music")
        assertNull(WorkoutMode.overrideUntilMs("com.instagram.android", NOW))
        assertNull(WorkoutMode.overrideUntilMs("com.spotify.music", NOW))
    }

    @Test
    fun `overrideUntilMs lapses with the cap for an ordinary package`() {
        val cap = 45 * MIN
        WorkoutMode.capMs = cap
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        assertNull(WorkoutMode.overrideUntilMs("com.instagram.android", NOW + cap))
    }

    // --- onWorkoutSeen idempotence ---

    @Test
    fun `repeated onWorkoutSeen with the same resolved start fires onChanged once`() {
        var changedCount = 0
        WorkoutMode.onChanged = { changedCount++ }
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        assertEquals(1, changedCount)
        assertEquals(NOW, WorkoutMode.startedAtMs)

        WorkoutMode.onWorkoutSeen(NOW, NOW + MIN)
        WorkoutMode.onWorkoutSeen(NOW, NOW + 2 * MIN)
        assertEquals("re-posts of the same workout must not re-fire onChanged", 1, changedCount)
        assertEquals("re-posts must not move the start", NOW, WorkoutMode.startedAtMs)
    }

    // --- clearing a NOTIFICATION source vs a MANUAL one ---

    @Test
    fun `onWorkoutGone clears a NOTIFICATION source`() {
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        WorkoutMode.onWorkoutGone()
        assertNull(WorkoutMode.activeUntilMs(NOW))
        assertEquals(WorkoutMode.Source.NONE, WorkoutMode.source)
    }

    @Test
    fun `onListenerDisconnected clears a NOTIFICATION source`() {
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        WorkoutMode.onListenerDisconnected()
        assertEquals(WorkoutMode.Source.NONE, WorkoutMode.source)
    }

    @Test
    fun `onWorkoutGone leaves a MANUAL source alone`() {
        WorkoutMode.startManually(NOW)
        WorkoutMode.onWorkoutGone()
        assertEquals(WorkoutMode.Source.MANUAL, WorkoutMode.source)
        assertTrue(WorkoutMode.activeUntilMs(NOW) != null)
    }

    @Test
    fun `onListenerDisconnected leaves a MANUAL source alone`() {
        WorkoutMode.startManually(NOW)
        WorkoutMode.onListenerDisconnected()
        assertEquals(WorkoutMode.Source.MANUAL, WorkoutMode.source)
    }

    // --- endManually ---

    @Test
    fun `endManually ends a live notification-sourced workout`() {
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        WorkoutMode.endManually()
        assertNull(WorkoutMode.activeUntilMs(NOW))
        assertEquals(WorkoutMode.Source.NONE, WorkoutMode.source)
    }

    @Test
    fun `a re-post with the same resolved start does not resurrect a dismissed workout`() {
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        WorkoutMode.endManually()
        WorkoutMode.onWorkoutSeen(NOW, NOW + MIN) // Strong re-posts the same notification
        assertEquals(
            "ending workout mode must not be undone by the next re-post",
            WorkoutMode.Source.NONE,
            WorkoutMode.source,
        )
    }

    @Test
    fun `a genuinely new workout starts the mode again after a dismissal`() {
        WorkoutMode.onWorkoutSeen(NOW, NOW)
        WorkoutMode.endManually()
        val laterStart = NOW + HOUR
        WorkoutMode.onWorkoutSeen(laterStart, laterStart)
        assertEquals(WorkoutMode.Source.NOTIFICATION, WorkoutMode.source)
        assertEquals(laterStart, WorkoutMode.startedAtMs)
    }
}
