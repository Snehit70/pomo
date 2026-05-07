package com.pomoremote.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pomoremote.timer.TimerState
import com.pomoremote.ui.theme.Gold
import com.pomoremote.ui.theme.StatusConnected
import kotlinx.coroutines.delay
import java.util.Locale

public data class TimerStats(
    val todayMinutes: Int = 0,
    val todaySessions: Int = 0,
    val streak: Int = 0,
)

@Composable
public fun TimerScreen(
    state: TimerState?,
    stats: TimerStats,
    dailyGoal: Int,
    fallbackWorkSeconds: Int,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    onReset: () -> Unit,
) {
    val phaseColor = when (state?.phase) {
        TimerState.PHASE_WORK -> MaterialTheme.colorScheme.primary
        TimerState.PHASE_SHORT, TimerState.PHASE_LONG -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val phaseLabel = when (state?.phase) {
        TimerState.PHASE_WORK -> "FOCUS"
        TimerState.PHASE_SHORT -> "SHORT BREAK"
        TimerState.PHASE_LONG -> "LONG BREAK"
        else -> "FOCUS"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ConnectionStatusPill()
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .size(320.dp),
            contentAlignment = Alignment.Center,
        ) {
            TimerRings(
                state = state,
                phaseColor = phaseColor,
                completedSessions = state?.completed ?: 0,
                dailyGoal = dailyGoal,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimerText(state, phaseColor, fallbackWorkSeconds)
                Spacer(Modifier.height(8.dp))
                Text(
                    phaseLabel,
                    color = phaseColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 1.6.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        StatsCard(stats, sessionsOverride = state?.completed)
        Spacer(Modifier.height(24.dp))
        ControlsRow(
            isRunning = state?.status == TimerState.STATUS_RUNNING,
            isPaused = state?.status == TimerState.STATUS_PAUSED,
            phaseColor = phaseColor,
            onToggle = onToggle,
            onSkip = onSkip,
            onReset = onReset,
        )
    }
}

@Composable
private fun ConnectionStatusPill() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(StatusConnected),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Phone primary",
            color = StatusConnected,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TimerText(state: TimerState?, color: Color, fallbackWorkSeconds: Int) {
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    val syncTime = remember(state) { System.currentTimeMillis() }

    LaunchedEffect(state) {
        while (state?.status == TimerState.STATUS_RUNNING) {
            now.value = System.currentTimeMillis()
            delay(16)
        }
        now.value = System.currentTimeMillis()
    }

    if (state == null) {
        val mins = fallbackWorkSeconds / 60
        val secs = fallbackWorkSeconds % 60
        Text(
            String.format(Locale.US, "%02d:%02d", mins, secs),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 56.sp,
        )
        return
    }

    val remaining = computeRemaining(state, syncTime, now.value)
    val totalSeconds = remaining.toInt()
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    val cs = ((remaining - totalSeconds) * 100).toInt().coerceIn(0, 99)
    val text = String.format(Locale.US, "%02d:%02d.%02d", mins, secs, cs)
    Text(
        text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
    )
}

@Composable
private fun TimerRings(
    state: TimerState?,
    phaseColor: Color,
    completedSessions: Int,
    dailyGoal: Int,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    val syncTime = remember(state) { System.currentTimeMillis() }
    LaunchedEffect(state) {
        while (state?.status == TimerState.STATUS_RUNNING) {
            now.value = System.currentTimeMillis()
            delay(16)
        }
        now.value = System.currentTimeMillis()
    }

    val timerProgress = computeProgress(state, syncTime, now.value)
    val animatedTimer by animateFloatAsState(
        targetValue = timerProgress,
        animationSpec = tween(durationMillis = 200),
        label = "timer-progress",
    )

    val goalProgress = if (dailyGoal > 0) {
        (completedSessions.toFloat() / dailyGoal).coerceIn(0f, 1f)
    } else 0f
    val animatedGoal by animateFloatAsState(
        targetValue = goalProgress,
        animationSpec = tween(durationMillis = 400),
        label = "goal-progress",
    )

    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val sizeMin = minOf(size.width, size.height)
        val outerStroke = 6.dp.toPx()
        val innerStroke = 12.dp.toPx()
        val outerInset = outerStroke / 2f
        val innerInset = (sizeMin - 260.dp.toPx()) / 2f + innerStroke / 2f

        // Outer (goal) ring track
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(outerInset, outerInset),
            size = Size(size.width - outerInset * 2, size.height - outerInset * 2),
            style = Stroke(width = outerStroke, cap = StrokeCap.Round),
        )
        // Outer (goal) ring arc
        if (animatedGoal > 0f) {
            drawArc(
                color = Gold,
                startAngle = -90f,
                sweepAngle = 360f * animatedGoal,
                useCenter = false,
                topLeft = Offset(outerInset, outerInset),
                size = Size(size.width - outerInset * 2, size.height - outerInset * 2),
                style = Stroke(width = outerStroke, cap = StrokeCap.Round),
            )
        }

        // Inner (timer) ring track
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(innerInset, innerInset),
            size = Size(size.width - innerInset * 2, size.height - innerInset * 2),
            style = Stroke(width = innerStroke, cap = StrokeCap.Round),
        )
        // Inner (timer) ring arc
        if (animatedTimer > 0f) {
            drawArc(
                color = phaseColor,
                startAngle = -90f,
                sweepAngle = 360f * animatedTimer,
                useCenter = false,
                topLeft = Offset(innerInset, innerInset),
                size = Size(size.width - innerInset * 2, size.height - innerInset * 2),
                style = Stroke(width = innerStroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun StatsCard(stats: TimerStats, sessionsOverride: Int?) {
    val hours = stats.todayMinutes / 60
    val mins = stats.todayMinutes % 60
    val focusText = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    val sessions = sessionsOverride ?: stats.todaySessions

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(focusText, "Today", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            StatDivider()
            StatCell(
                "$sessions",
                "Sessions",
                MaterialTheme.colorScheme.secondary,
                Modifier.weight(1f),
            )
            StatDivider()
            StatCell("${stats.streak}🔥", "Streak", Gold, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun ControlsRow(
    isRunning: Boolean,
    isPaused: Boolean,
    phaseColor: Color,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onSkip,
            shape = RoundedCornerShape(50),
        ) {
            Icon(Icons.Default.SkipNext, contentDescription = "Skip", tint = phaseColor)
            Spacer(Modifier.width(6.dp))
            Text("Skip", color = phaseColor)
        }
        Spacer(Modifier.width(24.dp))
        FloatingActionButton(
            onClick = onToggle,
            containerColor = phaseColor,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isRunning) "Pause" else if (isPaused) "Resume" else "Start",
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(24.dp))
        OutlinedButton(
            onClick = onReset,
            shape = RoundedCornerShape(50),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = phaseColor)
            Spacer(Modifier.width(6.dp))
            Text("Reset", color = phaseColor)
        }
    }
}

private fun computeRemaining(state: TimerState?, syncTime: Long, nowMs: Long): Double {
    if (state == null) return 0.0
    if (state.status != TimerState.STATUS_RUNNING) return state.remaining
    val elapsed = (nowMs - syncTime) / 1000.0
    return (state.remaining - elapsed).coerceAtLeast(0.0)
}

private fun computeProgress(state: TimerState?, syncTime: Long, nowMs: Long): Float {
    if (state == null) return 0f
    val total = if (state.duration > 0) state.duration else when (state.phase) {
        TimerState.PHASE_WORK -> 1500.0
        TimerState.PHASE_SHORT -> 300.0
        TimerState.PHASE_LONG -> 900.0
        else -> 1500.0
    }
    val remaining = computeRemaining(state, syncTime, nowMs)
    return (remaining / total).toFloat().coerceIn(0f, 1f)
}
