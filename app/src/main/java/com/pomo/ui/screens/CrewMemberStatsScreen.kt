package com.pomo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pomo.achievements.AchievementEvaluator
import com.pomo.crew.CrewBoardRow
import com.pomo.crew.CrewValidation
import com.pomo.crew.hasFullStats
import com.pomo.crew.toStatsSnapshot
import com.pomo.ui.theme.PomoTokens

/**
 * A crew member's stats, drawn with the very same screen as your own — their snapshot rebuilds
 * into a real `StatsSnapshot`. Only the header differs, and it differs loudly: a screenshot of
 * this page must never read as yours.
 */
@Composable
internal fun CrewMemberStatsScreen(
    row: CrewBoardRow,
    onBack: () -> Unit,
) {
    val snapshot = remember(row) { row.toStatsSnapshot() }
    val full = remember(row) { row.hasFullStats() }
    // One highest record per track. Their snapshot can prove what they reached, never what they did not.
    val achievementRecords = remember(row) { AchievementEvaluator.highlights(snapshot) }

    StatsContent(
        snapshot = snapshot,
        tagBreakdown = row.stats?.tagBreakdown,
        emptyBody = "${row.displayName} hasn't shared any focus yet.",
        header = { MemberStatsHeader(row = row, onBack = onBack) },
        rhythmTitle = "When ${row.displayName} focuses",
        // Older builds share daily totals but no hour buckets; an empty 24-hour chart would
        // read as "never focuses" rather than "didn't tell us".
        rhythmSection = full,
        // Members share daily totals, never session times, so there is no hour-by-hour today.
        showTodayRange = false,
        footer = {
            if (achievementRecords.isNotEmpty()) {
                PeerAchievementsSection(displayName = row.displayName, earned = achievementRecords)
                Spacer(Modifier.height(16.dp))
            }
            MemberStatsFooter(full = full)
        },
    )
}

@Composable
private fun MemberStatsHeader(
    row: CrewBoardRow,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to crew",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${row.displayName}'s stats",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            OwnerChip(row = row)
        }
    }
}

/** Names whose numbers these are, in the member's own accent, at the top of every screenshot. */
@Composable
private fun OwnerChip(row: CrewBoardRow) {
    val accent = PomoTokens.colors.accent
    val fingerprint = row.identityPublicKey.takeLast(FINGERPRINT_CHARS).uppercase()
    val label = if (row.isSelf) "You · $fingerprint" else "Crew member · not you · $fingerprint"
    Text(
        text = label,
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(accent.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = accent,
    )
}

@Composable
private fun MemberStatsFooter(full: Boolean) {
    val text =
        if (full) {
            "Shared by them through the crew relay. Records use the all-time fields their build shared; " +
                "the charts cover the last ${CrewValidation.MAX_HISTORY_DAYS} days."
        } else {
            "They're on an older build that shares only the last ${CrewValidation.MAX_DAILY_AGGREGATES} " +
                "days of daily totals, so some of this page is thinner than yours."
        }
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = PomoTokens.colors.onSurfaceFaint,
    )
}

private const val FINGERPRINT_CHARS: Int = 4
