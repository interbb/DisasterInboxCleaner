package com.interbb.disasterinboxcleaner

import android.content.Context
import android.content.SharedPreferences

data class StoredAdMonitorState(
    val enabled: Boolean,
    val enabledAt: Long,
    val deletedCount: Long,
    val lastDeletedAt: Long,
    val lastError: MonitorError?,
)

/** State of the ad-SMS cleanup feature. Separate prefs file from the disaster-alert monitor. */
class AdMonitorPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): StoredAdMonitorState = StoredAdMonitorState(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        enabledAt = preferences.getLong(KEY_ENABLED_AT, 0L),
        deletedCount = preferences.getLong(KEY_DELETED_COUNT, 0L),
        lastDeletedAt = preferences.getLong(KEY_LAST_DELETED_AT, 0L),
        lastError = preferences.getString(KEY_LAST_ERROR, null)?.let { stored ->
            MonitorError.entries.firstOrNull { it.name == stored }
        },
    )

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        val editor = preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .remove(KEY_LAST_ERROR)
        if (enabled) {
            editor.putLong(KEY_ENABLED_AT, System.currentTimeMillis())
        }
        editor.apply()
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
        const val PREFS_NAME = "ad_monitor_state"
        const val KEY_ENABLED = "enabled"
        const val KEY_ENABLED_AT = "enabled_at"
        const val KEY_DELETED_COUNT = "deleted_count"
        const val KEY_LAST_DELETED_AT = "last_deleted_at"
        const val KEY_LAST_ERROR = "last_error"
    }
}
