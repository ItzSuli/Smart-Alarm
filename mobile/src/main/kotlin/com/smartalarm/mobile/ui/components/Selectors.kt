package com.smartalarm.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.WakeMode
import com.smartalarm.mobile.ui.formatClock
import com.smartalarm.mobile.ui.theme.NightColors
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import com.smartalarm.mobile.R

/**
 * The cycle picker.
 *
 * Each option carries the clock time it would actually wake you at, which is the number the
 * decision is really made on — "5 cycles" means nothing at midnight, "06:12" means everything.
 */
@Composable
fun CycleSelector(
    selected: Int,
    wakeTimeFor: (Int) -> Long,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (AlarmPlan.MIN_CYCLES..AlarmPlan.MAX_CYCLES).forEach { cycles ->
                CycleOption(
                    cycles = cycles,
                    selected = cycles == selected,
                    wakeMillis = wakeTimeFor(cycles),
                    onClick = { onSelect(cycles) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CycleOption(
    cycles: Int,
    selected: Boolean,
    wakeMillis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        if (selected) NightColors.Primary else NightColors.Surface,
        tween(220), label = "cycleBg",
    )
    val scale by animateFloatAsState(if (selected) 1.04f else 1f, tween(220), label = "cycleScale")

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = NightColors.Outline,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = cycles.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) NightColors.Background else NightColors.TextPrimary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = formatClock(wakeMillis),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) NightColors.Background.copy(alpha = 0.75f) else NightColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Watch vibration, phone alarm, or both. */
@Composable
fun WakeModeSelector(
    selected: WakeMode,
    watchConnected: Boolean,
    onSelect: (WakeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WakeModeCard(
            mode = WakeMode.WATCH_ONLY,
            icon = R.drawable.ic_vibration,
            title = "Watch",
            detail = "Silent buzz",
            selected = selected == WakeMode.WATCH_ONLY,
            warning = if (watchConnected) null else "Watch offline",
            onClick = { onSelect(WakeMode.WATCH_ONLY) },
            modifier = Modifier.weight(1f),
        )
        WakeModeCard(
            mode = WakeMode.PHONE_ONLY,
            icon = R.drawable.ic_phone_android,
            title = "Phone",
            detail = "Alarm sound",
            selected = selected == WakeMode.PHONE_ONLY,
            warning = null,
            onClick = { onSelect(WakeMode.PHONE_ONLY) },
            modifier = Modifier.weight(1f),
        )
        WakeModeCard(
            mode = WakeMode.BOTH,
            icon = R.drawable.ic_watch,
            title = "Both",
            detail = "Buzz + sound",
            selected = selected == WakeMode.BOTH,
            warning = if (watchConnected) null else "Watch offline",
            onClick = { onSelect(WakeMode.BOTH) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WakeModeCard(
    mode: WakeMode,
    @DrawableRes icon: Int,
    title: String,
    detail: String,
    selected: Boolean,
    warning: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        if (selected) NightColors.PrimaryDeep else NightColors.Surface,
        tween(220), label = "modeBg",
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = NightColors.Outline,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (selected) NightColors.TextPrimary else NightColors.TextSecondary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = NightColors.TextPrimary,
        )
        Text(
            text = warning ?: detail,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                warning != null -> NightColors.Tertiary
                selected -> NightColors.TextPrimary.copy(alpha = 0.7f)
                else -> NightColors.TextTertiary
            },
            textAlign = TextAlign.Center,
        )
    }
}

/** A labelled figure, used across the live and history screens. */
@Composable
fun StatTile(
    value: String,
    label: String,
    accent: androidx.compose.ui.graphics.Color = NightColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(NightColors.Surface)
            .padding(vertical = 12.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = accent)
        Spacer(Modifier.height(2.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = NightColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/** A small pill that says whether the watch is there. */
@Composable
fun ConnectionPill(connected: Boolean, watchName: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (connected) NightColors.Secondary.copy(alpha = 0.14f)
                else NightColors.Tertiary.copy(alpha = 0.14f)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(if (connected) NightColors.Secondary else NightColors.Tertiary)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (connected) (watchName ?: "Watch connected") else "Watch not in range",
            style = MaterialTheme.typography.labelSmall,
            color = if (connected) NightColors.Secondary else NightColors.Tertiary,
        )
    }
}
