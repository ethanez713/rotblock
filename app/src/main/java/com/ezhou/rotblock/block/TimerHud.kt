package com.ezhou.rotblock.block

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot

private const val RING_SIZE_DP = 58
private const val DEFAULT_EDGE_INSET_DP = 12
private const val DEFAULT_TOP_DP = 88

/** Corner radius of the squircle border, as a fraction of the drawn box. */
private const val SQUIRCLE_CORNER_FRACTION = 0.32f

/**
 * Which outline the readout draws. The two are the same widget in every other respect -- same
 * countdown, same drag-and-dismiss, same window plumbing -- and differ only in shape, so a glance
 * tells a pause apart from a spent allowance without reading the number: a [CIRCLE] counts down an
 * allowance being burned, a [SQUIRCLE] counts down a pause before blocking resumes.
 */
enum class HudShape { CIRCLE, SQUIRCLE }

/**
 * How far a drag may be clamped from the top and bottom of the screen. Not the resting position --
 * [DEFAULT_TOP_DP] is that -- just enough clearance that a drag can never park the ring under the
 * status bar or hang it off the bottom edge where a finger could no longer reach it.
 */
private const val VERTICAL_CLAMP_INSET_DP = 24

/** How close the bubble's centre has to get to the target's centre to magnetise and dismiss. */
private const val MAGNETISE_RADIUS_DP = 56

/**
 * The dismiss target's own window is sized to the larger of its two drawn states plus slack, so
 * growing it on magnetise is only ever a redraw -- never a `WindowManager.updateViewLayout` call
 * for a window whose whole point is to sit still while the ring moves around it.
 */
private const val DISMISS_TARGET_WINDOW_DP = 88
private const val DISMISS_TARGET_NORMAL_DP = 64
private const val DISMISS_TARGET_MAGNETIZED_DP = 76
private const val DISMISS_TARGET_BOTTOM_INSET_DP = 48
private const val DISMISS_TARGET_FADE_MS = 120L
private const val SNAP_ANIMATION_MS = 160L

/**
 * A small countdown readout over the app in front -- a [HudShape.CIRCLE] over an app whose
 * allowance is being spent, a [HudShape.SQUIRCLE] over an app that is paused. Draggable anywhere on
 * screen, and dismissible for the rest of the visit with the standard chat-head gesture: drag it
 * onto the X that appears at the bottom of the screen and let go.
 *
 * Three jobs, and the third is why it exists at all:
 *
 * 1. It shows what is left, so the block is not a surprise.
 * 2. It can be dragged out from under whatever you are reading, rather than only ever accepted or
 *    ignored, so it does not have to fight the app underneath for the same corner of the screen.
 * 3. It shows that rotblock is *counting*. Every failure this app has had presents identically --
 *    the app is open and nothing happens -- and from the inside there is no way to tell a target
 *    that is being charged from one that was silently dropped. The Right now panel answers that,
 *    but only if you already suspect something and go and look. A ring that is simply not there
 *    while you sit in a quota'd app answers it without asking.
 *
 * That third job is also why a drag-dismiss cannot become a persisted "off": if it could, the
 * ring's absence would stop meaning "nothing is being counted" and start meaning "or you swiped it
 * away once, weeks ago, and forgot" -- the exact ambiguity this class exists to remove. So a
 * dismiss here only ever reports itself upward (`onDismissed`); it is
 * [com.ezhou.rotblock.service.RotblockAccessibilityService] that decides how long it lasts, by
 * tying it to the accrual session rather than to disk. This class does not know, and must not
 * need to know, when that expires.
 *
 * Used to be `FLAG_NOT_TOUCHABLE`, on the reasoning that it sits over someone else's UI and must
 * never eat a tap meant for the app underneath. That is still true for every tap -- this is a
 * readout, not a button, and a plain tap here still does nothing -- but a *drag* is now the one
 * gesture this window claims for itself. `FLAG_NOT_FOCUSABLE` stays regardless: the app underneath
 * must keep input focus and the IME through all of this, so typing in a text field with the ring
 * floating over it is never interrupted.
 *
 * Geometry -- whether a drag is over the dismiss target, where a release snaps to, how far a drag
 * may be clamped -- is pure functions in `HudGeometry.kt`, not here, so it can be tested off a
 * device. This class only owns the `WindowManager` calls those results feed into.
 *
 * Plain views and no theme, for the same reason as [BlockOverlay]: it is created by the
 * accessibility service, which must never crash, and a `WindowManager` window hosting Compose
 * needs three owners wired up by hand.
 */
class TimerHud(
    private val context: Context,
    /**
     * Reports the resting position after every drop (a dismiss or a snap), for
     * [com.ezhou.rotblock.data.SettingsRepository] to persist.
     */
    private val onMoved: (x: Int, y: Int) -> Unit,
    /** Fired once the bubble is released on the dismiss target, after the window is already gone. */
    private val onDismissed: () -> Unit,
    /** The outline this instance draws. See [HudShape]. */
    private val shape: HudShape = HudShape.CIRCLE,
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var view: RingView? = null
    private var params: WindowManager.LayoutParams? = null

    private var dismissTarget: DismissTargetView? = null
    private var dismissTargetParams: WindowManager.LayoutParams? = null

    private var snapAnimator: ValueAnimator? = null

    private var isDragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0

    /** Whether the bubble is currently close enough to the dismiss target to be dropped on it. */
    private var isOverTarget = false

    /** Epoch ms the allowance runs out. The ring counts to this rather than from a stored number. */
    private var deadlineMs = 0L
    private var quotaMs = 1L

    val isShowing: Boolean get() = view != null

    /**
     * Redraws once a second while visible, and only while visible.
     *
     * The one repeating callback in this app that is not armed for a deadline, and it is allowed
     * because of what it is gated on: an overlay the user opted into, on a screen that is on, over
     * an app they are actively using, with a session open. Screen-off, an app switch, a block, or a
     * pause all take the window down and the callback with it, so this can never run against a
     * sleeping phone -- which is the actual battery rule here.
     */
    private val tick = object : Runnable {
        override fun run() {
            view?.let {
                apply(it)
                handler.postDelayed(this, 1_000)
            }
        }
    }

    /**
     * Shows or updates the ring.
     *
     * [remainingMs] and [quotaMs] come straight from the engine's decision, and are converted to a
     * deadline immediately: the ring then counts against the clock like everything else here, so a
     * dropped frame or a late tick cannot make it disagree with the block that follows it.
     *
     * [storedX] and [storedY] are only ever consulted when this call is the one that creates the
     * window -- never when it is merely updating an already-showing one. The caller (the service)
     * re-invokes this every time it re-evaluates, which can be every second while a target accrues;
     * applying a stored position on every one of those would yank a live drag back to wherever it
     * was dropped last, mid-gesture. A negative coordinate means "never placed" and falls back to
     * the default corner.
     *
     * Returns false if the window could not be shown -- almost always a missing overlay grant,
     * which the block screen needs too and Setup already asks for.
     */
    fun show(remainingMs: Long, quotaMs: Long, opacityPercent: Int, storedX: Int, storedY: Int): Boolean {
        this.deadlineMs = System.currentTimeMillis() + remainingMs
        this.quotaMs = quotaMs.coerceAtLeast(1L)

        val existing = view
        if (existing != null) {
            existing.alpha = opacityPercent.coerceIn(10, 100) / 100f
            apply(existing)
            return true
        }
        if (!Settings.canDrawOverlays(context)) return false

        val ring = RingView(context, this, shape).apply { alpha = opacityPercent.coerceIn(10, 100) / 100f }
        val size = dp(RING_SIZE_DP)
        val metrics = context.resources.displayMetrics
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            // FLAG_NOT_TOUCHABLE is gone: a drag is now a gesture this window claims. See the
            // class doc for why FLAG_NOT_FOCUSABLE stays regardless -- the app underneath must
            // keep input focus and the IME through all of this.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // START, not END: the position is now driven by absolute pixels the drag gesture
            // writes directly, and a gravity that itself pins an edge would fight that on every
            // update.
            gravity = Gravity.TOP or Gravity.START
            val edgeInset = dp(DEFAULT_EDGE_INSET_DP)
            // Clamped, not applied verbatim: a stored position was valid for the display it was
            // placed on, and nothing re-checks it between then and now -- a bubble dropped near the
            // right edge in landscape is past storedX in portrait once the phone rotates. An absent
            // ring is exactly the ambiguity this whole class exists to prevent, so a ring merely
            // off screen is that same failure arriving through a side door: clamp it back on rather
            // than let a rotation strand it somewhere no gesture can reach.
            x = if (storedX >= 0) {
                clampX(storedX, size, metrics.widthPixels, edgeInset)
            } else {
                metrics.widthPixels - size - edgeInset
            }
            // Below the status bar, where neither a notch nor a browser toolbar is.
            y = if (storedY >= 0) {
                clampY(storedY, size, metrics.heightPixels, dp(VERTICAL_CLAMP_INSET_DP), dp(VERTICAL_CLAMP_INSET_DP))
            } else {
                dp(DEFAULT_TOP_DP)
            }
        }

        return try {
            windowManager.addView(ring, layoutParams)
            view = ring
            params = layoutParams
            apply(ring)
            handler.postDelayed(tick, 1_000)
            true
        } catch (t: Throwable) {
            view = null
            params = null
            false
        }
    }

    fun hide() {
        val ring = view ?: return
        view = null
        params = null
        handler.removeCallbacks(tick)
        // A hide can land mid-drag -- the target ran out of quota under the user's finger, say --
        // so everything a drag might have left behind has to come down with the ring itself.
        snapAnimator?.cancel()
        snapAnimator = null
        isDragging = false
        hideDismissTarget()
        runCatching { windowManager.removeView(ring) }
    }

    /**
     * All touch handling for the drag-and-dismiss gesture. Called from [RingView.onTouchEvent],
     * which wraps this in its own `try`/`catch` -- this class is instantiated and driven by the
     * accessibility service, whose own [com.ezhou.rotblock.service.RotblockAccessibilityService.guard]
     * does not reach view callbacks, so an exception escaping a touch event would crash the service
     * outright. Per CLAUDE.md rule 1, a dead accessibility service is a silently unblocked phone --
     * worse than this ring simply failing to move for one gesture.
     */
    fun onTouch(event: MotionEvent) {
        val ring = view ?: return
        val p = params ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A fresh grab while the snap-back from the last drag is still animating would
                // otherwise leave two writers on the same `params.x` -- the animator's update
                // listener and this gesture's ACTION_MOVE -- fighting every frame.
                snapAnimator?.cancel()
                snapAnimator = null
                downRawX = event.rawX
                downRawY = event.rawY
                downWindowX = p.x
                downWindowY = p.y
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging) {
                    // Below slop this is a steady finger, not a drag. The ring is a readout, not a
                    // button -- nothing happens here until the gesture proves itself a drag.
                    if (hypot(dx, dy) < touchSlop) return
                    isDragging = true
                    showDismissTarget()
                }

                val metrics = context.resources.displayMetrics
                val intendedX = downWindowX + dx.toInt()
                val intendedY = clampY(
                    downWindowY + dy.toInt(),
                    ring.height,
                    metrics.heightPixels,
                    dp(VERTICAL_CLAMP_INSET_DP),
                    dp(VERTICAL_CLAMP_INSET_DP),
                )
                val bubbleCenterX = intendedX + ring.width / 2f
                val bubbleCenterY = intendedY + ring.height / 2f
                val (targetCenterX, targetCenterY) = dismissTargetCenter(metrics)
                val over = isOverDismissTarget(
                    bubbleCenterX,
                    bubbleCenterY,
                    targetCenterX,
                    targetCenterY,
                    dp(MAGNETISE_RADIUS_DP).toFloat(),
                )
                if (over != isOverTarget) {
                    isOverTarget = over
                    dismissTarget?.let { it.magnetized = over; it.invalidate() }
                }
                if (over) {
                    // Snaps onto the target's own centre while in range -- the same tactile
                    // confirmation every chat-head style dismiss gives, so letting go here needs
                    // no separate "are you sure".
                    p.x = (targetCenterX - ring.width / 2f).toInt()
                    p.y = (targetCenterY - ring.height / 2f).toInt()
                } else {
                    p.x = intendedX
                    p.y = intendedY
                }
                runCatching { windowManager.updateViewLayout(ring, p) }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return // A tap: no dismiss, no snap, nothing moved.
                isDragging = false
                val dismiss = isOverTarget
                hideDismissTarget()
                if (dismiss) {
                    // No onMoved here. While isOverTarget is true, ACTION_MOVE has been overwriting
                    // p.x/p.y with the *dismiss target's* centre, not anywhere the user dragged the
                    // ring to -- reporting that as the resting position would park the next session's
                    // ring on top of the dismiss target itself. Dismissing is not moving: the last
                    // position worth remembering is the last one a snap actually settled on.
                    hide()
                    onDismissed()
                } else {
                    snapToEdge(ring, p)
                }
            }
        }
    }

    /** Animates the release position to the nearer edge; cancelled by [hide] if it is still running. */
    private fun snapToEdge(ring: RingView, p: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val targetX = snapEdgeX(p.x, ring.width, metrics.widthPixels, dp(DEFAULT_EDGE_INSET_DP))
        snapAnimator?.cancel()
        val animator = ValueAnimator.ofInt(p.x, targetX).apply {
            duration = SNAP_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = view ?: return@addUpdateListener
                val lp = params ?: return@addUpdateListener
                lp.x = anim.animatedValue as Int
                runCatching { windowManager.updateViewLayout(v, lp) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapAnimator = null
                    params?.let { onMoved(it.x, it.y) }
                }
            })
        }
        snapAnimator = animator
        animator.start()
    }

    /** The X-in-a-circle at the bottom of the screen. Created only while a drag is in progress. */
    private fun showDismissTarget() {
        if (dismissTarget != null) return
        val size = dp(DISMISS_TARGET_WINDOW_DP)
        val target = DismissTargetView(context).apply { alpha = 0f }
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            // Purely visual: it must never itself eat a touch, or a drag ending anywhere near the
            // bottom of the screen would stop reaching the ring's own window underneath it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(DISMISS_TARGET_BOTTOM_INSET_DP)
        }
        try {
            windowManager.addView(target, layoutParams)
            dismissTarget = target
            dismissTargetParams = layoutParams
            target.animate().alpha(1f).setDuration(DISMISS_TARGET_FADE_MS).start()
        } catch (t: Throwable) {
            dismissTarget = null
            dismissTargetParams = null
        }
    }

    private fun hideDismissTarget() {
        val target = dismissTarget ?: return
        dismissTarget = null
        dismissTargetParams = null
        isOverTarget = false
        runCatching { windowManager.removeView(target) }
    }

    /** Screen-space centre of the dismiss target, derived from the same gravity it was placed with. */
    private fun dismissTargetCenter(metrics: DisplayMetrics): Pair<Float, Float> {
        val size = dp(DISMISS_TARGET_WINDOW_DP)
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels - dp(DISMISS_TARGET_BOTTOM_INSET_DP) - size / 2f
        return centerX to centerY
    }

    private fun apply(ring: RingView) {
        val remaining = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
        ring.fraction = (remaining.toFloat() / quotaMs.toFloat()).coerceIn(0f, 1f)
        ring.label = shortDuration(remaining)
        ring.invalidate()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/**
 * The denominator the pause squircle drains its border against.
 *
 * When the pause's grant time [sinceMs] is known, that is the whole span it counts down over, so
 * the border reads full at the start and empty at the end. When it is not -- a pause older than
 * anchor tracking, or one whose anchor write was lost -- there is no honest total, so it falls back
 * to the time remaining: the fraction is then 1 and the border sits full rather than draining from
 * a nonsense number. Never zero, so [TimerHud.show] can divide by it.
 */
fun pauseArcTotalMs(untilMs: Long, sinceMs: Long, nowMs: Long): Long =
    if (sinceMs in 1 until untilMs) untilMs - sinceMs
    else (untilMs - nowMs).coerceAtLeast(1L)

/**
 * "9:59" under ten minutes, "24m" above it.
 *
 * Not [formatDuration]: that is written for a sentence ("more time in 4m 12s") and is too wide for
 * a 58dp circle. Seconds are shown where seconds are what you are watching, and dropped where they
 * would just be noise flickering in the corner of an app.
 */
private fun shortDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return if (totalSeconds < 600) {
        "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    } else {
        "${totalSeconds / 60}m"
    }
}

/** The readout itself: a track, an arc for what is left, and the number in the middle. */
private class RingView(
    context: Context,
    private val hud: TimerHud,
    private val shape: HudShape,
) : View(context) {

    /** Share of the allowance still unspent, 0..1. */
    var fraction: Float = 1f

    var label: String = ""

    private val density = context.resources.displayMetrics.density

    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6101014")
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        color = Color.parseColor("#33FFFFFF")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
    }

    private val bounds = RectF()
    private val corner = RectF()
    private val trackPath = Path()
    private val arcSegment = Path()
    private val pathMeasure = PathMeasure()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = 4f * density + track.strokeWidth / 2f
        bounds.set(inset, inset, w - inset, h - inset)

        // Warmer as it empties: the colour is doing the same job as the number, for the glance
        // that does not stop to read it. On a squircle "empty" means the pause is nearly over and
        // blocking is about to resume, which is the same heads-up.
        arc.color = when {
            fraction <= 0.1f -> Color.parseColor("#F08A7A")
            fraction <= 0.25f -> Color.parseColor("#F0BE6E")
            else -> Color.parseColor("#7FD1A6")
        }

        when (shape) {
            HudShape.CIRCLE -> {
                canvas.drawCircle(w / 2f, h / 2f, w / 2f - 2f * density, backdrop)
                canvas.drawArc(bounds, 0f, 360f, false, track)
                // From twelve o'clock, anticlockwise, so the ring empties the way a clock hand would.
                canvas.drawArc(bounds, -90f, -360f * fraction, false, arc)
            }
            HudShape.SQUIRCLE -> {
                val radius = minOf(bounds.width(), bounds.height()) * SQUIRCLE_CORNER_FRACTION
                val backInset = 2f * density
                canvas.drawRoundRect(
                    backInset, backInset, w - backInset, h - backInset, radius, radius, backdrop,
                )
                buildTopCenterRoundRect(trackPath, bounds, radius)
                canvas.drawPath(trackPath, track)
                // Same top-centre start and anticlockwise drain as the circle, traced along the
                // rounded-rect perimeter, so the two readouts empty the same direction.
                pathMeasure.setPath(trackPath, false)
                val drawLen = pathMeasure.length * fraction
                if (drawLen > 0f) {
                    arcSegment.reset()
                    pathMeasure.getSegment(0f, drawLen, arcSegment, true)
                    canvas.drawPath(arcSegment, arc)
                }
            }
        }

        val baseline = h / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, w / 2f, baseline, text)
    }

    /**
     * A rounded-rect outline as a single contour beginning at the top-centre and running
     * anticlockwise back to it, so [PathMeasure.getSegment] from zero draws the border the same way
     * [drawArc] does for the circle. Left open on purpose -- the closing edge is the final `lineTo`,
     * so the measured length is the whole perimeter with no extra join segment.
     */
    private fun buildTopCenterRoundRect(path: Path, b: RectF, r: Float) {
        path.reset()
        val cx = b.centerX()
        path.moveTo(cx, b.top)
        path.lineTo(b.left + r, b.top)
        corner.set(b.left, b.top, b.left + 2 * r, b.top + 2 * r)
        path.arcTo(corner, 270f, -90f)
        path.lineTo(b.left, b.bottom - r)
        corner.set(b.left, b.bottom - 2 * r, b.left + 2 * r, b.bottom)
        path.arcTo(corner, 180f, -90f)
        path.lineTo(b.right - r, b.bottom)
        corner.set(b.right - 2 * r, b.bottom - 2 * r, b.right, b.bottom)
        path.arcTo(corner, 90f, -90f)
        path.lineTo(b.right, b.top + r)
        corner.set(b.right - 2 * r, b.top, b.right, b.top + 2 * r)
        path.arcTo(corner, 0f, -90f)
        path.lineTo(cx, b.top)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Consumed unconditionally: FLAG_NOT_TOUCHABLE is gone, so a gesture that fell through
        // here would land on the app underneath, which never asked for it. And this is a view
        // callback, not a service entry point -- RotblockAccessibilityService.guard does not
        // reach here, so an uncaught exception would crash the service outright. See
        // TimerHud.onTouch's doc for why that is the one rule that matters more than this gesture
        // working correctly.
        try {
            hud.onTouch(event)
        } catch (t: Throwable) {
            // Swallow and drop the gesture. The ring is left wherever it currently sits, which is
            // always a valid, on-screen position -- never a half-updated one, since every write to
            // the window's params is followed immediately by the `updateViewLayout` call that
            // would have thrown.
        }
        return true
    }
}

/**
 * The X-in-a-circle dismiss target that appears at the bottom of the screen while the ring is
 * being dragged. Purely visual -- its window is `FLAG_NOT_TOUCHABLE` -- so unlike [RingView] it
 * never needs to handle a touch of its own.
 */
private class DismissTargetView(context: Context) : View(context) {

    /** True once the dragged ring is close enough that releasing here would dismiss it. */
    var magnetized: Boolean = false

    private val density = context.resources.displayMetrics.density

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3101014")
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // Grown on magnetise rather than resized as a window -- see DISMISS_TARGET_WINDOW_DP --
        // so this is the only feedback the gesture needs to explain itself: the same "it's about
        // to catch" cue every chat-head style dismiss target gives.
        val diameterDp = if (magnetized) DISMISS_TARGET_MAGNETIZED_DP else DISMISS_TARGET_NORMAL_DP
        val radius = diameterDp * density / 2f
        canvas.drawCircle(cx, cy, radius, circle)
        val arm = radius * 0.38f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross)
        canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, cross)
    }
}
