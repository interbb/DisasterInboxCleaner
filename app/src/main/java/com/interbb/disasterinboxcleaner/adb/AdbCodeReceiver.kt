package com.interbb.disasterinboxcleaner.adb

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Receives the notification's inline reply (or cancel) and forwards it to the grant service. */
class AdbCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, AdbGrantService::class.java)
        when (intent.action) {
            ACTION_CODE -> {
                // The raw text travels through unchanged; AdbGrantService.handleReply decides how to
                // parse it (AdbCodeInput) based on which phase it is currently waiting on.
                val typed = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(AdbGrantNotifications.KEY_CODE)
                    ?.toString()
                    .orEmpty()
                service.action = AdbGrantService.ACTION_CODE
                service.putExtra(AdbGrantService.EXTRA_INPUT, typed)
            }
            ACTION_CANCEL -> service.action = AdbGrantService.ACTION_CANCEL
            else -> return
        }
        runCatching { ContextCompat.startForegroundService(context, service) }
            .onFailure {
                // Background-start restriction lapsed (e.g. resume-after-process-death outside the allowlist
                // window). Report failure instead of letting the exception crash the app.
                AdbGrantPreferences(context).set(GrantPhase.FAILED, GrantMessages.SERVICE_START_FAILED)
                AdbGrantNotifications(context).show(GrantPhase.FAILED, GrantMessages.SERVICE_START_FAILED)
            }
    }

    companion object {
        const val ACTION_CODE = "com.interbb.disasterinboxcleaner.adb.CODE_REPLY"
        const val ACTION_CANCEL = "com.interbb.disasterinboxcleaner.adb.CANCEL_REPLY"
    }
}
