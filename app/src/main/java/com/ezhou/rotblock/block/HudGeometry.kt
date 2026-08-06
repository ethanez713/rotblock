package com.ezhou.rotblock.block

/**
 * The three geometry decisions behind the draggable countdown ring, pulled out as pure top-level
 * functions with no Android types so they can be unit-tested on the JVM like the rest of this
 * app's logic. [TimerHud] calls these and holds no arithmetic of its own -- only the
 * `WindowManager` calls their results feed into, which is exactly the part that cannot run off a
 * device and so is not worth trying to test.
 */

/**
 * True when the bubble's centre is close enough to the dismiss target's centre to magnetise to it
 * -- and, on release, to be dismissed by it. One radius test decides both, so "it looked stuck to
 * the X" and "letting go here removed it" can never disagree with each other.
 */
fun isOverDismissTarget(
    bubbleCenterX: Float,
    bubbleCenterY: Float,
    targetCenterX: Float,
    targetCenterY: Float,
    radiusPx: Float,
): Boolean {
    val dx = bubbleCenterX - targetCenterX
    val dy = bubbleCenterY - targetCenterY
    return dx * dx + dy * dy <= radiusPx * radiusPx
}

/**
 * The nearer horizontal resting place for a bubble of [sizePx], released at [currentX] on a
 * screen [screenWidthPx] wide, inset [insetPx] from whichever edge it lands on.
 *
 * Compared against the screen's own midline rather than against the distance to each edge
 * separately -- the same test, but one that cannot give a different answer depending on which
 * edge it happens to check first.
 */
fun snapEdgeX(currentX: Int, sizePx: Int, screenWidthPx: Int, insetPx: Int): Int {
    val bubbleCenterX = currentX + sizePx / 2
    return if (bubbleCenterX >= screenWidthPx / 2) {
        screenWidthPx - sizePx - insetPx
    } else {
        insetPx
    }
}

/**
 * Keeps a bubble of [sizePx] fully on screen vertically, so a drag can never park it under the
 * status bar or hang it off the bottom edge where it would be unreachable to drag back.
 */
fun clampY(y: Int, sizePx: Int, screenHeightPx: Int, topInsetPx: Int, bottomInsetPx: Int): Int {
    val maxY = (screenHeightPx - sizePx - bottomInsetPx).coerceAtLeast(topInsetPx)
    return y.coerceIn(topInsetPx, maxY)
}

/**
 * Keeps a bubble of [sizePx] fully on screen horizontally, both insets equal because either edge
 * is a valid rest -- unlike [snapEdgeX], this never *chooses* an edge, it only pulls a coordinate
 * back onto the screen if something else already put it out of bounds.
 *
 * That distinction is the reason this exists as its own function rather than reusing [snapEdgeX]:
 * a rotation can leave a legitimately-placed x past the new, narrower [screenWidthPx], and the fix
 * for that is pulling it back to the nearest inset it still fits inside -- not teleporting a ring
 * the user placed in the middle of the screen out to an edge, which is what [snapEdgeX] would do
 * to every stored position on every rotation.
 */
fun clampX(x: Int, sizePx: Int, screenWidthPx: Int, insetPx: Int): Int {
    val maxX = (screenWidthPx - sizePx - insetPx).coerceAtLeast(insetPx)
    return x.coerceIn(insetPx, maxX)
}
