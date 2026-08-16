package com.ezhou.rotblock

import com.ezhou.rotblock.setup.shouldRestoreEnrolment
import com.ezhou.rotblock.setup.withServiceEnrolled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val US = "com.ezhou.rotblock/com.ezhou.rotblock.service.RotblockAccessibilityService"
private const val TALKBACK = "com.google.android.marvin.talkback/.TalkBackService"

class AccessibilityEnrolmentTest {

    @Test
    fun `an empty list becomes just us`() {
        assertEquals(US, withServiceEnrolled(null, US))
        assertEquals(US, withServiceEnrolled("", US))
    }

    @Test
    fun `we are appended without disturbing what is already there`() {
        assertEquals("$TALKBACK:$US", withServiceEnrolled(TALKBACK, US))
    }

    @Test
    fun `a list already containing us is returned byte for byte`() {
        // Not merely equivalent: rebuilding the string would silently rewrite somebody else's
        // entry, and this is the only code in the app that writes that setting.
        val odd = "$TALKBACK::$US:"
        assertEquals(odd, withServiceEnrolled(odd, US))
    }

    @Test
    fun `case does not make a second copy of us`() {
        val shouty = US.uppercase()
        assertEquals(shouty, withServiceEnrolled(shouty, US))
    }

    @Test
    fun `enrolling twice adds us once`() {
        val once = withServiceEnrolled(TALKBACK, US)
        assertEquals(once, withServiceEnrolled(once, US))
    }

    @Test
    fun `no other service is ever dropped`() {
        val others = "a/b:c/d:e/f"
        val merged = withServiceEnrolled(others, US)
        for (entry in others.split(':')) {
            assertTrue("$entry went missing", merged.split(':').contains(entry))
        }
    }

    @Test
    fun `a force-stop is repaired`() {
        // Heard from, never told to stop, and no longer listed: the phone dropped us.
        assertTrue(
            shouldRestoreEnrolment(
                listedByAndroid = false,
                canWriteSecureSettings = true,
                lastAliveMs = 1_000L,
                cleanlyStoppedAtMs = 0L,
            ),
        )
    }

    @Test
    fun `switching rotblock off in Settings is left alone`() {
        // The unbind ran and stamped when it stopped, so this was a decision, not a drop. Undoing
        // it would be the app fighting its owner.
        assertFalse(
            shouldRestoreEnrolment(
                listedByAndroid = false,
                canWriteSecureSettings = true,
                lastAliveMs = 1_000L,
                cleanlyStoppedAtMs = 900L,
            ),
        )
    }

    @Test
    fun `a phone that has never run enforcement is never switched on for the first time`() {
        // Otherwise a fresh install would enable its own accessibility service before the user was
        // ever shown the disclosure.
        assertFalse(
            shouldRestoreEnrolment(
                listedByAndroid = false,
                canWriteSecureSettings = true,
                lastAliveMs = 0L,
                cleanlyStoppedAtMs = 0L,
            ),
        )
    }

    @Test
    fun `nothing is attempted without the grant, or while we are already listed`() {
        assertFalse(
            shouldRestoreEnrolment(
                listedByAndroid = false,
                canWriteSecureSettings = false,
                lastAliveMs = 1_000L,
                cleanlyStoppedAtMs = 0L,
            ),
        )
        assertFalse(
            shouldRestoreEnrolment(
                listedByAndroid = true,
                canWriteSecureSettings = true,
                lastAliveMs = 1_000L,
                cleanlyStoppedAtMs = 0L,
            ),
        )
    }
}
