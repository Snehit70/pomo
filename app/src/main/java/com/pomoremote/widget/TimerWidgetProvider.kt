package com.pomoremote.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pomoremote.MainActivity
import com.pomoremote.R
import com.pomoremote.service.PomodoroService
import com.pomoremote.timer.TimerState
import java.util.Locale

public class TimerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, null)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_TOGGLE_TIMER) {
            val serviceIntent = Intent(context, PomodoroService::class.java).apply {
                action = "TOGGLE"
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    public companion object {
        public const val ACTION_TOGGLE_TIMER: String = "com.pomoremote.widget.ACTION_TOGGLE_TIMER"

        public fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: TimerState?,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_timer)

            if (state != null) {
                val minutes = state.remaining.toInt() / 60
                val seconds = state.remaining.toInt() % 60
                views.setTextViewText(R.id.widget_timer, String.format(Locale.US, "%02d:%02d", minutes, seconds))

                val colorRes = when (state.phase) {
                    TimerState.PHASE_WORK -> R.color.md_theme_primary
                    TimerState.PHASE_SHORT, TimerState.PHASE_LONG -> R.color.md_theme_secondary
                    else -> R.color.md_theme_primary
                }
                val color = context.getColor(colorRes)

                views.setTextColor(R.id.widget_timer, color)
                views.setTextColor(R.id.widget_phase, color)

                val phaseText = when (state.phase) {
                    TimerState.PHASE_WORK -> "FOCUS"
                    TimerState.PHASE_SHORT -> "SHORT BREAK"
                    TimerState.PHASE_LONG -> "LONG BREAK"
                    else -> state.phase.uppercase(Locale.US)
                }
                views.setTextViewText(R.id.widget_phase, phaseText)

                val iconRes = if (TimerState.STATUS_RUNNING == state.status) {
                    R.drawable.ic_pause
                } else {
                    R.drawable.ic_play
                }
                views.setImageViewResource(R.id.widget_btn_action, iconRes)

                views.setInt(R.id.widget_btn_action, "setColorFilter", color)
            } else {
                views.setTextViewText(R.id.widget_timer, "--:--")
                views.setTextViewText(R.id.widget_phase, "POMO")
            }

            val appIntent = Intent(context, MainActivity::class.java)
            val pendingAppIntent = PendingIntent.getActivity(
                context, 0, appIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingAppIntent)

            val toggleIntent = Intent(context, TimerWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_TIMER
            }
            val pendingToggleIntent = PendingIntent.getBroadcast(
                context, 0, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_btn_action, pendingToggleIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        public fun updateAllWidgets(context: Context, state: TimerState) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, TimerWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, state)
            }
        }
    }
}
