package com.pomoremote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pomoremote.MainActivity
import com.pomoremote.R
import com.pomoremote.timer.TimerState
import java.util.Locale

class NotificationHelper(private val context: Context) {
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pomodoro Timer",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Shows active timer status"
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(state: TimerState, isServing: Boolean): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java)
        val pendingOpenApp = PendingIntent.getActivity(
            context, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
        )

        var title = "Pomo Remote"
        title += if (!isServing) {
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
        if (TimerState.PHASE_WORK == state.phase) phaseName = "Focus"
        else if (TimerState.PHASE_SHORT == state.phase) phaseName = "Short Break"
        else if (TimerState.PHASE_LONG == state.phase) phaseName = "Long Break"

        val contentText = "$timeStr - $phaseName"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .setOnlyAlertOnce(true)

        val toggleIntent = Intent(context, NotificationActionReceiver::class.java)
        toggleIntent.action = "TOGGLE"
        val pendingToggle = PendingIntent.getBroadcast(
            context, 1, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val skipIntent = Intent(context, NotificationActionReceiver::class.java)
        skipIntent.action = "SKIP"
        val pendingSkip = PendingIntent.getBroadcast(
            context, 2, skipIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleLabel = if (TimerState.STATUS_RUNNING == state.status) "Pause" else "Start"
        builder.addAction(android.R.drawable.ic_media_play, toggleLabel, pendingToggle)
        builder.addAction(android.R.drawable.ic_media_next, "Skip", pendingSkip)

        return builder.build()
    }

    fun updateNotification(state: TimerState, isServing: Boolean) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state, isServing))
    }

    companion object {
        const val CHANNEL_ID = "pomodoro_channel"
        const val NOTIFICATION_ID = 1
    }
}
