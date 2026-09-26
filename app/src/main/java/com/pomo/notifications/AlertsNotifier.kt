package com.pomo.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pomo.MainActivity
import com.pomo.R
import com.pomo.achievements.Achievement
import com.pomo.achievements.AchievementAxis

/**
 * The app's two quiet, non-timer notifications: an earned achievement and an available update.
 * Both ride [NotificationManager.IMPORTANCE_LOW] silent channels — they are worth surfacing in the
 * shade but never worth a sound, a vibration, or a heads-up banner. The loud timer notifications
 * are a separate concern owned by [com.pomo.service.NotificationHelper].
 */
public class AlertsNotifier(private val context: Context) {
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val achievements =
            NotificationChannel(
                ACHIEVEMENT_CHANNEL_ID,
                "Achievements",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "A quiet note when you earn an achievement"
                setSound(null, null)
                enableVibration(false)
            }
        val updates =
            NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "A quiet note when a newer version of Pomo is available"
                setSound(null, null)
                enableVibration(false)
            }
        notificationManager.createNotificationChannel(achievements)
        notificationManager.createNotificationChannel(updates)
    }

    /** One quiet note for every record crossed by the same history commit. */
    public fun notifyAchievements(achievements: List<Achievement>) {
        if (achievements.isEmpty()) return
        val title = if (achievements.size == 1) "Achievement earned" else "${achievements.size} records updated"
        val content =
            if (achievements.size == 1) {
                achievements.single().let { "${it.title} · ${it.fact}" }
            } else {
                achievements.joinToString(" · ") { "${it.badge} ${it.axis.notificationLabel()}" }
            }
        val notification =
            NotificationCompat.Builder(context, ACHIEVEMENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setContentIntent(openApp(NAV_TARGET_ACHIEVEMENTS, requestCode = ACHIEVEMENT_REQUEST_CODE))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        notificationManager.notify(ACHIEVEMENT_NOTIFICATION_ID, notification)
    }

    /** A quiet note that [versionName] is available. Tapping it opens the Settings update section. */
    public fun notifyUpdate(versionName: String) {
        val notification =
            NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Update available")
                .setContentText("Version $versionName is ready to install.")
                .setContentIntent(openApp(NAV_TARGET_UPDATE, requestCode = UPDATE_REQUEST_CODE))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
    }

    private fun openApp(
        navTarget: String,
        requestCode: Int,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NAV_TARGET, navTarget)
            }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    public companion object {
        public const val ACHIEVEMENT_CHANNEL_ID: String = "pomo_achievement_channel"
        public const val UPDATE_CHANNEL_ID: String = "pomo_update_channel"

        /** Intent extra carried by a tapped alert, naming the screen [MainActivity] should open. */
        public const val EXTRA_NAV_TARGET: String = "com.pomo.extra.NAV_TARGET"
        public const val NAV_TARGET_ACHIEVEMENTS: String = "achievements"
        public const val NAV_TARGET_UPDATE: String = "update"

        // Kept clear of NotificationHelper's ids (1 = foreground, 2 = ring).
        private const val ACHIEVEMENT_NOTIFICATION_ID: Int = 1000
        private const val UPDATE_NOTIFICATION_ID: Int = 1100
        private const val ACHIEVEMENT_REQUEST_CODE: Int = 2000
        private const val UPDATE_REQUEST_CODE: Int = 2100
    }
}

private fun AchievementAxis.notificationLabel(): String =
    when (this) {
        AchievementAxis.Focus -> "Focus"
        AchievementAxis.ActiveDays -> "active"
        AchievementAxis.Streak -> "streak"
        AchievementAxis.BestDay -> "Best day"
        AchievementAxis.Milestone -> "Entry"
    }
