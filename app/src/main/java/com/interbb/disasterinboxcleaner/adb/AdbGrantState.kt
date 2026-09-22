package com.interbb.disasterinboxcleaner.adb

import android.content.Context
import android.content.SharedPreferences

enum class GrantPhase {
    IDLE,
    WAITING_CODE,
    PAIRING,
    WAITING_CONNECT_PORT,
    CONNECTING,
    GRANTING,
    DONE,
    FAILED,
}

data class GrantStatus(
    val phase: GrantPhase = GrantPhase.IDLE,
    val message: String = "",
    val updatedAt: Long = 0L,
) {
    val inProgress: Boolean
        get() = phase == GrantPhase.WAITING_CODE || phase == GrantPhase.PAIRING ||
            phase == GrantPhase.WAITING_CONNECT_PORT || phase == GrantPhase.CONNECTING ||
            phase == GrantPhase.GRANTING
}

/** Last known state of the on-phone grant flow, shared by the service and the UI. */
class AdbGrantPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): GrantStatus = GrantStatus(
        phase = preferences.getString(KEY_PHASE, null)
            ?.let { stored -> GrantPhase.entries.firstOrNull { it.name == stored } }
            ?: GrantPhase.IDLE,
        message = preferences.getString(KEY_MESSAGE, "").orEmpty(),
        updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L),
    )

    fun set(phase: GrantPhase, message: String) {
        preferences.edit()
            .putString(KEY_PHASE, phase.name)
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * The connect port is stable across sessions (unlike the pairing port and code, which are
     * never persisted), so a later run can skip straight to CONNECTING instead of asking again.
     */
    fun rememberedConnectPort(): Int? = preferences.getInt(KEY_CONNECT_PORT, 0).takeIf { it > 0 }

    fun rememberConnectPort(port: Int) {
        preferences.edit().putInt(KEY_CONNECT_PORT, port).apply()
    }

    /** Clears a remembered connect port that turned out to be stale (e.g. after a failed connect). */
    fun forgetConnectPort() {
        preferences.edit().remove(KEY_CONNECT_PORT).apply()
    }

    /**
     * Resets a session that claims to be in progress but whose owning service process is gone (e.g. the OS
     * or the user force-stopped it while WAITING_CODE), so the UI's "진행 중…" state does not persist forever.
     */
    fun reconcileStale(serviceRunning: Boolean) {
        if (AdbGrantMachine.shouldResetStale(snapshot(), serviceRunning)) {
            set(GrantPhase.FAILED, GrantMessages.STALE_SESSION)
        }
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val PREFS_NAME = "adb_grant_state"
        const val KEY_PHASE = "phase"
        const val KEY_MESSAGE = "message"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_CONNECT_PORT = "connect_port"
    }
}
