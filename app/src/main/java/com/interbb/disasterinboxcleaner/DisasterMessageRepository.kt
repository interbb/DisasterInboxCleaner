package com.interbb.disasterinboxcleaner

import android.content.Context
import android.provider.BaseColumns
import android.provider.Telephony

data class DisasterMessageItem(
    val id: Long,
    val receivedAt: Long,
    val body: String,
    val address: String = "",
)

sealed interface HistoryLoadResult {
    data class Success(val messages: List<DisasterMessageItem>) : HistoryLoadResult
    data object ReadPermissionRequired : HistoryLoadResult
    data object ProviderRejected : HistoryLoadResult
}

sealed interface ManualDeleteResult {
    data class Success(val deleted: Int) : ManualDeleteResult
    data object DeletePermissionRequired : ManualDeleteResult
    data object ProviderRejected : ManualDeleteResult
}

class DisasterMessageRepository(private val context: Context) {
    fun loadMessages(): HistoryLoadResult {
        if (!PermissionState.canReadSms(context)) {
            return HistoryLoadResult.ReadPermissionRequired
        }

        return try {
            val messages = buildList {
                context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(BaseColumns._ID, Telephony.Sms.DATE, Telephony.Sms.BODY),
                    EmergencyCopyPolicy.ADDRESS_SELECTION,
                    null,
                    "${Telephony.Sms.DATE} DESC",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                    val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    while (cursor.moveToNext()) {
                        add(
                            DisasterMessageItem(
                                id = cursor.getLong(idIndex),
                                receivedAt = cursor.getLong(dateIndex),
                                body = cursor.getString(bodyIndex).orEmpty(),
                            ),
                        )
                    }
                }
            }
            HistoryLoadResult.Success(messages)
        } catch (_: SecurityException) {
            HistoryLoadResult.ReadPermissionRequired
        } catch (_: RuntimeException) {
            HistoryLoadResult.ProviderRejected
        }
    }

    fun deleteMessages(ids: Set<Long>): ManualDeleteResult {
        if (!PermissionState.canDeleteSms(context)) {
            return ManualDeleteResult.DeletePermissionRequired
        }

        return try {
            var deleted = 0
            ids.forEach { id ->
                deleted += context.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    EmergencyCopyPolicy.byIdSelection,
                    EmergencyCopyPolicy.byIdSelectionArgs(id),
                )
            }
            ManualDeleteResult.Success(deleted)
        } catch (_: SecurityException) {
            ManualDeleteResult.DeletePermissionRequired
        } catch (_: RuntimeException) {
            ManualDeleteResult.ProviderRejected
        }
    }

    fun deleteAllMessages(): ManualDeleteResult {
        if (!PermissionState.canDeleteSms(context)) {
            return ManualDeleteResult.DeletePermissionRequired
        }

        return try {
            val deleted = context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                EmergencyCopyPolicy.allCopiesSelection,
                null,
            )
            ManualDeleteResult.Success(deleted)
        } catch (_: SecurityException) {
            ManualDeleteResult.DeletePermissionRequired
        } catch (_: RuntimeException) {
            ManualDeleteResult.ProviderRejected
        }
    }
}
