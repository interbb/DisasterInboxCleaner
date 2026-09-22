package com.interbb.disasterinboxcleaner

import android.app.Notification
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class DisasterNotificationListenerService : NotificationListenerService() {
    private lateinit var monitorPreferences: MonitorPreferences
    private lateinit var adPreferences: AdMonitorPreferences
    private lateinit var inboxCleaner: EmergencyInboxCleaner
    private lateinit var adCleaner: AdInboxCleaner
    private lateinit var worker: ScheduledExecutorService
    private var inboxObserver: ContentObserver? = null
    private var adObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        monitorPreferences = MonitorPreferences(this)
        adPreferences = AdMonitorPreferences(this)
        inboxCleaner = EmergencyInboxCleaner(this)
        adCleaner = AdInboxCleaner(this, AdCleanupHistory.default(this))
        worker = Executors.newSingleThreadScheduledExecutor()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        MonitorRuntime.setListenerConnected(true)
        registerInboxObserverIfPossible()
        registerAdObserverIfPossible()
        scheduleCleanup(0L)
        scheduleAdCleanup(0L)
    }

    override fun onListenerDisconnected() {
        MonitorRuntime.setListenerConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        when (notification.packageName) {
            CELL_BROADCAST_PACKAGE -> {
                monitorPreferences.recordEvent()
                if (!monitorPreferences.isEnabled()) return

                registerInboxObserverIfPossible()
                RETRY_DELAYS_MS.forEach(::scheduleCleanup)
            }
            SAMSUNG_MESSAGES_PACKAGE -> {
                if (!adPreferences.isEnabled()) return

                registerAdObserverIfPossible()
                RETRY_DELAYS_MS.forEach(::scheduleAdCleanup)
            }
        }
    }

    private fun registerInboxObserverIfPossible() {
        if (inboxObserver != null || !PermissionState.canReadSms(this)) return

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (monitorPreferences.isEnabled()) {
                    scheduleCleanup(CONTENT_OBSERVER_DELAY_MS)
                }
            }
        }

        try {
            // content://sms, not content://sms/inbox: registering on the child misses a change
            // announced on the parent, and the provider does announce there. That left the disaster
            // path with only its notification trigger and no working inbox-change safety net, while
            // the ad path (which watches the parent) had one. Deletes already target content://sms.
            contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                observer,
            )
            inboxObserver = observer
        } catch (_: SecurityException) {
            monitorPreferences.recordError(MonitorError.SMS_READ_PERMISSION_REQUIRED)
        }
    }

    /** Watches content://sms (all boxes) so ad cleanup runs whichever URI the default app writes to. */
    private fun registerAdObserverIfPossible() {
        if (adObserver != null || !PermissionState.canReadSms(this)) return

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (adPreferences.isEnabled()) {
                    scheduleAdCleanup(CONTENT_OBSERVER_DELAY_MS)
                }
            }
        }

        try {
            contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                observer,
            )
            adObserver = observer
        } catch (_: SecurityException) {
            adPreferences.recordError(MonitorError.SMS_READ_PERMISSION_REQUIRED)
        }
    }

    private fun scheduleCleanup(delayMs: Long) {
        if (worker.isShutdown) return
        worker.schedule(::performCleanup, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleAdCleanup(delayMs: Long) {
        if (worker.isShutdown) return
        worker.schedule(::performAdCleanup, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun performCleanup() {
        val state = monitorPreferences.snapshot()
        if (!state.enabled) return

        when (val result = inboxCleaner.deleteCopiesSince(state.monitoringStartedAt)) {
            is CleanupResult.Success -> {
                if (result.deleted > 0) {
                    monitorPreferences.recordCleanup(result.deleted)
                } else {
                    monitorPreferences.recordReady()
                }
            }
            is CleanupResult.Failure -> monitorPreferences.recordError(result.error)
        }
    }

    private fun performAdCleanup() {
        val state = adPreferences.snapshot()
        if (!state.enabled) return

        when (val result = adCleaner.cleanSince(state.enabledAt)) {
            is AdCleanupResult.Success -> {
                if (result.deleted > 0) {
                    adPreferences.recordCleanup(result.deleted)
                    // Only close the notification for a message this app actually removed. A pass
                    // that deleted nothing means the ad is still in the inbox — an MMS ad, or one
                    // that never matched — and closing its notification would hide a message the
                    // user still has. A later retry that does delete will close it then.
                    cancelAdNotifications(sinceMillis = state.enabledAt)
                } else {
                    adPreferences.recordReady()
                }
            }
            is AdCleanupResult.Failure -> adPreferences.recordError(result.error)
        }
    }

    /**
     * Best effort: closes Samsung Messages ad notifications posted since the feature was enabled,
     * after a successful cleanup pass (the matching row is gone or was never in scope).
     */
    private fun cancelAdNotifications(sinceMillis: Long) {
        val active = try {
            activeNotifications
        } catch (_: SecurityException) {
            return
        } ?: return

        active
            .filter {
                it.packageName == SAMSUNG_MESSAGES_PACKAGE &&
                    it.postTime >= sinceMillis &&
                    notificationLooksLikeAd(it)
            }
            .forEach { notification -> runCatching { cancelNotification(notification.key) } }
    }

    private fun notificationLooksLikeAd(notification: StatusBarNotification): Boolean {
        val extras = notification.notification.extras ?: return false
        val texts = mutableListOf<CharSequence?>()
        texts += extras.getCharSequence(Notification.EXTRA_TEXT)
        texts += extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { texts += it }
        return texts.any { AdSmsPolicy.isAd(it?.toString()) }
    }

    override fun onDestroy() {
        inboxObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        inboxObserver = null
        adObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        adObserver = null
        worker.shutdownNow()
        MonitorRuntime.setListenerConnected(false)
        super.onDestroy()
    }

    private companion object {
        const val CELL_BROADCAST_PACKAGE = "com.google.android.cellbroadcastreceiver"
        const val SAMSUNG_MESSAGES_PACKAGE = "com.samsung.android.messaging"
        const val CONTENT_OBSERVER_DELAY_MS = 300L
        val RETRY_DELAYS_MS = longArrayOf(400L, 1_500L, 4_000L)
    }
}
