package com.smartalarm.mobile.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayMonth = SimpleDateFormat("EEE d MMM", Locale.getDefault())
private val fullDate = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())

fun formatClock(millis: Long): String = clock.format(Date(millis))

fun formatDayMonth(millis: Long): String = dayMonth.format(Date(millis))

fun formatFullDate(millis: Long): String = fullDate.format(Date(millis))

/** "9h 00m", the way a sleep duration is normally read. */
fun formatDuration(minutes: Float): String {
    val total = minutes.roundToInt().coerceAtLeast(0)
    val hours = total / 60
    val rest = total % 60
    return when {
        hours > 0 -> "${hours}h ${rest.toString().padStart(2, '0')}m"
        else -> "${rest}m"
    }
}

fun formatDurationShort(minutes: Float): String {
    val total = minutes.roundToInt().coerceAtLeast(0)
    val hours = total / 60
    val rest = total % 60
    return when {
        hours > 0 && rest > 0 -> "${hours}h ${rest}m"
        hours > 0 -> "${hours}h"
        else -> "${rest}m"
    }
}

/** "tonight" versus "tomorrow", so a wake time after midnight is not ambiguous. */
fun relativeDayLabel(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val nowDay = now.get(Calendar.DAY_OF_YEAR)
    val thenDay = then.get(Calendar.DAY_OF_YEAR)
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    return when {
        sameYear && nowDay == thenDay -> "today"
        sameYear && thenDay - nowDay == 1 -> "tomorrow"
        else -> formatDayMonth(millis)
    }
}
