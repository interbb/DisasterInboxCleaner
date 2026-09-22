package com.interbb.disasterinboxcleaner

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!MonitorPreferences(context).isEnabled() && !AdMonitorPreferences(context).isEnabled()) return
        if (!PermissionState.hasNotificationAccess(context)) return

        NotificationListenerService.requestRebind(
            ComponentName(context, DisasterNotificationListenerService::class.java),
        )
    }
}
