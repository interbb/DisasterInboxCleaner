package com.interbb.disasterinboxcleaner

import android.content.Context
import android.provider.BaseColumns
import android.provider.Telephony

data class AdCandidate(
    val id: Long,
    val address: String,
    val receivedAt: Long,
    val body: String,
)

sealed interface AdCleanupResult {
    data class Success(val deleted: Int, val addresses: List<String>) : AdCleanupResult
    data class Failure(val error: MonitorError) : AdCleanupResult
}

/**
 * Finds and deletes legally marked advertising SMS in the inbox.
 *
 * All queries and deletes go through Telephony.Sms.CONTENT_URI (content://sms). The provider
 * rejects deletes on content://sms/inbox ("Unknown URL") and ignores where clauses on
 * content://sms/N, so every delete is "content://sms + selection by id".
 */
class AdInboxCleaner(
    private val context: Context,
    private val history: AdCleanupHistory,
) {
    /** Automatic path: delete marked inbox rows received at or after [enabledAt]. */
    fun cleanSince(enabledAt: Long): AdCleanupResult {
        if (!PermissionState.canReadSms(context)) {
            return AdCleanupResult.Failure(MonitorError.SMS_READ_PERMISSION_REQUIRED)
        }
        if (!PermissionState.canDeleteSms(context)) {
            return AdCleanupResult.Failure(MonitorError.SMS_DELETE_PERMISSION_REQUIRED)
        }
        if (enabledAt <= 0L) {
            return AdCleanupResult.Success(0, emptyList())
        }

        return try {
            val candidates = queryAdCandidates(
                AdSmsPolicy.autoDeleteSelection,
                AdSmsPolicy.autoDeleteSelectionArgs(enabledAt),
            )
            val deleted = deleteCandidates(candidates, AdSmsPolicy.RULE_LEGAL_MARK)
            AdCleanupResult.Success(deleted.size, deleted.map { it.address })
        } catch (_: SecurityException) {
            AdCleanupResult.Failure(MonitorError.SMS_DELETE_PERMISSION_REQUIRED)
        } catch (_: RuntimeException) {
            AdCleanupResult.Failure(MonitorError.SMS_PROVIDER_REJECTED)
        }
    }

    /** Manual screen: every marked inbox row, newest first. Bodies stay in memory only. */
    fun listCandidates(): HistoryLoadResult {
        if (!PermissionState.canReadSms(context)) {
            return HistoryLoadResult.ReadPermissionRequired
        }

        return try {
            val items = queryAdCandidates(AdSmsPolicy.inboxAdSelection, null).map { candidate ->
                DisasterMessageItem(
                    id = candidate.id,
                    receivedAt = candidate.receivedAt,
                    body = candidate.body,
                    address = candidate.address,
                )
            }
            HistoryLoadResult.Success(items)
        } catch (_: SecurityException) {
            HistoryLoadResult.ReadPermissionRequired
        } catch (_: RuntimeException) {
            HistoryLoadResult.ProviderRejected
        }
    }

    fun deleteByIds(ids: Set<Long>): ManualDeleteResult = manualDelete { candidates ->
        candidates.filter { it.id in ids }
    }

    fun deleteAll(): ManualDeleteResult = manualDelete { candidates -> candidates }

    private fun manualDelete(select: (List<AdCandidate>) -> List<AdCandidate>): ManualDeleteResult {
        if (!PermissionState.canDeleteSms(context)) {
            return ManualDeleteResult.DeletePermissionRequired
        }

        return try {
            val candidates = select(queryAdCandidates(AdSmsPolicy.inboxAdSelection, null))
            val deleted = deleteCandidates(candidates, AdSmsPolicy.RULE_MANUAL)
            ManualDeleteResult.Success(deleted.size)
        } catch (_: SecurityException) {
            ManualDeleteResult.DeletePermissionRequired
        } catch (_: RuntimeException) {
            ManualDeleteResult.ProviderRejected
        }
    }

    /**
     * Runs the coarse SQL selection, then applies [AdSmsPolicy.isAd] in Kotlin so a mark that is
     * merely present somewhere in the body (the SQL prefilter) never counts unless it actually
     * satisfies the precise rule — this is what keeps every delete, list, and count correct.
     */
    private fun queryAdCandidates(selection: String, selectionArgs: Array<String>?): List<AdCandidate> =
        queryCandidates(selection, selectionArgs).filter { AdSmsPolicy.isAd(it.body) }

    private fun queryCandidates(selection: String, selectionArgs: Array<String>?): List<AdCandidate> =
        buildList {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                PROJECTION,
                selection,
                selectionArgs,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                while (cursor.moveToNext()) {
                    add(
                        AdCandidate(
                            id = cursor.getLong(idIndex),
                            address = cursor.getString(addressIndex).orEmpty(),
                            receivedAt = cursor.getLong(dateIndex),
                            body = cursor.getString(bodyIndex).orEmpty(),
                        ),
                    )
                }
            }
        }

    /** Deletes one row at a time. Only rows the provider reports as deleted are logged. */
    private fun deleteCandidates(candidates: List<AdCandidate>, rule: String): List<AdCandidate> {
        val deleted = candidates.filter { candidate ->
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                AdSmsPolicy.byIdSelection,
                AdSmsPolicy.byIdSelectionArgs(candidate.id),
            ) == 1
        }
        if (deleted.isNotEmpty()) {
            val now = System.currentTimeMillis()
            history.append(
                deleted.map { candidate ->
                    AdCleanupEntry(
                        at = now,
                        address = candidate.address,
                        snippet = AdSmsPolicy.snippet(candidate.body),
                        rule = rule,
                    )
                },
            )
        }
        return deleted
    }

    private companion object {
        val PROJECTION = arrayOf(
            BaseColumns._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
        )
    }
}
