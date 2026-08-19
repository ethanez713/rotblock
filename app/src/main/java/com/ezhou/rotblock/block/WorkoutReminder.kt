package com.ezhou.rotblock.block

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The full-screen "you drifted" nudge that appears while workout mode is live and the phone has
 * spent too long on something that is not the workout app.
 *
 * A separate window from [BlockOverlay] on purpose, not a mode flag on it. Both would share one
 * root view, so putting a reminder up would tear a live block screen down and put a reminder in
 * its place -- and a nudge must never be able to displace enforcement. Built the same way as
 * [BlockOverlay] for the same reason: plain views, no Compose, created from the accessibility
 * service which must never crash.
 */
class WorkoutReminder(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null

    val isShowing: Boolean get() = root != null

    /**
     * Returns false when the reminder could not be shown -- almost always a missing "display over
     * other apps" grant. Unlike a real block, there is no fallback kick-out: a nudge that fails to
     * appear just means the interval elapses again unspent, since [show]'s caller only advances
     * the next-due deadline on a true result.
     */
    fun show(onResume: () -> Unit, onSnooze: () -> Unit): Boolean {
        if (root != null) return true
        if (!canDrawOverlays()) return false

        val view = buildView(onResume, onSnooze)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // Deliberately not focusable, same as BlockOverlay: a focusable overlay would steal
            // focus from whatever app is underneath and break typing in it mid-sentence. Back
            // therefore reaches the app rather than this window, which is why both ways out below
            // are on-screen taps rather than something relying on a back press.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        return try {
            windowManager.addView(view, params)
            root = view
            true
        } catch (t: Throwable) {
            root = null
            false
        }
    }

    fun hide() {
        val view = root ?: return
        root = null
        runCatching { windowManager.removeView(view) }
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

    private fun buildView(onResume: () -> Unit, onSnooze: () -> Unit): LinearLayout {
        val pad = dp(28)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F2101014"))
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = false

            addView(TextView(context).apply {
                text = "rotblock"
                setTextColor(Color.parseColor("#6E7681"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                letterSpacing = 0.18f
                gravity = Gravity.CENTER
            })

            addView(spacer(dp(20)))

            addView(TextView(context).apply {
                text = "Still mid-workout"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
            })

            addView(spacer(dp(10)))

            addView(TextView(context).apply {
                text = "You have been on your phone for a few minutes."
                setTextColor(Color.parseColor("#B6BCC4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
            })

            addView(spacer(dp(36)))

            addView(primaryButton("Back to your workout") { onResume() })

            // Not a button, same reasoning as BlockOverlay: the way out of a nudge should never be
            // the easiest-looking thing on the screen, or the nudge stops nudging.
            addView(spacer(dp(14)))
            addView(textLink("I'm doing something else", "#6E7681") { onSnooze() })
        }
    }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#0B1F17"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(15), dp(20), dp(15))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#7FD1A6"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun textLink(label: String, color: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun spacer(height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, height)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
