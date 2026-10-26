package com.pomo.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pomo.R
import com.pomo.timer.TimerState
import com.pomo.ui.components.StatTile
import com.pomo.ui.theme.PomoTokens
import com.pomo.ui.theme.TimerHeroStyle
import com.pomo.ui.theme.TimerMsStyle
import kotlinx.coroutines.android.awaitFrame
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
    onAddTime: (Int) -> Unit,
    onStatsClick: () -> Unit,
    currentTag: String? = null,
    currentTagColor: Color? = null,
    extensionAvailableSeconds: Int = 0,
    onTagSelected: (String?) -> Unit = {},
    onUndoExtension: () -> Unit = {},
    availableTags: List<String> = emptyList(),
) {
    val colors = PomoTokens.colors
    val isRunning = state?.status == TimerState.STATUS_RUNNING
    val isPaused = state?.status == TimerState.STATUS_PAUSED
    val phaseLabel =
        when (state?.phase) {
            TimerState.PHASE_WORK -> "FOCUS"
            TimerState.PHASE_SHORT -> "SHORT BREAK"
            TimerState.PHASE_LONG -> "LONG BREAK"
            else -> "FOCUS"
        }

    val isWorkRunning =
        state?.phase == TimerState.PHASE_WORK &&
            (state.status == TimerState.STATUS_RUNNING || state.status == TimerState.STATUS_PAUSED)

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(colors.bg),
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            TimerLandscapeLayout(
                state = state,
                stats = stats,
                dailyGoal = dailyGoal,
                fallbackWorkSeconds = fallbackWorkSeconds,
                phaseLabel = phaseLabel,
                isRunning = isRunning,
                isPaused = isPaused,
                isWorkRunning = isWorkRunning,
                currentTag = currentTag,
                currentTagColor = currentTagColor,
                availableTags = availableTags,
                extensionAvailableSeconds = extensionAvailableSeconds,
                onTagSelected = onTagSelected,
                onStatsClick = onStatsClick,
                onAddTime = onAddTime,
                onUndoExtension = onUndoExtension,
                onToggle = onToggle,
                onSkip = onSkip,
                onReset = onReset,
            )
        } else {
            TimerPortraitLayout(
                state = state,
                stats = stats,
                dailyGoal = dailyGoal,
                fallbackWorkSeconds = fallbackWorkSeconds,
                phaseLabel = phaseLabel,
                isRunning = isRunning,
                isPaused = isPaused,
                isWorkRunning = isWorkRunning,
                currentTag = currentTag,
                currentTagColor = currentTagColor,
                availableTags = availableTags,
                extensionAvailableSeconds = extensionAvailableSeconds,
                onTagSelected = onTagSelected,
                onStatsClick = onStatsClick,
                onAddTime = onAddTime,
                onUndoExtension = onUndoExtension,
                onToggle = onToggle,
                onSkip = onSkip,
                onReset = onReset,
            )
        }
    }
}

@Composable
private fun TimerPortraitLayout(
    state: TimerState?,
    stats: TimerStats,
    dailyGoal: Int,
    fallbackWorkSeconds: Int,
    phaseLabel: String,
    isRunning: Boolean,
    isPaused: Boolean,
    isWorkRunning: Boolean,
    currentTag: String?,
    currentTagColor: Color?,
    availableTags: List<String>,
    extensionAvailableSeconds: Int,
    onTagSelected: (String?) -> Unit,
    onStatsClick: () -> Unit,
    onAddTime: (Int) -> Unit,
    onUndoExtension: () -> Unit,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TimerHeader()
        Spacer(Modifier.height(24.dp))
        Text(
            text = phaseLabel,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.14.sp.value.sp),
            color = PomoTokens.colors.onSurfaceMuted,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        TimerReadout(state = state, fallbackWorkSeconds = fallbackWorkSeconds)
        Spacer(Modifier.height(10.dp))
        LiveStateIndicator(isRunning = isRunning, isPaused = isPaused)
        Spacer(Modifier.height(28.dp))
        LinearProgress(
            progress = derivedProgress(state, fallbackWorkSeconds),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        LaunchPips(
            completed = state?.completed ?: 0,
            goal = dailyGoal.coerceAtLeast(1),
            isRunning = isRunning,
        )
        Spacer(Modifier.height(24.dp))
        if (availableTags.isNotEmpty()) {
            TagChip(
                tag = currentTag,
                tagColor = currentTagColor,
                onClick = { if (!isWorkRunning) onTagSelected(currentTag) },
                enabled = !isWorkRunning,
            )
            Spacer(Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        StatsStrip(stats, sessionsOverride = state?.completed, onClick = onStatsClick)
        Spacer(Modifier.height(12.dp))
        if (isRunning || extensionAvailableSeconds > 0) {
            AddTimeStepper(onAddTime, extensionAvailableSeconds, isRunning, onUndoExtension)
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(20.dp))
        }
        ControlsRow(isRunning, isPaused, onToggle, onSkip, onReset)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TimerLandscapeLayout(
    state: TimerState?,
    stats: TimerStats,
    dailyGoal: Int,
    fallbackWorkSeconds: Int,
    phaseLabel: String,
    isRunning: Boolean,
    isPaused: Boolean,
    isWorkRunning: Boolean,
    currentTag: String?,
    currentTagColor: Color?,
    availableTags: List<String>,
    extensionAvailableSeconds: Int,
    onTagSelected: (String?) -> Unit,
    onStatsClick: () -> Unit,
    onAddTime: (Int) -> Unit,
    onUndoExtension: () -> Unit,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TimerHeader()
            Spacer(Modifier.height(8.dp))
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.14.sp.value.sp),
                color = PomoTokens.colors.onSurfaceMuted,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            TimerReadout(state = state, fallbackWorkSeconds = fallbackWorkSeconds, compact = true)
            Spacer(Modifier.height(6.dp))
            LiveStateIndicator(isRunning = isRunning, isPaused = isPaused)
            Spacer(Modifier.height(12.dp))
            LinearProgress(
                progress = derivedProgress(state, fallbackWorkSeconds),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            LaunchPips(
                completed = state?.completed ?: 0,
                goal = dailyGoal.coerceAtLeast(1),
                isRunning = isRunning,
            )
            if (availableTags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                TagChip(
                    tag = currentTag,
                    tagColor = currentTagColor,
                    onClick = { if (!isWorkRunning) onTagSelected(currentTag) },
                    enabled = !isWorkRunning,
                )
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(
            modifier = Modifier.weight(1.2f).fillMaxHeight(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatsStrip(stats, sessionsOverride = state?.completed, onClick = onStatsClick)
            if (isRunning || extensionAvailableSeconds > 0) {
                Spacer(Modifier.height(8.dp))
                AddTimeStepper(onAddTime, extensionAvailableSeconds, isRunning, onUndoExtension)
            }
            Spacer(Modifier.height(8.dp))
            ControlsRow(isRunning, isPaused, onToggle, onSkip, onReset)
        }
    }
}

@Composable
private fun AddTimeStepper(
    onAddTime: (Int) -> Unit,
    extensionAvailableSeconds: Int,
    canExtend: Boolean,
    onUndoExtension: () -> Unit,
) {
    val colors = PomoTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "EXTEND",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceMuted,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (extensionAvailableSeconds > 0) {
                AddTimeButton(
                    label = "UNDO ${extensionAvailableSeconds / 60}m",
                    description = "Undo added time",
                    onClick = onUndoExtension,
                )
            }
            if (canExtend) {
                AddTimeButton(label = "+1", description = "Add 1 minute") {
                    onAddTime(60)
                }
                AddTimeButton(label = "+5", description = "Add 5 minutes") {
                    onAddTime(300)
                }
            }
        }
    }
}

@Composable
private fun AddTimeButton(
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val colors = PomoTokens.colors
    Box(
        modifier =
            Modifier
                .semantics {
                    contentDescription = description
                    role = Role.Button
                }
                .clip(RoundedCornerShape(10.dp))
                .background(colors.accent.copy(alpha = 0.14f))
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.accent,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TimerHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "POMO",
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun TimerReadout(
    state: TimerState?,
    fallbackWorkSeconds: Int,
    compact: Boolean = false,
) {
    // ~60 fps tick loop while running, so the ms field rolls smoothly.
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    val syncTime = remember(state) { System.currentTimeMillis() }
    LaunchedEffect(state) {
        while (state?.status == TimerState.STATUS_RUNNING) {
            awaitFrame()
            now.value = System.currentTimeMillis()
        }
        now.value = System.currentTimeMillis()
    }
    val seconds =
        if (state == null) {
            fallbackWorkSeconds.toDouble()
        } else {
            computeRemaining(state, syncTime, now.value)
        }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = formatClock(seconds),
            color = PomoTokens.colors.onSurface,
            style = if (compact) TimerHeroStyle.copy(fontSize = 72.sp) else TimerHeroStyle,
        )
        // Ms digits as a vertical telemetry column beside the hero — the instrument-panel
        // detail the user asked for. Width-constrained so the hero stays optically centered.
        if (state != null) {
            Spacer(Modifier.width(6.dp))
            Column(
                modifier =
                    Modifier
                        .width(if (compact) 24.dp else 32.dp)
                        .padding(top = if (compact) 6.dp else 8.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                formatMs(seconds).forEach { ch ->
                    Text(
                        text = ch.toString(),
                        color = PomoTokens.colors.onSurfaceFaint,
                        style = if (compact) TimerMsStyle.copy(fontSize = 24.sp) else TimerMsStyle,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveStateIndicator(
    isRunning: Boolean,
    isPaused: Boolean,
) {
    val signal = PomoTokens.colors.accent
    val muted = PomoTokens.colors.onSurfaceMuted
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            isRunning -> {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(signal),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = signal,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            isPaused -> {
                Text(
                    text = "[ PAUSED ]",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            else -> {
                Text(
                    text = "READY",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val track = PomoTokens.colors.outline
    val fill = PomoTokens.colors.accent
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(120),
        label = "linear-progress",
    )
    Box(
        modifier =
            modifier
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(track),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(animated)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(fill),
        )
    }
}

@Composable
private fun LaunchPips(
    completed: Int,
    goal: Int,
    isRunning: Boolean,
) {
    val signal = PomoTokens.colors.accent
    val done = PomoTokens.colors.onSurfaceMuted
    val empty = PomoTokens.colors.outline
    val total = goal.coerceIn(1, 12)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until total) {
            val color =
                when {
                    i < completed -> done
                    i == completed && isRunning -> signal
                    else -> empty
                }
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(color),
            )
        }
    }
}

@Composable
private fun StatsStrip(
    stats: TimerStats,
    sessionsOverride: Int?,
    onClick: () -> Unit,
) {
    val hours = stats.todayMinutes / 60
    val mins = stats.todayMinutes % 60
    val focusText = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    val sessions = sessionsOverride ?: stats.todaySessions
    val colors = PomoTokens.colors

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatTile(focusText, "TODAY", Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally)
        StatDivider()
        StatTile("$sessions", "SESSIONS", Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally)
        StatDivider()
        StatTile(
            "${stats.streak}",
            "STREAK",
            Modifier.weight(1f),
            accentColor = if (stats.streak > 0) colors.accent else colors.onSurface,
            horizontalAlignment = Alignment.CenterHorizontally,
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier =
            Modifier
                .width(1.dp)
                .height(36.dp)
                .background(PomoTokens.colors.outline),
    )
}

@Composable
private fun TagChip(
    tag: String?,
    tagColor: Color?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = PomoTokens.colors
    val displayText = tag ?: stringResource(R.string.session_tags_untagged)
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) colors.surfaceElevated else colors.surfaceElevated.copy(alpha = 0.4f))
                .then(
                    if (enabled) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (tagColor != null) {
            Box(
                modifier = Modifier.size(8.dp).background(tagColor, CircleShape),
            )
        }
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelMedium,
            color = if (tag != null) colors.onSurface else colors.onSurfaceMuted,
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = if (enabled) colors.accent else colors.accent.copy(alpha = 0.3f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlsRow(
    isRunning: Boolean,
    isPaused: Boolean,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    onReset: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val colors = PomoTokens.colors
    val resetInteractionSource = remember { MutableInteractionSource() }
    val isResetPressed by resetInteractionSource.collectIsPressedAsState()
    val resetFill by animateFloatAsState(
        targetValue = if (isResetPressed) 1f else 0f,
        animationSpec = tween(600),
        label = "reset-hold",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .semantics {
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(label = "Reset timer") {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onReset()
                                        true
                                    },
                                )
                        }
                        .clip(CircleShape)
                        .combinedClickable(
                            interactionSource = resetInteractionSource,
                            indication = null,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onReset()
                            },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onReset()
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (resetFill > 0f) {
                        Box(
                            modifier =
                                Modifier
                                    .size((24 + 18 * resetFill).dp)
                                    .clip(CircleShape)
                                    .background(colors.accent.copy(alpha = 0.18f * resetFill)),
                        )
                    }
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Reset timer",
                        tint = colors.onSurfaceMuted,
                    )
                }
            }
            Text(
                text = stringResource(R.string.action_reset).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(26.dp))
        FloatingActionButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggle()
            },
            containerColor = colors.accent,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription =
                    if (isRunning) {
                        "Pause"
                    } else if (isPaused) {
                        "Resume"
                    } else {
                        "Start"
                    },
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(26.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSkip()
                },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Skip", tint = colors.onSurfaceMuted)
            }
            Text(
                text = stringResource(R.string.action_skip).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun formatClock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0)
    val whole = total.toInt()
    val mins = whole / 60
    val secs = whole % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

private fun formatMs(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0)
    val frac = total - total.toInt()
    val ms = (frac * 1000).toInt().coerceIn(0, 999)
    return String.format(Locale.US, "%03d", ms)
}

private fun derivedProgress(
    state: TimerState?,
    fallbackWorkSeconds: Int,
): Float {
    if (state == null) return 0f
    val total = if (state.duration > 0) state.duration else fallbackWorkSeconds.toDouble()
    if (total <= 0) return 0f
    val remaining = state.remaining
    val elapsed = (total - remaining).coerceAtLeast(0.0)
    return (elapsed / total).toFloat().coerceIn(0f, 1f)
}

private fun computeRemaining(
    state: TimerState?,
    syncTime: Long,
    nowMs: Long,
): Double {
    if (state == null) return 0.0
    if (state.status != TimerState.STATUS_RUNNING) return state.remaining
    val elapsed = (nowMs - syncTime) / 1000.0
    return (state.remaining - elapsed).coerceAtLeast(0.0)
}
