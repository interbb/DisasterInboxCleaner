package com.interbb.disasterinboxcleaner.adb

/** Interprets adb client output. Pure Kotlin so it is unit-tested without Android. */
object AdbOutcomes {
    private val sixDigits = Regex("(?<!\\d)\\d{6}(?!\\d)")

    fun pairSucceeded(result: AdbResult): Boolean =
        result.exitCode == 0 && result.output.contains("Successfully paired")

    fun connectSucceeded(result: AdbResult): Boolean =
        result.exitCode == 0 &&
            (result.output.contains("connected to") || result.output.contains("already connected to")) &&
            !result.output.contains("failed to connect")

    fun grantSucceeded(result: AdbResult): Boolean =
        result.exitCode == 0 &&
            !result.output.contains("Error", ignoreCase = true) &&
            !result.output.contains("Exception")

    /** Hides any standalone 6-digit run (a pairing code) before text is logged or shown. */
    fun maskCode(text: String): String = sixDigits.replace(text, "******")
}
