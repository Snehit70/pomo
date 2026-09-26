package com.pomo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pomo.MainActivity
import com.pomo.R
import com.pomo.timer.TimerState
import java.util.Locale

public class NotificationHelper(private val context: Context) {
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Pomodoro Timer",
                    NotificationManager.IMPORTANCE_LOW,
                )
            channel.description = "Shows active timer status"
            notificationManager.createNotificationChannel(channel)

            // High-importance so the ring surfaces as a heads-up banner, but silent: the
            // ring's own audio/vibration is owned by StateCueEngine, not this channel.
            val ringChannel =
                NotificationChannel(
                    RING_CHANNEL_ID,
                    "Timer ring",
                    NotificationManager.IMPORTANCE_HIGH,
                )
            ringChannel.description = "Heads-up alert while a completed phase is ringing"
            ringChannel.setSound(null, null)
            ringChannel.enableVibration(false)
            notificationManager.createNotificationChannel(ringChannel)
        }
    }

    public fun buildNotification(
        state: TimerState,
        isServing: Boolean,
    ): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java)
        val pendingOpenApp =
            PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )

        var title = "Pomo"
        title +=
            if (!isServing) {
                " (API off)"
            } else {
                when (state.status) {
                    TimerState.STATUS_RUNNING -> " (Running)"
                    TimerState.STATUS_PAUSED -> " (Paused)"
                    else -> " (Ready)"
                }
            }

        val minutes = state.remaining.toInt() / 60
        val seconds = state.remaining.toInt() % 60
        val timeStr = String.format(Locale.US, "%02d:%02d", minutes, seconds)

        var phaseName = state.phase
        if (TimerState.PHASE_WORK == state.phase) {
            phaseName = "Focus"
        } else if (TimerState.PHASE_SHORT == state.phase) {
            phaseName = "Short Break"
        } else if (TimerState.PHASE_LONG == state.phase) {
            phaseName = "Long Break"
        }

        val contentText = "$timeStr - $phaseName"

        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(contentText)
                .setOngoing(true)
                .setContentIntent(pendingOpenApp)
                .setOnlyAlertOnce(true)

        val toggleIntent = Intent(context, NotificationActionReceiver::class.java)
        toggleIntent.action = "TOGGLE"
        val pendingToggle =
            PendingIntent.getBroadcast(
                context,
                1,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val skipIntent = Intent(context, NotificationActionReceiver::class.java)
        skipIntent.action = "SKIP"
        val pendingSkip =
            PendingIntent.getBroadcast(
                context,
                2,
                skipIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val toggleLabel = if (TimerState.STATUS_RUNNING == state.status) "Pause" else "Start"
        builder.addAction(android.R.drawable.ic_media_play, toggleLabel, pendingToggle)
        builder.addAction(android.R.drawable.ic_media_next, "Skip", pendingSkip)

        return builder.build()
    }

    public fun updateNotification(
        state: TimerState,
        isServing: Boolean,
    ) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state, isServing))
    }

    public fun showRingNotification(state: TimerState) {
        notificationManager.notify(RING_NOTIFICATION_ID, buildRingNotification(state))
    }

    public fun cancelRingNotification() {
        notificationManager.cancel(RING_NOTIFICATION_ID)
    }

    private fun buildRingNotification(state: TimerState): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java)
        val pendingOpenApp =
            PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )

        val phaseName =
            when (state.phase) {
                TimerState.PHASE_WORK -> "Focus"
                TimerState.PHASE_SHORT -> "Short Break"
                TimerState.PHASE_LONG -> "Long Break"
                else -> state.phase
            }

        val dismissIntent = Intent(context, NotificationActionReceiver::class.java)
        dismissIntent.action = "DISMISS"
        val pendingDismiss =
            PendingIntent.getBroadcast(
                context,
                3,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val startIntent = Intent(context, NotificationActionReceiver::class.java)
        startIntent.action = "TOGGLE"
        val pendingStart =
            PendingIntent.getBroadcast(
                context,
                1,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val skipIntent = Intent(context, NotificationActionReceiver::class.java)
        skipIntent.action = "SKIP"
        val pendingSkip =
            PendingIntent.getBroadcast(
                context,
                2,
                skipIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat.Builder(context, RING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.ring_notification_title))
            .setContentText(context.getString(R.string.ring_notification_ready, phaseName))
            .setContentIntent(pendingOpenApp)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.ring_notification_dismiss),
                pendingDismiss,
            )
            .addAction(
                android.R.drawable.ic_media_play,
                context.getString(R.string.ring_notification_start, phaseName),
                pendingStart,
            )
            .addAction(android.R.drawable.ic_media_next, context.getString(R.string.action_skip), pendingSkip)
            .build()
    }

    public companion object {
        public const val CHANNEL_ID: String = "pomodoro_channel"
        public const val RING_CHANNEL_ID: String = "pomodoro_ring_channel"
        public const val NOTIFICATION_ID: Int = 1
        public const val RING_NOTIFICATION_ID: Int = 2
    }
}
