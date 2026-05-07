package com.pomoremote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

public class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action == null) return

        val serviceIntent = Intent(context, PomodoroService::class.java)
        serviceIntent.action = intent.action

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
