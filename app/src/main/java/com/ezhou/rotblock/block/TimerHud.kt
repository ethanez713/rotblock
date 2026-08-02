package com.ezhou.rotblock.block

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A small countdown ring over the app you are spending an allowance in.
 *
 * Two jobs, and the second one is why it exists at all:
 *
 * 1. It shows what is left, so the block is not a surprise.
 * 2. It shows that rotblock is *counting*. Every failure this app has had presents identically --
 *    the app is open and nothing happens -- and from the inside there is no way to tell a target
 *    that is being charged from one that was silently dropped. The Right now panel answers that,
 *    but only if you already suspect something and go and look. A ring that is simply not there
 *    while you sit in a quota'd app answers it without asking.
 *
 * So its absence is the signal, which is why the opacity floor is 10% and not 0: an invisible ring
 * reports nothing.
 *
 * Shown only while a target is actually accruing time -- not while blocked, not while paused, not
 * outside a rule's hours, not while one of our own screens is up. It is a readout of the ledger,
 * so anything that stops the ledger takes it down.
 *
 * Plain views and no theme, for the same reason as [BlockOverlay]: it is created by the
 * accessibility service, which must never crash, and a `WindowManager` window hosting Compose
 * needs three owners wired up by hand.
 */
class TimerHud(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: RingView? = null

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
     * Returns false if the window could not be shown -- almost always a missing overlay grant,
     * which the block screen needs too and Setup already asks for.
     */
    fun show(remainingMs: Long, quotaMs: Long, opacityPercent: Int): Boolean {
        this.deadlineMs = System.currentTimeMillis() + remainingMs
        this.quotaMs = quotaMs.coerceAtLeast(1L)

        val existing = view
        if (existing != null) {
            existing.alpha = opacityPercent.coerceIn(10, 100) / 100f
            apply(existing)
            return true
        }
        if (!Settings.canDrawOverlays(context)) return false

        val ring = RingView(context).apply { alpha = opacityPercent.coerceIn(10, 100) / 100f }
        val size = dp(58)
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            // Untouchable on purpose: it sits over someone else's UI and must never eat a tap.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            // Below the status bar, where neither a notch nor a browser toolbar is.
            y = dp(88)
        }

        return try {
            windowManager.addView(ring, params)
            view = ring
            apply(ring)
            handler.postDelayed(tick, 1_000)
            true
        } catch (t: Throwable) {
            view = null
            false
        }
    }

    fun hide() {
        val ring = view ?: return
        view = null
        handler.removeCallbacks(tick)
        runCatching { windowManager.removeView(ring) }
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

/** The ring itself: a track, an arc for what is left, and the number in the middle. */
private class RingView(context: Context) : View(context) {

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

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = 4f * density + track.strokeWidth / 2f
        bounds.set(inset, inset, w - inset, h - inset)

        canvas.drawCircle(w / 2f, h / 2f, w / 2f - 2f * density, backdrop)
        canvas.drawArc(bounds, 0f, 360f, false, track)

        // Warmer as it empties: the colour is doing the same job as the number, for the glance
        // that does not stop to read it.
        arc.color = when {
            fraction <= 0.1f -> Color.parseColor("#F08A7A")
            fraction <= 0.25f -> Color.parseColor("#F0BE6E")
            else -> Color.parseColor("#7FD1A6")
        }
        // From twelve o'clock, anticlockwise, so the ring empties the way a clock hand would.
        canvas.drawArc(bounds, -90f, -360f * fraction, false, arc)

        val baseline = h / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, w / 2f, baseline, text)
    }
}
