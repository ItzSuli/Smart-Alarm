package com.smartalarm.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartalarm.core.model.CheckResult
import com.smartalarm.core.model.CheckStatus
import com.smartalarm.core.model.SystemCheckReport
import com.smartalarm.mobile.ui.theme.NightColors

/**
 * The system check: run it on an evening that does not matter, and find out whether the alarm
 * would actually have woken you on one that does.
 */
@Composable
fun SystemCheckScreen(
    report: SystemCheckReport,
    estimatedSeconds: Int,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (checkId: String, yes: Boolean) -> Unit,
    onStopTestAlarm: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    // Leaving this screen must never leave the test alarm ringing.
    DisposableEffect(Unit) { onDispose { onStopTestAlarm() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightColors.SkyGradient)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = NightColors.TextPrimary,
                )
            }
            Text(
                "System check",
                style = MaterialTheme.typography.headlineSmall,
                color = NightColors.TextPrimary,
            )
        }

        Spacer(Modifier.height(12.dp))
        VerdictCard(report, estimatedSeconds)

        Spacer(Modifier.height(16.dp))
        if (report.allChecks.isEmpty() && !report.running) {
            IntroCard(estimatedSeconds)
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRun,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NightColors.Primary,
                    contentColor = NightColors.Background,
                ),
            ) {
                Text("Run the check", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(40.dp))
            return@Column
        }

        if (report.phoneChecks.isNotEmpty()) {
            CheckSection("This phone", report.phoneChecks, onConfirm)
            Spacer(Modifier.height(12.dp))
        }
        if (report.watchChecks.isNotEmpty()) {
            CheckSection("The watch", report.watchChecks, onConfirm)
            Spacer(Modifier.height(12.dp))
        }

        AnimatedVisibility(visible = report.confirmations.isNotEmpty()) {
            Column {
                CheckSection(
                    title = "Only you can answer these",
                    checks = report.confirmations,
                    onConfirm = onConfirm,
                    subtitle = "The alarm just went off the way it would in the morning.",
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        if (report.running) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NightColors.TextSecondary),
            ) {
                Text("Cancel")
            }
        } else {
            Button(
                onClick = onRun,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NightColors.SurfaceHigh,
                    contentColor = NightColors.TextPrimary,
                ),
            ) {
                Text("Run again")
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun VerdictCard(report: SystemCheckReport, estimatedSeconds: Int) {
    val accent = when {
        report.running -> NightColors.Primary
        report.failures.isNotEmpty() -> NightColors.Danger
        report.warnings.isNotEmpty() -> NightColors.Tertiary
        report.allChecks.isEmpty() -> NightColors.TextTertiary
        else -> NightColors.Secondary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightColors.Surface)
            .padding(18.dp),
    ) {
        Text(
            report.verdict,
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                report.running ->
                    "Hold the watch still for about $estimatedSeconds seconds. It is reading " +
                        "your pulse and movement the way it would overnight."

                report.allChecks.isEmpty() ->
                    "Not run yet."

                report.failures.isNotEmpty() ->
                    "Fix the red items below, then run it again."

                report.warnings.isNotEmpty() ->
                    "The alarm will fire. The amber items are worth reading before you rely on it."

                else ->
                    "Sensors, the link to the watch, and the alarm itself all work."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = NightColors.TextSecondary,
        )
        if (report.running) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                color = NightColors.Primary,
                trackColor = NightColors.SurfaceHigh,
            )
        }
        if (report.roundTripMillis > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Watch answered in ${report.roundTripMillis / 1000}s.",
                style = MaterialTheme.typography.labelSmall,
                color = NightColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun IntroCard(estimatedSeconds: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightColors.Surface)
            .padding(18.dp),
    ) {
        Text(
            "What it does",
            style = MaterialTheme.typography.titleMedium,
            color = NightColors.TextPrimary,
        )
        Spacer(Modifier.height(10.dp))
        listOf(
            "Holds the watch's sensors open for $estimatedSeconds seconds and checks real " +
                "movement and heart-rate data arrives",
            "Runs that data through the same analysis used overnight",
            "Sends it to this phone, so the link is proven end to end",
            "Buzzes the watch and rings the phone the way a real morning would",
            "Asks you whether you actually felt and heard it",
        ).forEach { line ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text("·  ", color = NightColors.Primary)
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NightColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Wear the watch and keep it on for the whole check.",
            style = MaterialTheme.typography.bodySmall,
            color = NightColors.TextTertiary,
        )
    }
}

@Composable
private fun CheckSection(
    title: String,
    checks: List<CheckResult>,
    onConfirm: (String, Boolean) -> Unit,
    subtitle: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightColors.Surface)
            .padding(16.dp),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = NightColors.TextTertiary,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NightColors.TextSecondary)
        }
        Spacer(Modifier.height(10.dp))
        checks.forEach { check ->
            CheckRow(check, onConfirm)
        }
    }
}

@Composable
private fun CheckRow(check: CheckResult, onConfirm: (String, Boolean) -> Unit) {
    val needsAnswer = check.status == CheckStatus.PENDING && check.id.startsWith("confirm.")

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(check.status)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                check.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (check.status == CheckStatus.PENDING) NightColors.TextTertiary
                else NightColors.TextPrimary,
                fontWeight = if (needsAnswer) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (check.detail.isNotEmpty()) {
                Text(
                    check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = NightColors.TextSecondary,
                )
            }
            if (check.remedy.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    check.remedy,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(check.status),
                )
            }
            if (needsAnswer) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnswerChip("Yes", NightColors.Secondary) { onConfirm(check.id, true) }
                    AnswerChip("No", NightColors.Danger) { onConfirm(check.id, false) }
                }
            }
        }
    }
}

@Composable
private fun AnswerChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun StatusDot(status: CheckStatus) {
    val transition = rememberInfiniteTransition(label = "checkPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse",
    )
    val color = statusColor(status)
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .size(10.dp)
            .alpha(if (status == CheckStatus.RUNNING) pulse else 1f)
            .clip(CircleShape)
            .background(color),
    )
}

private fun statusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.PASS -> NightColors.Secondary
    CheckStatus.WARN -> NightColors.Tertiary
    CheckStatus.FAIL -> NightColors.Danger
    CheckStatus.RUNNING -> NightColors.Primary
    CheckStatus.SKIPPED -> NightColors.TextTertiary
    CheckStatus.PENDING -> NightColors.Outline
}
