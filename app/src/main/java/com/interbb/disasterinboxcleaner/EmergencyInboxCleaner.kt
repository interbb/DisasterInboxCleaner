package com.interbb.disasterinboxcleaner

import android.content.Context
import android.provider.Telephony

sealed interface CleanupResult {
    data class Success(val deleted: Int) : CleanupResult
    data class Failure(val error: MonitorError) : CleanupResult
}

class EmergencyInboxCleaner(private val context: Context) {
    fun deleteCopiesSince(monitoringStartedAt: Long): CleanupResult {
        if (!PermissionState.canReadSms(context)) {
            return CleanupResult.Failure(MonitorError.SMS_READ_PERMISSION_REQUIRED)
        }
        if (!PermissionState.canDeleteSms(context)) {
            return CleanupResult.Failure(MonitorError.SMS_DELETE_PERMISSION_REQUIRED)
        }
        if (monitoringStartedAt <= 0L) {
            return CleanupResult.Success(0)
        }

        return try {
            val deleted = context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                EmergencyCopyPolicy.deleteSelection,
                EmergencyCopyPolicy.deleteSelectionArgs(monitoringStartedAt),
            )
            CleanupResult.Success(deleted)
        } catch (_: SecurityException) {
            CleanupResult.Failure(MonitorError.SMS_DELETE_PERMISSION_REQUIRED)
        } catch (_: RuntimeException) {
            CleanupResult.Failure(MonitorError.SMS_PROVIDER_REJECTED)
        }
    }
}
