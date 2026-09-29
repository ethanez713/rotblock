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

/** One segment of one bar. */
data class Segment(val value: Float, val color: Color)

/** One bar: a stack of segments plus its axis label. */
data class Bar(val label: String, val segments: List<Segment>) {
    val total: Float get() = segments.sumOf { it.value.toDouble() }.toFloat()
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
    val axis = ChartPalette.axis()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val currentOnSelect by rememberUpdatedState(onSelect)

    val maxTotal = bars.maxOfOrNull { it.total }?.takeIf { it > 0f } ?: 1f
    val niceMax = niceCeiling(maxTotal)

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

            drawGrid(
                gridLines = gridLines,
                niceMax = niceMax,
                plotLeft = plotLeft,
                plotWidth = plotWidth,
                plotHeight = plotHeight,
                grid = grid,
                axis = axis,
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

                var cursorY = plotHeight
                val visible = bar.segments.filter { it.value > 0f }
                visible.forEachIndexed { segIndex, segment ->
                    val rawHeight = (segment.value / niceMax) * plotHeight
                    if (rawHeight <= 0f) return@forEachIndexed
                    val top = cursorY - rawHeight
                    val isTop = segIndex == visible.lastIndex
                    val color = if (dimmed) segment.color.copy(alpha = 0.28f) else segment.color

                    if (isTop) {
                        // Round only the data end; the baseline stays square so bars sit flat.
                        val path = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(Offset(left, top), Size(barWidth, rawHeight)),
                                    topLeft = CornerRadius(corner, corner),
                                    topRight = CornerRadius(corner, corner),
                                    bottomLeft = CornerRadius.Zero,
                                    bottomRight = CornerRadius.Zero,
                                ),
                            )
                        }
                        drawPath(path, color)
                    } else {
                        drawRect(color, Offset(left, top), Size(barWidth, rawHeight))
                    }

                    // Surface-coloured spacer so neighbouring fills never blend into one block.
                    if (!isTop && rawHeight > segmentGap) {
                        drawRect(surface, Offset(left, top), Size(barWidth, segmentGap))
                    }
                    cursorY = top
                }

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

private fun DrawScope.drawGrid(
    gridLines: Int,
    niceMax: Float,
    plotLeft: Float,
    plotWidth: Float,
    plotHeight: Float,
    grid: Color,
    axis: Color,
    measurer: TextMeasurer,
    muted: Color,
    format: (Float) -> String,
) {
    for (i in 1..gridLines) {
        val fraction = i.toFloat() / gridLines
        val y = plotHeight - plotHeight * fraction
        drawLine(grid, Offset(plotLeft, y), Offset(plotLeft + plotWidth, y), strokeWidth = 1f)
        val layout = measurer.measure(
            format(niceMax * fraction),
            TextStyle(fontSize = 9.sp, color = muted),
        )
        drawText(layout, topLeft = Offset(plotLeft - layout.size.width - 6f, y - layout.size.height / 2f))
    }
    drawLine(axis, Offset(plotLeft, plotHeight), Offset(plotLeft + plotWidth, plotHeight), strokeWidth = 2f)
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
