package com.ezhou.rotblock.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import kotlin.math.ceil

/** One segment of one bar. */
data class Segment(val value: Float, val color: Color)

/**
 * One bar: a stack of segments plus its axis label.
 *
 * [below] grows downward from the same baseline, for a quantity that must never be read as part of
 * the stack above it. Both lists are ordered from the baseline outward.
 */
data class Bar(val label: String, val segments: List<Segment>, val below: List<Segment> = emptyList()) {
    val total: Float get() = segments.sumOf { it.value.toDouble() }.toFloat()
    val belowTotal: Float get() = below.sumOf { it.value.toDouble() }.toFloat()
}

/**
 * Stacked bars on a shared baseline.
 *
 * Mark rules applied here: a 2px surface-coloured gap between stacked segments so adjacent fills
 * never touch, 4px rounding on the top of the stack only (the data end) with the baseline left
 * square, hairline gridlines behind the marks, and no value printed on every bar -- the tap
 * handler surfaces exact numbers instead.
 */
@Composable
fun StackedBarChart(
    bars: List<Bar>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    selectedIndex: Int? = null,
    onSelect: (Int?) -> Unit = {},
    labelEvery: Int = 1,
    gridLines: Int = 3,
    formatGridValue: (Float) -> String = { it.toInt().toString() },
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val grid = ChartPalette.grid()
    val axisColor = ChartPalette.axis()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val currentOnSelect by rememberUpdatedState(onSelect)

    val axis = remember(bars, gridLines) { AxisSplit.of(bars, gridLines) }

    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(bars.size, selectedIndex) {
                    detectTapGestures { offset ->
                        if (bars.isEmpty()) return@detectTapGestures
                        val index = columnAt(offset.x, size.width, bars.size, density)
                        if (index !in bars.indices) {
                            onSelect(null)
                        } else {
                            // Tapping the selected column clears it, so there is a way back out.
                            onSelect(if (index == selectedIndex) null else index)
                        }
                    }
                }
                // Scrubbing selects whatever column the finger is over and never toggles. A
                // horizontal drag starts it at once without stealing the page's vertical scroll;
                // holding first lets the finger wander vertically too.
                .pointerInput(bars.size) {
                    val scrubTo = { x: Float ->
                        val index = columnAt(x, size.width, bars.size, density)
                        if (index in bars.indices) currentOnSelect(index)
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { scrubTo(it.x) },
                    ) { change, _ -> scrubTo(change.position.x) }
                }
                .pointerInput(bars.size) {
                    val scrubTo = { x: Float ->
                        val index = columnAt(x, size.width, bars.size, density)
                        if (index in bars.indices) currentOnSelect(index)
                    }
                    detectDragGesturesAfterLongPress(
                        onDragStart = { scrubTo(it.x) },
                    ) { change, _ ->
                        change.consume()
                        scrubTo(change.position.x)
                    }
                },
        ) {
            val labelHeight = 18.dp.toPx()
            val axisLabelWidth = 34.dp.toPx()
            val plotLeft = axisLabelWidth
            val plotWidth = size.width - axisLabelWidth
            val plotHeight = size.height - labelHeight
            val segmentGap = 2.dp.toPx()
            val corner = 4.dp.toPx()

            val unit = plotHeight / axis.span
            val baselineY = axis.stepsAbove * axis.step * unit

            drawGrid(
                axis = axis,
                unit = unit,
                baselineY = baselineY,
                plotLeft = plotLeft,
                plotWidth = plotWidth,
                grid = grid,
                axisColor = axisColor,
                measurer = measurer,
                muted = muted,
                format = formatGridValue,
            )

            if (bars.isEmpty()) return@Canvas

            val slot = plotWidth / bars.size
            val barWidth = (slot * 0.62f).coerceAtMost(28.dp.toPx())

            bars.forEachIndexed { index, bar ->
                val centerX = plotLeft + slot * index + slot / 2f
                val left = centerX - barWidth / 2f
                val dimmed = selectedIndex != null && selectedIndex != index

                drawStack(bar.segments, upward = true, left, barWidth, baselineY, unit, dimmed, corner, segmentGap, surface)
                drawStack(bar.below, upward = false, left, barWidth, baselineY, unit, dimmed, corner, segmentGap, surface)

                if (index % labelEvery == 0) {
                    val layout = measurer.measure(bar.label, TextStyle(fontSize = 9.sp, color = muted))
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            centerX - layout.size.width / 2f,
                            plotHeight + (labelHeight - layout.size.height) / 2f,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * One scale for both directions: a shared tick step, with as many steps on each side of the baseline
 * as that side needs. Two independently rounded halves would put different minutes under the same
 * pixel height, which is a dual axis wearing a disguise.
 */
private class AxisSplit(val step: Float, val stepsAbove: Int, val stepsBelow: Int) {
    val span: Float get() = step * (stepsAbove + stepsBelow)

    companion object {
        fun of(bars: List<Bar>, gridLines: Int): AxisSplit {
            val above = bars.maxOfOrNull { it.total } ?: 0f
            val below = bars.maxOfOrNull { it.belowTotal } ?: 0f
            if (below <= 0f) {
                return AxisSplit(niceCeiling(above.takeIf { it > 0f } ?: 1f) / gridLines, gridLines, 0)
            }
            val step = niceCeiling(maxOf(above, below)) / gridLines
            return AxisSplit(
                step = step,
                stepsAbove = ceil(above / step).toInt().coerceAtLeast(1),
                stepsBelow = ceil(below / step).toInt().coerceAtLeast(1),
            )
        }
    }
}

private fun DrawScope.drawStack(
    segments: List<Segment>,
    upward: Boolean,
    left: Float,
    barWidth: Float,
    baselineY: Float,
    unit: Float,
    dimmed: Boolean,
    corner: Float,
    segmentGap: Float,
    surface: Color,
) {
    val visible = segments.filter { it.value > 0f }
    var cursorY = baselineY
    visible.forEachIndexed { segIndex, segment ->
        val height = segment.value * unit
        if (height <= 0f) return@forEachIndexed
        val top = if (upward) cursorY - height else cursorY
        val isEnd = segIndex == visible.lastIndex
        val color = if (dimmed) segment.color.copy(alpha = 0.28f) else segment.color

        if (isEnd) {
            // Round only the data end; the baseline stays square so bars sit flat against it.
            val rounded = CornerRadius(corner, corner)
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(Offset(left, top), Size(barWidth, height)),
                        topLeft = if (upward) rounded else CornerRadius.Zero,
                        topRight = if (upward) rounded else CornerRadius.Zero,
                        bottomLeft = if (upward) CornerRadius.Zero else rounded,
                        bottomRight = if (upward) CornerRadius.Zero else rounded,
                    ),
                )
            }
            drawPath(path, color)
        } else {
            drawRect(color, Offset(left, top), Size(barWidth, height))
        }

        // Surface-coloured spacer on the outward edge, so neighbouring fills never blend.
        if (!isEnd && height > segmentGap) {
            val gapY = if (upward) top else top + height - segmentGap
            drawRect(surface, Offset(left, gapY), Size(barWidth, segmentGap))
        }
        cursorY = if (upward) top else top + height
    }
}

private fun DrawScope.drawGrid(
    axis: AxisSplit,
    unit: Float,
    baselineY: Float,
    plotLeft: Float,
    plotWidth: Float,
    grid: Color,
    axisColor: Color,
    measurer: TextMeasurer,
    muted: Color,
    format: (Float) -> String,
) {
    val lines = (1..axis.stepsAbove).map { baselineY - it * axis.step * unit to it * axis.step } +
        (1..axis.stepsBelow).map { baselineY + it * axis.step * unit to it * axis.step }
    for ((y, value) in lines) {
        drawLine(grid, Offset(plotLeft, y), Offset(plotLeft + plotWidth, y), strokeWidth = 1f)
        val layout = measurer.measure(format(value), TextStyle(fontSize = 9.sp, color = muted))
        drawText(layout, topLeft = Offset(plotLeft - layout.size.width - 6f, y - layout.size.height / 2f))
    }
    drawLine(axisColor, Offset(plotLeft, baselineY), Offset(plotLeft + plotWidth, baselineY), strokeWidth = 2f)
}

/** Rounds the axis top up to something that reads well as a tick value. */
internal fun niceCeiling(value: Float): Float {
    if (value <= 0f) return 1f
    val steps = floatArrayOf(1f, 2f, 3f, 5f, 10f, 15f, 20f, 30f, 45f, 60f, 90f, 120f, 180f, 240f, 360f, 480f, 720f)
    steps.firstOrNull { it >= value }?.let { return it }
    var candidate = 720f
    while (candidate < value) candidate += 360f
    return candidate
}

/**
 * Nearest-column hit testing: the target is the full column, gaps and axis label band included,
 * not just the drawn bar. At 30 bars an individual bar is only a few dp wide, so anything narrower
 * would be unhittable.
 */
private fun columnAt(x: Float, width: Int, count: Int, density: Density): Int {
    val axisLabelWidth = with(density) { 34.dp.toPx() }
    val slot = (width - axisLabelWidth) / count
    return ((x - axisLabelWidth) / slot).toInt()
}
