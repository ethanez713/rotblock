package com.ezhou.rotblock

import com.ezhou.rotblock.block.clampX
import com.ezhou.rotblock.block.clampY
import com.ezhou.rotblock.block.isOverDismissTarget
import com.ezhou.rotblock.block.snapEdgeX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three geometry decisions behind the draggable countdown ring -- see
 * [com.ezhou.rotblock.block.TimerHud].
 *
 * Pure functions, tested off a device the same way the rest of this app's logic is: no
 * `WindowManager`, no gesture dispatch, just the arithmetic that decides whether a drop dismisses,
 * where a release snaps to, and how far a drag may be pushed off screen.
 */
class HudGeometryTest {

    // region isOverDismissTarget

    @Test
    fun `a bubble centred on the target is over it`() {
        assertTrue(isOverDismissTarget(500f, 2000f, 500f, 2000f, radiusPx = 100f))
    }

    @Test
    fun `a bubble just inside the radius is over it`() {
        assertTrue(isOverDismissTarget(599f, 2000f, 500f, 2000f, radiusPx = 100f))
    }

    @Test
    fun `a bubble just outside the radius is not over it`() {
        assertFalse(isOverDismissTarget(601f, 2000f, 500f, 2000f, radiusPx = 100f))
    }

    @Test
    fun `distance is measured on both axes, not the larger of the two`() {
        // 80 right and 80 up is ~113px away in a straight line -- outside a 100px radius, even
        // though neither offset alone would be. A check that took the larger axis instead of the
        // true distance would wrongly call this "over" and dismiss a bubble nowhere near the X.
        assertFalse(isOverDismissTarget(580f, 1920f, 500f, 2000f, radiusPx = 100f))
    }

    // endregion

    // region snapEdgeX

    @Test
    fun `just past the midpoint snaps right`() {
        // Bubble centre at 501 on a 1000px screen, one past the 500 midline.
        assertEquals(890, snapEdgeX(currentX = 451, sizePx = 100, screenWidthPx = 1000, insetPx = 10))
    }

    @Test
    fun `just short of the midpoint snaps left`() {
        // Bubble centre at 499, one short of the midline.
        assertEquals(10, snapEdgeX(currentX = 449, sizePx = 100, screenWidthPx = 1000, insetPx = 10))
    }

    @Test
    fun `already resting at an edge stays there`() {
        assertEquals(10, snapEdgeX(currentX = 10, sizePx = 100, screenWidthPx = 1000, insetPx = 10))
        assertEquals(890, snapEdgeX(currentX = 890, sizePx = 100, screenWidthPx = 1000, insetPx = 10))
    }

    // endregion

    // region clampX

    @Test
    fun `a stored x past the right edge lands at the right inset`() {
        // A bubble placed near the right edge in landscape, then read back on a narrower portrait
        // screen -- storedX is now past where it fits.
        assertEquals(890, clampX(x = 1400, sizePx = 100, screenWidthPx = 1000, insetPx = 10))
    }

    @Test
    fun `a negative stored x lands at the left inset`() {
        assertEquals(10, clampX(x = -300, sizePx = 100, screenWidthPx = 1000, insetPx = 10))
    }

    @Test
    fun `an on-screen stored x is untouched`() {
        // Unlike snapEdgeX, being merely off-centre is not a reason to move it -- only being off
        // screen is.
        assertEquals(500, clampX(x = 500, sizePx = 100, screenWidthPx = 1000, insetPx = 10))
    }

    @Test
    fun `insets wider than the screen never invert the x clamp range either`() {
        assertEquals(50, clampX(x = 40, sizePx = 100, screenWidthPx = 100, insetPx = 50))
        assertEquals(50, clampX(x = 9000, sizePx = 100, screenWidthPx = 100, insetPx = 50))
    }

    // endregion

    // region clampY

    @Test
    fun `dragged above the top inset clamps down to it`() {
        assertEquals(50, clampY(y = -500, sizePx = 100, screenHeightPx = 2000, topInsetPx = 50, bottomInsetPx = 80))
    }

    @Test
    fun `dragged below the bottom inset clamps up to it`() {
        assertEquals(1820, clampY(y = 5000, sizePx = 100, screenHeightPx = 2000, topInsetPx = 50, bottomInsetPx = 80))
    }

    @Test
    fun `resting comfortably on screen is left alone`() {
        assertEquals(500, clampY(y = 500, sizePx = 100, screenHeightPx = 2000, topInsetPx = 50, bottomInsetPx = 80))
    }

    @Test
    fun `insets wider than the screen never invert the clamp range`() {
        // A screen too short for the bubble plus both insets would otherwise ask coerceIn for a
        // maximum below its minimum, which throws. Degenerate input has to resolve to a single
        // valid position, not a crash.
        assertEquals(50, clampY(y = 40, sizePx = 100, screenHeightPx = 100, topInsetPx = 50, bottomInsetPx = 50))
        assertEquals(50, clampY(y = 9000, sizePx = 100, screenHeightPx = 100, topInsetPx = 50, bottomInsetPx = 50))
    }

    // endregion
}
