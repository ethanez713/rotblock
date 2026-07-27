package com.ezhou.rotblock

import com.ezhou.rotblock.block.escapeLabelFor
import com.ezhou.rotblock.policy.TargetKind
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
}
