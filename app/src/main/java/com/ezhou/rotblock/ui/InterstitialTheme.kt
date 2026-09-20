package com.ezhou.rotblock.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.ezhou.rotblock.R

/**
 * Ethan design-system tokens for the three interstitial surfaces only.
 * The rest of the app keeps RotblockTheme; these screens are a different kind of page.
 */
object Ds {
    val paper0 = Color(0xFFFDFCF9)
    val paper1 = Color(0xFFF8F6F1)
    val paper2 = Color(0xFFF1EDE5)
    val ink900 = Color(0xFF211D18)
    val ink800 = Color(0xFF2E2924)
    val ink700 = Color(0xFF3E3830)
    val ink600 = Color(0xFF574F45)
    val ink500 = Color(0xFF70685B)
    val ink200 = Color(0xFFD8D1C5)
    val ink100 = Color(0xFFE8E2D8)
    val accent = Color(0xFFDD7A32)
    val accentHover = Color(0xFFBF6321)
    val onAccent = Color(0xFFFFF8F1)
    val danger = Color(0xFF5326FF)
    val dangerHover = Color(0xFF3A16F5)
    val onDanger = Color(0xFFFDFCFF)
    val more = Color(0xFFE80808)
    val less = Color(0xFF1B8A32)

    object Night {
        val paper0 = Color(0xFF232019)
        val paper1 = Color(0xFF1A1712)
        val paper2 = Color(0xFF12100C)
        val ink900 = Color(0xFFF7F4ED)
        val ink800 = Color(0xFFE8E3DA)
        val ink700 = Color(0xFFD6D0C5)
        val ink600 = Color(0xFFB4AEA2)
        val ink500 = Color(0xFF9A9385)
        val ink200 = Color(0xFF423D34)
        val ink100 = Color(0xFF322E27)
        val accent = Color(0xFFDD7A32)
        val onAccent = Color(0xFFFFF8F1)
        val danger = Color(0xFF6A3DFF)
        val onDanger = Color(0xFFFFFFFF)
        val more = Color(0xFFFF4A4A)
        val less = Color(0xFF4DDB6F)
    }
}

private val Piazzolla = FontFamily(
    Font(R.font.piazzolla, FontWeight.Normal),
    Font(R.font.piazzolla, FontWeight.SemiBold),
)

private val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans, FontWeight.Normal),
    Font(R.font.instrument_sans, FontWeight.Medium),
    Font(R.font.instrument_sans, FontWeight.SemiBold),
)

private val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

val InterstitialMono: FontFamily = PlexMono
val InterstitialSerif: FontFamily = Piazzolla
val InterstitialSans: FontFamily = InstrumentSans

private val DsTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Piazzolla,
        fontWeight = FontWeight.SemiBold,
        fontSize = 35.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.015).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Piazzolla,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.012).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Ds.accent,
    onPrimary = Ds.onAccent,
    primaryContainer = Ds.accent,
    onPrimaryContainer = Ds.onAccent,
    secondary = Ds.ink600,
    onSecondary = Ds.paper0,
    error = Ds.danger,
    onError = Ds.onDanger,
    errorContainer = Color(0xFFE8E5FF),
    onErrorContainer = Ds.dangerHover,
    background = Ds.paper1,
    onBackground = Ds.ink700,
    surface = Ds.paper0,
    onSurface = Ds.ink900,
    surfaceVariant = Ds.paper2,
    onSurfaceVariant = Ds.ink600,
    outline = Ds.ink200,
    outlineVariant = Ds.ink100,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Ds.Night.accent,
    onPrimary = Ds.Night.onAccent,
    primaryContainer = Ds.Night.accent,
    onPrimaryContainer = Ds.Night.onAccent,
    secondary = Ds.Night.ink600,
    onSecondary = Ds.Night.paper1,
    error = Ds.Night.danger,
    onError = Ds.Night.onDanger,
    errorContainer = Color(0xFF251852),
    onErrorContainer = Color(0xFFBCA9FF),
    background = Ds.Night.paper1,
    onBackground = Ds.Night.ink700,
    surface = Ds.Night.paper0,
    onSurface = Ds.Night.ink900,
    surfaceVariant = Ds.Night.paper2,
    onSurfaceVariant = Ds.Night.ink600,
    outline = Ds.Night.ink200,
    outlineVariant = Ds.Night.ink100,
)

@Composable
fun InterstitialTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = DsTypography,
        content = content,
    )
}

fun dsNight(context: Context): Boolean =
    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES

/** Android ints for the overlay, which cannot host Compose. */
class DsAndroid(night: Boolean) {
    val bg = if (night) 0xFF1A1712.toInt() else 0xFFF8F6F1.toInt()
    val heading = if (night) 0xFFF7F4ED.toInt() else 0xFF211D18.toInt()
    val body = if (night) 0xFFD6D0C5.toInt() else 0xFF3E3830.toInt()
    val muted = if (night) 0xFFB4AEA2.toInt() else 0xFF574F45.toInt()
    val faint = if (night) 0xFF9A9385.toInt() else 0xFF70685B.toInt()
    val accent = 0xFFDD7A32.toInt()
    val onAccent = 0xFFFFF8F1.toInt()
    val more = if (night) 0xFFFF4A4A.toInt() else 0xFFE80808.toInt()
    val less = if (night) 0xFF4DDB6F.toInt() else 0xFF1B8A32.toInt()
}

fun dsHeadingTypeface(context: Context): Typeface {
    val base = ResourcesCompat.getFont(context, R.font.piazzolla) ?: Typeface.SERIF
    return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, 600, false) else Typeface.create(base, Typeface.BOLD)
}

fun dsBodyTypeface(context: Context): Typeface =
    ResourcesCompat.getFont(context, R.font.instrument_sans) ?: Typeface.SANS_SERIF

fun dsMonoTypeface(context: Context): Typeface =
    ResourcesCompat.getFont(context, R.font.ibm_plex_mono_regular) ?: Typeface.MONOSPACE

fun dsMonoMediumTypeface(context: Context): Typeface {
    val base = ResourcesCompat.getFont(context, R.font.ibm_plex_mono_medium)
        ?: ResourcesCompat.getFont(context, R.font.ibm_plex_mono_regular)
        ?: Typeface.MONOSPACE
    return base
}
