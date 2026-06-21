package com.pomo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pomo.stats.HourRhythm
import com.pomo.ui.theme.PomoTokens

/**
 * 24-bar hour-of-day chart. Bars rise from a baseline; peak hour painted in signal red,
 * the rest in muted neutral. Axis ticks at 6 / 12 / 18 only.
 */
@Composable
public fun HourBarChart24(
    rhythm: HourRhythm,
    modifier: Modifier = Modifier,
) {
    val signal = PomoTokens.colors.accent
    val muted = PomoTokens.colors.onSurfaceMuted
    val faint = PomoTokens.colors.onSurfaceFaint
    val outline = PomoTokens.colors.outline
    val max = (rhythm.buckets.maxOrNull() ?: 0).coerceAtLeast(1)
    val peak = rhythm.peakHour
    val description = rhythmCaption(rhythm)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Hour of day chart. $description" },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val baseline = h - 1f
                val barCount = 24
                val gapPx = 4f
                val barW = (w - gapPx * (barCount - 1)) / barCount
                val minBarH = 2f
                val maxBarH = h - 4f

                // Baseline hairline.
                drawRect(
                    color = outline,
                    topLeft = Offset(0f, baseline),
                    size = Size(w, 1f),
                )

                for (hr in 0 until barCount) {
                    val frac = rhythm.buckets[hr].toFloat() / max
                    val barH = if (rhythm.buckets[hr] == 0) minBarH else (minBarH + frac * (maxBarH - minBarH))
                    val left = hr * (barW + gapPx)
                    val top = baseline - barH
                    val color = when {
                        rhythm.buckets[hr] == 0 -> outline
                        peak == hr -> signal
                        else -> muted
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left, top),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(2f, 2f),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // Axis: 6 / 12 / 18 only, plus 0 and 24 implied by edges.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("0", "6", "12", "18", "24").forEach { tick ->
                Text(
                    text = tick,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.6.sp),
                    color = faint,
                )
            }
        }
    }
}

internal fun rhythmCaption(rhythm: HourRhythm): String {
    // Always name the peak hour when there is one — a visible peak bar should never be
    // captioned "scattered". The period is derived straight from the peak hour.
    val peak = rhythm.peakHour ?: return "Not enough data yet"
    val hourLabel = formatHour(peak)
    val period = when (peak) {
        in 5..11 -> "morning focus"
        in 12..16 -> "afternoon focus"
        in 17..20 -> "evening focus"
        else -> "late-night focus"
    }
    return "Peak $hourLabel — $period"
}

private fun formatHour(h: Int): String {
    val period = if (h < 12) "am" else "pm"
    val twelve = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$twelve$period"
}

// Suppress unused-param warning placeholder for backward source compat if anything still
// imports a Color from this file historically.
@Suppress("unused")
private object Hidden {
    @Composable
    fun placeholder(modifier: Modifier = Modifier) {
        Text("", textAlign = TextAlign.Center, modifier = modifier.width(0.dp))
    }
}
