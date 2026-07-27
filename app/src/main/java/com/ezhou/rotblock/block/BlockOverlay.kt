package com.ezhou.rotblock.block

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ezhou.rotblock.policy.BlockReason

/**
 * The full-screen "no" that appears over a blocked app.
 *
 * Built from plain views on purpose. It is created by the accessibility service, which must never
 * crash, and hosting Compose in a `WindowManager` window means wiring up lifecycle, saved-state
 * and view-model owners by hand -- three more things that can throw on a code path whose whole
 * job is to be reliable. The main app UI is Compose; this one screen is not.
 */
class BlockOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var countdownView: TextView? = null
    private var liftsAtMs: Long? = null
    private var onPauseRequested: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    private val tick = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, 1_000)
        }
    }

    /**
     * Returns false when the overlay could not be shown -- almost always a missing
     * "display over other apps" grant. The caller must then fall back to kicking the user out of
     * the app, because a block that silently does nothing is the one failure mode this app
     * cannot have.
     */
    fun show(
        label: String,
        reason: BlockReason,
        liftsAtMs: Long?,
        onPauseRequested: () -> Unit,
    ): Boolean {
        this.liftsAtMs = liftsAtMs
        this.onPauseRequested = onPauseRequested
        if (root != null) {
            updateTexts(label, reason)
            return true
        }
        if (!canDrawOverlays()) return false

        val view = buildView(label, reason)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // Not FLAG_NOT_TOUCHABLE: the point is to swallow taps aimed at the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        return try {
            windowManager.addView(view, params)
            root = view
            handler.post(tick)
            true
        } catch (t: Throwable) {
            root = null
            countdownView = null
            false
        }
    }

    fun hide() {
        val view = root ?: return
        root = null
        countdownView = null
        liftsAtMs = null
        onPauseRequested = null
        handler.removeCallbacks(tick)
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

    private var titleView: TextView? = null
    private var reasonView: TextView? = null

    @SuppressLint("SetTextI18n")
    private fun buildView(label: String, reason: BlockReason): LinearLayout {
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

            titleView = TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            addView(titleView)

            addView(spacer(dp(10)))

            reasonView = TextView(context).apply {
                text = reasonText(reason)
                setTextColor(Color.parseColor("#B6BCC4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
            }
            addView(reasonView)

            addView(spacer(dp(24)))

            countdownView = TextView(context).apply {
                setTextColor(Color.parseColor("#7FD1A6"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
            }
            addView(countdownView)

            addView(spacer(dp(36)))

            addView(Button(context).apply {
                text = "Close"
                setOnClickListener { goHome() }
            })

            addView(spacer(dp(8)))

            addView(Button(context).apply {
                text = "I need this — pause blocking"
                setOnClickListener { onPauseRequested?.invoke() }
            })
        }
    }

    private fun updateTexts(label: String, reason: BlockReason) {
        titleView?.text = label
        reasonView?.text = reasonText(reason)
        updateCountdown()
    }

    private fun reasonText(reason: BlockReason): String = when (reason) {
        BlockReason.HARD_BLOCK -> "Blocked by one of your policies."
        BlockReason.QUOTA_EXHAUSTED -> "You have used up your time for now."
    }

    @SuppressLint("SetTextI18n")
    private fun updateCountdown() {
        val view = countdownView ?: return
        val lifts = liftsAtMs
        if (lifts == null) {
            view.text = ""
            view.visibility = View.GONE
            return
        }
        val remaining = lifts - System.currentTimeMillis()
        view.visibility = View.VISIBLE
        view.text = if (remaining <= 0) {
            "Time is available again — reopen the app."
        } else {
            "More time in ${formatDuration(remaining)}"
        }
    }

    private fun goHome() {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        hide()
    }

    private fun spacer(height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, height)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/** "4m 12s" / "58s" / "1h 3m". Shared by the overlay and the Compose UI. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
