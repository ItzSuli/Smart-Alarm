package com.smartalarm.wear.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import com.smartalarm.core.model.SleepStage

/** Night colours, shared with the phone app so the two read as one product. */
object Night {
    val Background = Color(0xFF07070F)
    val Surface = Color(0xFF14142B)
    val Primary = Color(0xFF9B8CFF)
    val PrimaryVariant = Color(0xFF6C5CE7)
    val Secondary = Color(0xFF63E6E2)
    val OnPrimary = Color(0xFF15102E)
    val TextPrimary = Color(0xFFF2EFFF)
    val TextSecondary = Color(0xFF9E9AC0)
    val Error = Color(0xFFFF8A9B)

    val Awake = Color(0xFFFFB86B)
    val Rem = Color(0xFF63E6E2)
    val Light = Color(0xFF9B8CFF)
    val Deep = Color(0xFF3D3A8C)
}

fun SleepStage.color(): Color = when (this) {
    SleepStage.AWAKE -> Night.Awake
    SleepStage.REM -> Night.Rem
    SleepStage.LIGHT -> Night.Light
    SleepStage.DEEP -> Night.Deep
    SleepStage.UNKNOWN -> Night.TextSecondary
}

@Composable
fun SmartAlarmWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = Night.Primary,
            primaryVariant = Night.PrimaryVariant,
            secondary = Night.Secondary,
            secondaryVariant = Night.Secondary,
            background = Night.Background,
            surface = Night.Surface,
            error = Night.Error,
            onPrimary = Night.OnPrimary,
            onSecondary = Night.OnPrimary,
            onBackground = Night.TextPrimary,
            onSurface = Night.TextPrimary,
            onSurfaceVariant = Night.TextSecondary,
            onError = Night.OnPrimary,
        ),
        content = content,
    )
}
