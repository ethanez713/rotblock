package com.ezhou.rotblock.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Chart colours, kept apart from the app theme because they answer to different rules.
 *
 * Both columns are selected for their own surface rather than one being an automatic lightening
 * of the other, and the slot ORDER is the colourblind-safety mechanism: it was validated as a
 * sequence, so slots must be assigned in order and never cycled or sorted by value.
 *
 * Validated with the dataviz validator against this app's own card surfaces
 * (light #F0F1F3, dark #17171D), 6 slots, adjacent pairs:
 *   light — CVD ΔE 9.1, normal-vision ΔE 19.6, all checks pass
 *   dark  — CVD ΔE 8.4, normal-vision ΔE 19.3, all checks pass
 *
 * Light mode carries a sub-3:1 contrast warning on four slots, which obliges "relief": the
 * per-target breakdown table under the chart is that relief and must not be removed.
 */
object ChartPalette {

    private val lightSeries = listOf(
        Color(0xFF2A78D6), // blue
        Color(0xFFEB6834), // orange
        Color(0xFF1BAF7A), // aqua
        Color(0xFFEDA100), // yellow
        Color(0xFFE87BA4), // magenta
        Color(0xFF008300), // green
    )

    private val darkSeries = listOf(
        Color(0xFF3987E5),
        Color(0xFFD95926),
        Color(0xFF199E70),
        Color(0xFFC98500),
        Color(0xFFD55181),
        Color(0xFF008300),
    )

    /** Anything past the last slot folds in here rather than inventing a new hue. */
    private val lightOther = Color(0xFF898781)
    private val darkOther = Color(0xFF898781)

    /** Reserved status colour. Only ever used for emergency skips, never as a series. */
    val critical = Color(0xFFD03B3B)

    val slotCount: Int get() = lightSeries.size

    @Composable
    @ReadOnlyComposable
    fun series(index: Int): Color {
        val palette = if (isSystemInDarkTheme()) darkSeries else lightSeries
        return palette.getOrElse(index) { if (isSystemInDarkTheme()) darkOther else lightOther }
    }

    @Composable
    @ReadOnlyComposable
    fun other(): Color = if (isSystemInDarkTheme()) darkOther else lightOther

    @Composable
    @ReadOnlyComposable
    fun grid(): Color = if (isSystemInDarkTheme()) Color(0xFF2C2C2A) else Color(0xFFE1E0D9)

    @Composable
    @ReadOnlyComposable
    fun axis(): Color = if (isSystemInDarkTheme()) Color(0xFF383835) else Color(0xFFC3C2B7)
}
