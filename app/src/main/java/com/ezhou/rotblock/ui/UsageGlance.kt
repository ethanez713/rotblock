package com.ezhou.rotblock.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.isSystemInDarkTheme
import com.ezhou.rotblock.insights.DiscouragingStatsResult
import com.ezhou.rotblock.insights.STATS_DAYS
import com.ezhou.rotblock.insights.deltaMinutes
import com.ezhou.rotblock.insights.formatHoursMinutes
import com.ezhou.rotblock.insights.yTicks
import kotlin.math.max
import kotlin.math.min

val MoreRedLight = Color(0xFFE80808)
val MoreRedDark = Color(0xFFFF4A4A)
val LessGreenLight = Color(0xFF1B8A32)
val LessGreenDark = Color(0xFF4DDB6F)

internal const val MORE_RED = 0xFFE80808.toInt()
internal const val MORE_RED_NIGHT = 0xFFFF4A4A.toInt()
internal const val LESS_GREEN = 0xFF1B8A32.toInt()
internal const val LESS_GREEN_NIGHT = 0xFF4DDB6F.toInt()

fun compareHead(todayMs: Long, avgMs: Long): String {
    val d = deltaMinutes(todayMs, avgMs)
    return when {
        d > 0 -> "${formatHoursMinutes(d * 60_000L)} more"
        d < 0 -> "${formatHoursMinutes(-d * 60_000L)} less"
        else -> "same"
    }
}

fun compareColorInt(todayMs: Long, avgMs: Long, onDark: Boolean): Int {
    val d = deltaMinutes(todayMs, avgMs)
    return when {
        d > 0 -> if (onDark) MORE_RED_NIGHT else MORE_RED
        d < 0 -> if (onDark) LESS_GREEN_NIGHT else LESS_GREEN
        else -> if (onDark) 0xFF9A9385.toInt() else 0xFF70685B.toInt()
    }
}

@Composable
fun moreColor(): Color = if (isSystemInDarkTheme()) Ds.Night.more else Ds.more

@Composable
fun lessColor(): Color = if (isSystemInDarkTheme()) Ds.Night.less else Ds.less

class HourClocksView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var minutesMs: Long = 0L
        set(value) {
            if (field == value) return
            field = value
            contentDescription = if (value > 0L) formatHoursMinutes(value) else null
            requestLayout()
            invalidate()
        }

    var onDarkSurface: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density get() = resources.displayMetrics.density
    private val clockPx get() = 22 * density
    private val gapPx get() = 5 * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = clockCount(minutesMs).coerceAtLeast(1)
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(suggestedMinimumWidth)
        val per = (clockPx + gapPx)
        val cols = max(1, if (width <= 0) 1 else ((width + gapPx) / per).toInt())
        val rows = (n + cols - 1) / cols
        val height = (rows * clockPx + (rows - 1) * gapPx).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val fracs = clockFracs(minutesMs)
        if (fracs.isEmpty()) return
        val more = if (onDarkSurface) MORE_RED_NIGHT else MORE_RED
        fill.color = more
        stroke.color = more
        empty.color = (more and 0x00FFFFFF) or 0x29000000
        val size = clockPx
        val gap = gapPx
        val per = size + gap
        val cols = max(1, ((width + gap) / per).toInt())
        var i = 0
        while (i < fracs.size) {
            val row = i / cols
            val col = i % cols
            val rowCount = min(cols, fracs.size - row * cols)
            val rowWidth = rowCount * size + (rowCount - 1) * gap
            val rowLeft = (width - rowWidth) / 2f
            val cx = rowLeft + col * per + size / 2f
            val cy = row * per + size / 2f
            drawClock(canvas, cx, cy, size / 2f - 1.6f * density, fracs[i])
            i++
        }
    }

    private fun drawClock(canvas: Canvas, cx: Float, cy: Float, r: Float, frac: Float) {
        canvas.drawCircle(cx, cy, r, empty)
        canvas.drawCircle(cx, cy, r, stroke)
        val t = frac.coerceIn(0f, 1f)
        if (t >= 0.98f) {
            canvas.drawCircle(cx, cy, r - 0.4f * density, fill)
        } else if (t > 0.04f) {
            val path = Path()
            path.moveTo(cx, cy)
            path.lineTo(cx, cy - r)
            path.arcTo(
                RectF(cx - r, cy - r, cx + r, cy + r),
                -90f,
                t * 360f,
                false,
            )
            path.close()
            canvas.drawPath(path, fill)
        }
        canvas.drawCircle(cx, cy - r + 0.2f * density, 1.15f * density, fill)
    }
}

class DayBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var appMs: List<Long> = emptyList()
        set(value) {
            field = value
            contentDescription = "30 days of use"
            invalidate()
        }
    var overlayMs: List<Long> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var quarterHour: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var onDarkSurface: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val density get() = resources.displayMetrics.density
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val appPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPale = Paint().apply { strokeWidth = resources.displayMetrics.density }
    private val gridBright = Paint().apply { strokeWidth = resources.displayMetrics.density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 9 * resources.displayMetrics.density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (108 * density).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val app = appMs
        if (app.isEmpty() || width == 0 || height == 0) return
        val overlay = overlayMs
        val d = density
        val padB = 16 * d
        val padT = 4 * d
        val padL = 28 * d
        val padR = 28 * d
        val innerH = height - padB - padT
        val innerW = width - padL - padR
        if (innerH <= 0 || innerW <= 0) return
        val n = app.size
        var maxRaw = 1L
        for (v in app) if (v > maxRaw) maxRaw = v
        for (v in overlay) if (v > maxRaw) maxRaw = v
        val (top, ticks) = yTicks(maxRaw, quarterHour)
        val slot = innerW / n
        val barW = max(2.5f * d, slot * 0.42f)

        overlayPaint.color = if (onDarkSurface) 0xFF827B6E.toInt() else 0xFFE8E2D8.toInt()
        appPaint.color = if (onDarkSurface) 0xFFE4DFD5.toInt() else 0xFF3E3830.toInt()
        todayPaint.color = if (onDarkSurface) 0xFFF7F4ED.toInt() else 0xFF211D18.toInt()
        gridPale.color = if (onDarkSurface) 0xFF8A8173.toInt() else 0xFF70685B.toInt()
        gridBright.color = if (onDarkSurface) 0xB3211D18.toInt() else 0xB3FDFCF9.toInt()
        textPaint.color = if (onDarkSurface) 0xFFB4AEA2.toInt() else 0xFF70685B.toInt()

        fun y(v: Long) = padT + innerH * (1f - v.toFloat() / top)
        fun ht(v: Long) = innerH * (v.toFloat() / top)

        val baseline = padT + innerH
        for (i in 0 until n) {
            val x = padL + i * slot + slot / 2f
            if (overlay.isNotEmpty()) {
                val ov = overlay.getOrElse(i) { 0L }
                if (ov > 0L) {
                    canvas.drawRect(
                        x - barW * 0.7f, y(ov),
                        x + barW * 0.7f, baseline,
                        overlayPaint,
                    )
                }
            }
            val v = app[i]
            if (v > 0L) {
                val paint = if (i == n - 1) todayPaint else appPaint
                val topY = baseline - max(ht(v), 1.5f * d)
                canvas.drawRect(x - barW * 0.38f, topY, x + barW * 0.38f, baseline, paint)
            }
        }

        for (tick in ticks) {
            val yy = y(tick.valueMs)
            canvas.drawLine(padL, yy, width - padR, yy, gridPale)
            canvas.drawLine(padL, yy, width - padR, yy, gridBright)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(tick.label, padL - 4 * d, yy + 3 * d, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(tick.label, width - padR + 4 * d, yy + 3 * d, textPaint)
        }

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("30d", padL, height - 2 * d, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("today", width - padR, height - 2 * d, textPaint)
    }
}

private fun clockFracs(minutesMs: Long): List<Float> {
    val hours = minutesMs / 3_600_000f
    val full = hours.toInt()
    val part = hours - full
    val out = ArrayList<Float>(full + 1)
    repeat(full) { out.add(1f) }
    if (part > 0.08f) out.add(part)
    if (out.isEmpty() && minutesMs > 0L) out.add(0.2f)
    return out
}

private fun clockCount(minutesMs: Long): Int = clockFracs(minutesMs).size

@Composable
fun HourClocks(minutesMs: Long, onDark: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { HourClocksView(it).also { v -> v.onDarkSurface = onDark } },
        update = {
            it.onDarkSurface = onDark
            it.minutesMs = minutesMs
        },
        modifier = modifier.fillMaxWidth().wrapContentHeight(),
    )
}

@Composable
fun DayBars(
    appMs: List<Long>,
    overlayMs: List<Long> = emptyList(),
    quarterHour: Boolean = false,
    onDark: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { DayBarsView(it).also { v -> v.onDarkSurface = onDark } },
        update = {
            it.onDarkSurface = onDark
            it.quarterHour = quarterHour
            it.overlayMs = overlayMs
            it.appMs = appMs
        },
        modifier = modifier.fillMaxWidth().height(108.dp),
    )
}

@Composable
fun CompareDelta(todayMs: Long, avgMs: Long, modifier: Modifier = Modifier) {
    val d = deltaMinutes(todayMs, avgMs)
    val color = when {
        d > 0 -> moreColor()
        d < 0 -> lessColor()
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val head = when {
        d > 0 -> "${formatHoursMinutes(d * 60_000L)} more"
        d < 0 -> "${formatHoursMinutes(-d * 60_000L)} less"
        else -> "same"
    }
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            head,
            color = color,
            fontFamily = InterstitialMono,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            "than your 30-day average",
            color = color,
            fontFamily = InterstitialMono,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun UsageGlance(
    stats: DiscouragingStatsResult,
    targetLabel: String?,
    dense: Boolean,
    onDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val appDays = stats.thisTargetDailyMs.ifEmpty { List(STATS_DAYS) { 0L } }
    val allDays = stats.allBlockedDailyMs.ifEmpty { List(STATS_DAYS) { 0L } }
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (targetLabel != null) {
            Text(
                "$targetLabel · ${formatHoursMinutes(stats.thisTargetToday)} today",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = InterstitialMono,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
        }
        HourClocks(stats.thisTargetToday, onDark)
        Spacer(Modifier.height(6.dp))
        CompareDelta(stats.thisTargetToday, stats.thisTargetAvg30Ms)
        if (!dense) {
            Spacer(Modifier.height(12.dp))
            Text(
                "all blocked · ${formatHoursMinutes(stats.allBlockedToday)} today",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = InterstitialMono,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            HourClocks(stats.allBlockedToday, onDark)
            Spacer(Modifier.height(14.dp))
            DayBars(
                appMs = appDays,
                overlayMs = allDays,
                onDark = onDark,
            )
        }
    }
}
