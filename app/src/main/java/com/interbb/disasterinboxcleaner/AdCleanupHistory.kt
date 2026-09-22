package com.interbb.disasterinboxcleaner

import android.content.Context
import java.io.File
import java.io.IOException

data class AdCleanupEntry(
    val at: Long,
    val address: String,
    val snippet: String,
    val rule: String,
)

/**
 * Local-only log of deleted advertising SMS: timestamp, sender, rule and the first 30 characters.
 * Never the full body. Stored as tab-separated text so JVM unit tests need no Android runtime.
 *
 * File format: first line "v1", then one entry per line: at<TAB>address<TAB>rule<TAB>snippet.
 */
class AdCleanupHistory(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun append(entries: List<AdCleanupEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val cutoff = now() - RETENTION_MS
            val merged = (entries + read())
                .filter { it.at >= cutoff }
                .sortedByDescending { it.at }
                .take(MAX_ENTRIES)
            write(merged)
        }
    }

    fun list(): List<AdCleanupEntry> = synchronized(lock) { read().sortedByDescending { it.at } }

    fun clear() {
        synchronized(lock) {
            if (file.exists()) file.delete()
        }
    }

    private fun read(): List<AdCleanupEntry> {
        if (!file.exists()) return emptyList()
        val lines = try {
            file.readLines()
        } catch (_: IOException) {
            quarantine()
            return emptyList()
        }
        if (lines.isEmpty() || lines[0] != HEADER) {
            quarantine()
            return emptyList()
        }
        val entries = ArrayList<AdCleanupEntry>(lines.size)
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split('\t', limit = 4)
            val at = parts.getOrNull(0)?.toLongOrNull()
            if (parts.size < 4 || at == null) {
                quarantine()
                return emptyList()
            }
            entries += AdCleanupEntry(at = at, address = parts[1], rule = parts[2], snippet = parts[3])
        }
        return entries
    }

    private fun write(entries: List<AdCleanupEntry>) {
        file.parentFile?.mkdirs()
        val text = buildString {
            appendLine(HEADER)
            entries.forEach { entry ->
                append(entry.at).append('\t')
                    .append(clean(entry.address)).append('\t')
                    .append(clean(entry.rule)).append('\t')
                    .append(clean(entry.snippet)).append('\n')
            }
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    private fun quarantine() {
        val backup = File(file.parentFile, file.name + ".bak")
        if (backup.exists()) backup.delete()
        file.renameTo(backup)
    }

    companion object {
        const val MAX_ENTRIES = 300
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val FILE_NAME = "ad_cleanup_history.tsv"
        private const val HEADER = "v1"

        fun default(context: Context): AdCleanupHistory =
            AdCleanupHistory(File(context.filesDir, FILE_NAME))

        internal fun clean(value: String): String =
            value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        private val lock = Any()
    }
}
