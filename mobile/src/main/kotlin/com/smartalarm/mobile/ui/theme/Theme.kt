package com.smartalarm.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.smartalarm.core.model.SleepStage

/**
 * A night palette.
 *
 * The app is used in a dark bedroom at either end of the night, so the dark scheme is the real
 * one and the light scheme exists only so the app does not look broken if someone opens it at
 * noon with the system in light mode. Nothing here is pure white or pure black: the brightest
 * text sits at 95% so it does not glare off a phone held a foot from a face in the dark.
 */
object NightColors {
    val Background = Color(0xFF07070F)
    val Surface = Color(0xFF12122A)
    val SurfaceHigh = Color(0xFF1B1B3C)
    val Outline = Color(0xFF2C2C55)

    val Primary = Color(0xFF9B8CFF)
    val PrimaryDeep = Color(0xFF6C5CE7)
    val Secondary = Color(0xFF63E6E2)
    val Tertiary = Color(0xFFFFB86B)
    val Danger = Color(0xFFFF8A9B)

    val TextPrimary = Color(0xFFF2EFFF)
    val TextSecondary = Color(0xFFA3A0C9)
    val TextTertiary = Color(0xFF6F6C97)

    // Stage colours, ordered light to dark the way the hypnogram reads top to bottom.
    val Awake = Color(0xFFFFB86B)
    val Rem = Color(0xFF63E6E2)
    val Light = Color(0xFF9B8CFF)
    val Deep = Color(0xFF4A46A8)

    val SkyGradient = Brush.verticalGradient(
        listOf(Color(0xFF17123A), Color(0xFF0C0A1F), Color(0xFF07070F)),
    )
}

fun SleepStage.color(): Color = when (this) {
    SleepStage.AWAKE -> NightColors.Awake
    SleepStage.REM -> NightColors.Rem
    SleepStage.LIGHT -> NightColors.Light
    SleepStage.DEEP -> NightColors.Deep
    SleepStage.UNKNOWN -> NightColors.TextTertiary
}

fun SleepStage.displayName(): String = when (this) {
    SleepStage.AWAKE -> "Awake"
    SleepStage.REM -> "REM"
    SleepStage.LIGHT -> "Light"
    SleepStage.DEEP -> "Deep"
    SleepStage.UNKNOWN -> "Settling"
}

private val DarkScheme = darkColorScheme(
    primary = NightColors.Primary,
    onPrimary = Color(0xFF16112F),
    primaryContainer = NightColors.PrimaryDeep,
    onPrimaryContainer = NightColors.TextPrimary,
    secondary = NightColors.Secondary,
    onSecondary = Color(0xFF07231F),
    tertiary = NightColors.Tertiary,
    onTertiary = Color(0xFF2B1A05),
    background = NightColors.Background,
    onBackground = NightColors.TextPrimary,
    surface = NightColors.Surface,
    onSurface = NightColors.TextPrimary,
    surfaceVariant = NightColors.SurfaceHigh,
    onSurfaceVariant = NightColors.TextSecondary,
    outline = NightColors.Outline,
    outlineVariant = Color(0xFF232348),
    error = NightColors.Danger,
    onError = Color(0xFF3A0912),
)

private val LightScheme = lightColorScheme(
    primary = NightColors.PrimaryDeep,
    secondary = Color(0xFF1FA7A3),
    tertiary = Color(0xFFCC7A1E),
    background = Color(0xFFF7F5FF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECE8FF),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

@Composable
fun SmartAlarmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
