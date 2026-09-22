package com.interbb.disasterinboxcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdCleanupHistoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_700_000_000_000L
    private lateinit var file: File

    private fun history(): AdCleanupHistory {
        file = File(temp.root, "history.tsv")
        return AdCleanupHistory(file) { now }
    }

    private fun entry(
        at: Long,
        address: String = "0212345678",
        snippet: String = "(광고)테스트",
        rule: String = AdSmsPolicy.RULE_LEGAL_MARK,
    ) = AdCleanupEntry(at = at, address = address, snippet = snippet, rule = rule)

    @Test
    fun appendThenListReturnsNewestFirst() {
        val history = history()
        history.append(listOf(entry(now - 2_000), entry(now - 1_000)))
        history.append(listOf(entry(now)))

        val listed = history.list()
        assertEquals(listOf(now, now - 1_000, now - 2_000), listed.map { it.at })
        assertEquals("0212345678", listed[0].address)
        assertEquals("(광고)테스트", listed[0].snippet)
        assertEquals(AdSmsPolicy.RULE_LEGAL_MARK, listed[0].rule)
    }

    @Test
    fun keepsAtMostThreeHundredNewestEntries() {
        val history = history()
        history.append((0 until 301).map { entry(now - it) })

        val listed = history.list()
        assertEquals(300, listed.size)
        assertEquals(now, listed.first().at)
        assertEquals(now - 299, listed.last().at)
    }

    @Test
    fun dropsEntriesOlderThanRetention() {
        val history = history()
        val tooOld = now - AdCleanupHistory.RETENTION_MS - 1
        history.append(listOf(entry(tooOld), entry(now)))

        assertEquals(listOf(now), history.list().map { it.at })
    }

    @Test
    fun corruptFileIsQuarantinedAndListIsEmpty() {
        val history = history()
        file.writeText("not a history file")

        assertTrue(history.list().isEmpty())
        assertFalse(file.exists())
        assertTrue(File(temp.root, "history.tsv.bak").exists())
    }

    @Test
    fun clearRemovesFile() {
        val history = history()
        history.append(listOf(entry(now)))
        history.clear()

        assertFalse(file.exists())
        assertTrue(history.list().isEmpty())
    }

    @Test
    fun tabsAndNewlinesAreFlattenedBeforeStorage() {
        val history = history()
        history.append(listOf(entry(now, address = "a\tb", snippet = "line1\nline2")))

        val stored = history.list().single()
        assertEquals("a b", stored.address)
        assertEquals("line1 line2", stored.snippet)
    }

    @Test
    fun concurrentInstancesOnSameFileStayConsistentAndLeaveNoTempFile() {
        file = File(temp.root, "history.tsv")
        val first = AdCleanupHistory(file) { now }
        val second = AdCleanupHistory(file) { now }

        first.append(listOf(entry(now - 1_000)))
        second.append(listOf(entry(now)))

        val listed = first.list()
        assertEquals(listOf(now, now - 1_000), listed.map { it.at })
        assertFalse(File(temp.root, "history.tsv.tmp").exists())
    }
}
