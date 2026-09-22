package com.interbb.disasterinboxcleaner

/**
 * Identifies legally marked advertising SMS: a body where, skipping past up to a few known
 * carrier tags such as "[Web발신]", the mark "(광고)" or "[광고]" follows.
 *
 * The provider can't express that rule in SQL, so this is a two-stage check. [candidateMarkSelection]
 * (and the selections built from it) is a coarse SQL prefilter: does the mark appear anywhere in
 * the body? [isAd] is the precise rule, checked in Kotlin against the actual candidate rows.
 * Every delete already goes "query candidates, then delete by id" (see AdInboxCleaner), so the
 * coarse filter alone never causes a delete — callers must still run [isAd] over the results
 * before deleting, listing, or counting them.
 */
object AdSmsPolicy {
    const val RULE_LEGAL_MARK = "legal_mark"
    const val RULE_MANUAL = "manual"
    const val SNIPPET_LENGTH = 30

    /**
     * The only bracketed runs [isAd] treats as a carrier tag to skip past, not any bracketed
     * text — normalized (internal whitespace stripped, lower-cased) so "[Web 발신]", "[web발신]",
     * and "[WEB발신]" all match. A tag whose normalized inner text is not in this set (e.g. a
     * safety-notice header like "[안전안내문자]") is never skipped, so the mark can never be
     * reached past it.
     */
    private val KNOWN_CARRIER_TAGS: Set<String> = setOf(
        "Web발신",
        "국제발신",
        "국외발신",
        "해외발신",
        "국내발신",
    ).mapTo(mutableSetOf(), ::normalizeTag)

    /** Known carrier tags such as "[Web발신]" may precede the mark; at most this many are skipped. */
    private const val MAX_CARRIER_TAGS = 3

    /** A carrier tag's closing bracket must appear within this many characters of its opener. */
    private const val MAX_TAG_INNER_LENGTH = 20

    private const val BODY_COLUMN = "body"
    private const val TYPE_COLUMN = "type"
    private const val DATE_COLUMN = "date"
    private const val ID_COLUMN = "_id"

    /** Telephony.Sms.MESSAGE_TYPE_INBOX, kept literal so unit tests need no Android runtime. */
    private const val INBOX_TYPE = 1

    val markers: List<String> = listOf("(광고)", "[광고]")

    /**
     * Coarse SQL prefilter: does the mark appear anywhere in the body? SQLite's only LIKE
     * wildcards are `%` and `_`, so the parentheses and brackets in these patterns are literal.
     * This is broader than the real rule (it also matches a mark buried mid-message), so every
     * row it returns must still be checked with [isAd] before it is deleted, listed, or counted.
     */
    val candidateMarkSelection: String = markers.joinToString(prefix = "(", separator = " OR ", postfix = ")") {
        "$BODY_COLUMN LIKE '%$it%'"
    }

    /** Manual list and "delete all": every inbox row that might carry the legal mark (see [isAd]). */
    val inboxAdSelection: String = "$TYPE_COLUMN = $INBOX_TYPE AND $candidateMarkSelection"

    /** Automatic cleanup: candidate inbox rows received after the feature was enabled. */
    val autoDeleteSelection: String = "$inboxAdSelection AND $DATE_COLUMN >= ?"

    fun autoDeleteSelectionArgs(enabledAt: Long): Array<String> = arrayOf(enabledAt.toString())

    /** Per-row delete, still guarded by type and the coarse mark so a stale id can never delete another row. */
    val byIdSelection: String = "$ID_COLUMN = ? AND $inboxAdSelection"

    fun byIdSelectionArgs(id: Long): Array<String> = arrayOf(id.toString())

    /**
     * Precise rule: scan from the start of [body], skipping whitespace and up to
     * [MAX_CARRIER_TAGS] known carrier tags — a bracketed or parenthesized run whose closing
     * bracket appears within [MAX_TAG_INNER_LENGTH] characters AND whose inner text normalizes
     * to one of [KNOWN_CARRIER_TAGS] — until either the legal mark is found (an ad) or something
     * else is, including an unrecognized bracketed run (not an ad). The mark is always checked
     * before any tag handling, so "[광고]..." matches immediately without going near the tag logic.
     */
    fun isAd(body: String?): Boolean {
        if (body == null) return false
        val length = body.length
        var index = 0
        var tagsSkipped = 0

        while (index < length) {
            while (index < length && body[index].isWhitespace()) index++
            if (index >= length) return false

            if (markers.any { body.startsWith(it, index) }) return true

            val closer = when (body[index]) {
                '[' -> ']'
                '(' -> ')'
                else -> return false
            }
            if (tagsSkipped >= MAX_CARRIER_TAGS) return false

            val searchLimit = minOf(length, index + 1 + MAX_TAG_INNER_LENGTH)
            val closeIndex = body.indexOf(closer, index + 1)
            if (closeIndex == -1 || closeIndex >= searchLimit) return false

            val inner = body.substring(index + 1, closeIndex)
            if (normalizeTag(inner) !in KNOWN_CARRIER_TAGS) return false

            index = closeIndex + 1
            tagsSkipped++
        }
        return false
    }

    /** Case-insensitive, internal-whitespace-insensitive form used to match a tag's inner text. */
    private fun normalizeTag(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()

    fun snippet(body: String?): String {
        if (body == null) return ""
        val flat = body.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
        return if (flat.length <= SNIPPET_LENGTH) flat else flat.substring(0, SNIPPET_LENGTH)
    }
}
