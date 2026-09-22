package com.interbb.disasterinboxcleaner

import android.content.Context
import android.content.SharedPreferences

data class StoredMonitorState(
    val onboardingComplete: Boolean,
    val enabled: Boolean,
    val monitoringStartedAt: Long,
    val deletedCount: Long,
    val lastDeletedAt: Long,
    val lastEventAt: Long,
    val lastError: MonitorError?,
)

enum class MonitorError {
    SMS_READ_PERMISSION_REQUIRED,
    SMS_DELETE_PERMISSION_REQUIRED,
    SMS_PROVIDER_REJECTED,
}

class MonitorPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): StoredMonitorState = StoredMonitorState(
        onboardingComplete = preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false),
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        monitoringStartedAt = preferences.getLong(KEY_MONITORING_STARTED_AT, 0L),
        deletedCount = preferences.getLong(KEY_DELETED_COUNT, 0L),
        lastDeletedAt = preferences.getLong(KEY_LAST_DELETED_AT, 0L),
        lastEventAt = preferences.getLong(KEY_LAST_EVENT_AT, 0L),
        lastError = preferences.getString(KEY_LAST_ERROR, null)?.let { stored ->
            MonitorError.entries.firstOrNull { it.name == stored }
        },
    )

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun completeOnboarding() {
        preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    fun setEnabled(enabled: Boolean) {
        val editor = preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .remove(KEY_LAST_ERROR)
        if (enabled) {
            editor.putLong(KEY_MONITORING_STARTED_AT, System.currentTimeMillis())
        }
        editor.apply()
    }

    fun recordEvent() {
        preferences.edit()
            .putLong(KEY_LAST_EVENT_AT, System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun recordCleanup(deleted: Int) {
        if (deleted <= 0) return
        preferences.edit()
            .putLong(KEY_DELETED_COUNT, preferences.getLong(KEY_DELETED_COUNT, 0L) + deleted)
            .putLong(KEY_LAST_DELETED_AT, System.currentTimeMillis())
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordReady() {
        preferences.edit().remove(KEY_LAST_ERROR).apply()
    }

    fun recordError(error: MonitorError) {
        preferences.edit().putString(KEY_LAST_ERROR, error.name).apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val PREFS_NAME = "monitor_state"
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        const val KEY_ENABLED = "enabled"
        const val KEY_MONITORING_STARTED_AT = "monitoring_started_at"
        const val KEY_DELETED_COUNT = "deleted_count"
        const val KEY_LAST_DELETED_AT = "last_deleted_at"
        const val KEY_LAST_EVENT_AT = "last_event_at"
        const val KEY_LAST_ERROR = "last_error"
    }
}
