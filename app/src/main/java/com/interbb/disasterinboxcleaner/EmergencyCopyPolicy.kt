package com.interbb.disasterinboxcleaner

/**
 * Identifies only the SMS-inbox copies created by Android's Korean Cell Broadcast overlay.
 * Message bodies are deliberately not queried or inspected.
 *
 * Deletes must go through content://sms (Telephony.Sms.CONTENT_URI): the platform provider's
 * delete matcher does not accept content://sms/inbox and throws "Unknown URL", and
 * content://sms/N ignores any where clause. Because content://sms spans every box, each delete
 * selection pins the inbox type explicitly.
 */
object EmergencyCopyPolicy {
    const val ADDRESS_PATTERN = "#CMAS#%"
    const val ADDRESS_COLUMN = "address"
    const val DATE_COLUMN = "date"
    const val TYPE_COLUMN = "type"
    const val ID_COLUMN = "_id"

    /** Telephony.Sms.MESSAGE_TYPE_INBOX, kept literal so unit tests need no Android runtime. */
    const val INBOX_TYPE = 1

    const val ADDRESS_SELECTION = "$ADDRESS_COLUMN LIKE '$ADDRESS_PATTERN'"

    val inboxCopySelection: String = "$ADDRESS_SELECTION AND $TYPE_COLUMN = $INBOX_TYPE"

    /** Automatic cleanup: inbox copies received after monitoring was enabled. */
    val deleteSelection: String = "$inboxCopySelection AND $DATE_COLUMN >= ?"

    fun deleteSelectionArgs(monitoringStartedAt: Long): Array<String> =
        arrayOf(monitoringStartedAt.toString())

    /** Manual "delete all": every inbox copy. */
    val allCopiesSelection: String = inboxCopySelection

    /** Manual per-item delete, still guarded by the address pattern. */
    val byIdSelection: String = "$ID_COLUMN = ? AND $inboxCopySelection"

    fun byIdSelectionArgs(id: Long): Array<String> = arrayOf(id.toString())
}
