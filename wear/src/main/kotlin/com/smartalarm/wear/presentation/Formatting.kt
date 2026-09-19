package com.smartalarm.wear.presentation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

fun formatClock(millis: Long): String = clockFormat.format(Date(millis))

/** "9h 00m" for a cycle count, the way the user thinks about the choice. */
fun formatDuration(minutes: Float): String {
    val total = minutes.roundToInt().coerceAtLeast(0)
    val hours = total / 60
    val rest = total % 60
    return when {
        hours > 0 && rest > 0 -> "${hours}h ${rest}m"
        hours > 0 -> "${hours}h"
        else -> "${rest}m"
    }
}

fun formatDurationLong(minutes: Float): String {
    val total = minutes.roundToInt().coerceAtLeast(0)
    val hours = total / 60
    val rest = total % 60
    return when {
        hours > 0 && rest > 0 -> "$hours hr $rest min"
        hours > 0 -> "$hours hr"
        else -> "$rest min"
    }
}
