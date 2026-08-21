package com.ezhou.rotblock

import com.ezhou.rotblock.block.escapeLabelFor
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.service.EscapeVerdict
import com.ezhou.rotblock.service.verifyEscapeOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * No block may strand you.
 *
 * A block that covers more of the screen than it actually forbids has to offer a way back to the
 * part you are still allowed to use. Without one the browser was a dead end: quota runs out on
 * reddit, the block screen covers the address bar, closing and reopening restores the same tab and
 * blocks again, and the only remaining move is disabling the policy -- the exact behaviour this app
 * exists to prevent.
 */
class EscapeTest {

    @Test
    fun `a website block offers a way off the site`() {
        assertNotNull(escapeLabelFor(TargetKind.WEBSITE))
    }

    @Test
    fun `a feed block offers a way out of the feed`() {
        assertNotNull(escapeLabelFor(TargetKind.FEED))
    }

    @Test
    fun `an app block needs no escape because the app is the target`() {
        // Closing it is already a complete answer; an extra button would just be noise.
        assertNull(escapeLabelFor(TargetKind.APP))
    }

    @Test
    fun `every target kind has been considered`() {
        // escapeLabelFor is an exhaustive `when`, so this fails to compile rather than fails at
        // runtime if a kind is added -- but the assertion keeps the intent visible here too.
        TargetKind.entries.forEach { escapeLabelFor(it) }
    }

    /**
     * verifyEscapeOutcome must not collapse "nobody has looked yet" into "the browser refused" --
     * the bug a real device capture caught: four escapes in 24 seconds, every verify window
     * bouncing off a transient systemui window, five re-blocks on a browser that had escaped every
     * single time.
     */

    @Test
    fun `the target moving is escaped, whatever the read timestamps say`() {
        assertEquals(
            EscapeVerdict.ESCAPED,
            verifyEscapeOutcome(
                escapedTargetId = "reddit",
                currentTargetId = "other",
                escapedAtMs = 10_000L,
                lastWindowReadAtMs = 0L,
                attempt = 1,
                maxAttempts = 3,
            ),
        )
        assertEquals(
            EscapeVerdict.ESCAPED,
            verifyEscapeOutcome(
                escapedTargetId = "reddit",
                currentTargetId = null,
                escapedAtMs = 10_000L,
                lastWindowReadAtMs = 20_000L,
                attempt = 3,
                maxAttempts = 3,
            ),
        )
    }

    @Test
    fun `a read landing after the escape with the target unchanged is refused on the first attempt`() {
        assertEquals(
            EscapeVerdict.REFUSED,
            verifyEscapeOutcome(
                escapedTargetId = "reddit",
                currentTargetId = "reddit",
                escapedAtMs = 10_000L,
                lastWindowReadAtMs = 10_500L,
                attempt = 1,
                maxAttempts = 3,
            ),
        )
    }

    @Test
    fun `a read that predates the escape is not evidence`() {
        assertEquals(
            EscapeVerdict.UNCONFIRMED,
            verifyEscapeOutcome(
                escapedTargetId = "reddit",
                currentTargetId = "reddit",
                escapedAtMs = 10_000L,
                lastWindowReadAtMs = 9_000L,
                attempt = 1,
                maxAttempts = 3,
            ),
        )
    }

    @Test
    fun `no read at all is unconfirmed until the bound is hit, then refused`() {
        assertEquals(
            EscapeVerdict.UNCONFIRMED,
            verifyEscapeOutcome(
                escapedTargetId = "reddit",
                currentTargetId = "reddit",
                escapedAtMs = 10_000L,
                lastWindowReadAtMs = 0L,
                attempt = 1,
                maxAttempts = 3,
            ),
        )
        assertEquals(
            EscapeVerdict.UNCONFIRMED,
            verifyEscapeOutcome(
                escapedTargetId = "reddit",
                currentTargetId = "reddit",
                escapedAtMs = 10_000L,
                lastWindowReadAtMs = 0L,
                attempt = 2,
                maxAttempts = 3,
            ),
        )
        assertEquals(
            EscapeVerdict.REFUSED,
            verifyEscapeOutcome(
                escapedTargetId = "reddit",
                currentTargetId = "reddit",
                escapedAtMs = 10_000L,
                lastWindowReadAtMs = 0L,
                attempt = 3,
                maxAttempts = 3,
            ),
        )
    }

    @Test
    fun `a read landing exactly at the escape timestamp is not evidence about what happened after`() {
        assertEquals(
            EscapeVerdict.UNCONFIRMED,
            verifyEscapeOutcome(
                escapedTargetId = "reddit",
                currentTargetId = "reddit",
                escapedAtMs = 10_000L,
                lastWindowReadAtMs = 10_000L,
                attempt = 1,
                maxAttempts = 3,
            ),
        )
    }
}
