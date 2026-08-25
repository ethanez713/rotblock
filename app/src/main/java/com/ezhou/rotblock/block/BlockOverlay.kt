package com.ezhou.rotblock.block

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ezhou.rotblock.policy.BlockReason
import com.ezhou.rotblock.policy.TargetKind

/**
 * The way out of a block that is narrower than the app it covers.
 *
 * A website or feed rule blocks part of something you are otherwise allowed to use, and the block
 * screen covers the whole app -- including the address bar you would need to leave the blocked
 * page. Without this the browser is a dead end: closing it and reopening it restores the same tab,
 * gets blocked again, and there is no reachable way to go anywhere else. The only escape left is
 * disabling the policy, which is precisely the behaviour rotblock exists to prevent.
 */
data class Escape(val label: String, val action: () -> Unit)

/**
 * The escape a block over [kind] has to offer, or null when closing the app is already a complete
 * answer because the app *is* the target.
 *
 * [kind] is the kind of the target **on screen**, never of the rule that blocked it. Since groups
 * those differ: a group can be the reason a website is blocked, and taking the escape from the
 * blocking rule would offer "close this app" over a browser sitting on a page it cannot leave.
 *
 * Exhaustive on purpose: a new kind of target cannot be added without someone deciding whether it
 * strands the user. That decision is the whole content of this function.
 */
fun escapeLabelFor(kind: TargetKind): String? = when (kind) {
    TargetKind.APP -> null
    TargetKind.FEED -> "Leave the feed"
    TargetKind.WEBSITE -> "Leave this site"
    // Not "a group needs no escape" -- a group is never the thing on screen at all, so this is
    // unreachable by construction rather than by judgement. Answering null keeps a routing mistake
    // from silently dropping a website's way out: the caller resolves the kind from the target id,
    // so arriving here at all means the target id was a group's, which nothing can produce.
    TargetKind.GROUP -> null
}

/**
 * What leaving means when there is nothing narrower to leave: the app itself is the target, so
 * closing it is the whole answer. This is the block screen's main button for an app rule, and its
 * quiet alternative for the other two.
 */
private const val CLOSE_LABEL = "Close this app"

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
    private var liftReason: BlockReason? = null

    /** The escape the visible screen was built with, so a changed one forces a rebuild. */
    private var shownEscapeLabel: String? = null
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
        escape: Escape? = null,
        /**
         * True for [BlockSelfTest]: strips the screen down to the one button it advertises. A
         * self test has no real policy behind it, so the "Close this app" and "I actually need
         * this" links would either do nothing useful or, worse, open the pause flow for a policy
         * that does not exist -- and either one going down a path other than [escape]'s own action
         * would leave [BlockSelfTest] waiting for an end that never comes.
         */
        singleAction: Boolean = false,
    ): Boolean {
        // Text can be swapped in place; the way out cannot. A block screen that stays up while the
        // target changes underneath it -- a website block replaced by an app block on the same
        // screen -- would otherwise keep the previous escape's button *and its closure*, so the
        // main button would try to move a browser that is no longer what you are in. Before the
        // fields below are set, because tearing down clears them.
        if (root != null && escape?.label != shownEscapeLabel) hide()

        this.liftsAtMs = liftsAtMs
        this.liftReason = reason
        this.onPauseRequested = onPauseRequested
        if (root != null) {
            updateTexts(label, reason)
            return true
        }
        if (!canDrawOverlays()) return false

        val view = buildView(label, reason, escape, singleAction)
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
            shownEscapeLabel = escape?.label
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
        shownEscapeLabel = null
        liftsAtMs = null
        liftReason = null
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
    private fun buildView(
        label: String,
        reason: BlockReason,
        escape: Escape?,
        singleAction: Boolean = false,
    ): LinearLayout {
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

            // The one button on the screen is the one that does what the block is asking for:
            // leave. For a website or a feed that means moving the app off the blocked page,
            // because closing it alone strands you -- it reopens on the same page and blocks
            // again. For an app there is nothing narrower to leave, so leaving *is* closing it.
            addView(
                primaryButton(escape?.label ?: CLOSE_LABEL) {
                    if (escape == null) {
                        goHome()
                    } else {
                        hide()
                        escape.action()
                    }
                },
            )

            // Everything below is deliberately not a button. A block screen with three equal
            // buttons puts "let me through" on the same footing as "fine, leave", and the way out
            // of a rule should never be the easiest-looking thing on the screen.
            if (!singleAction) {
                if (escape != null) {
                    addView(spacer(dp(18)))
                    addView(textLink(CLOSE_LABEL, "#9BA3AD") { goHome() })
                }

                addView(spacer(dp(14)))
                addView(textLink("I actually need this", "#6E7681") { onPauseRequested?.invoke() })
            }
        }
    }

    /** A filled, rounded, full-width tap target built by hand -- there is no theme out here. */
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

    /** Text that is tappable and does not pretend to be a button. Padded to stay reachable. */
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

    private fun updateTexts(label: String, reason: BlockReason) {
        titleView?.text = label
        reasonView?.text = reasonText(reason)
        updateCountdown()
    }

    private fun reasonText(reason: BlockReason): String = when (reason) {
        BlockReason.HARD_BLOCK -> "Blocked by one of your policies."
        BlockReason.QUOTA_EXHAUSTED -> "You have used up your time for now."
        BlockReason.WORKOUT_MODE -> "Workout in progress."
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
        // A hard block has no time to run out of, so the only thing that can make it lift on its
        // own is its scheduled window ending -- and workout mode is the same: its deadline is a
        // cap, not an allowance. Saying "more time" for either would promise an allowance that
        // does not exist. Written as a positive `when` naming every reason rather than a `!=`, so
        // the next reason added has to answer this question rather than falling through it.
        val quota = when (liftReason) {
            BlockReason.HARD_BLOCK, BlockReason.WORKOUT_MODE -> false
            BlockReason.QUOTA_EXHAUSTED -> true
            null -> false
        }
        view.text = when {
            remaining <= 0 && quota -> "Time is available again — reopen the app."
            remaining <= 0 -> "Allowed again — reopen the app."
            quota -> "More time in ${formatDuration(remaining)}"
            else -> "Allowed again in ${formatDuration(remaining)}"
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
