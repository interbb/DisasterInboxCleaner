package com.interbb.disasterinboxcleaner.adb

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AdbResult(val exitCode: Int, val output: String)

/**
 * Runs the bundled adb client (app/src/main/jniLibs/arm64-v8a/libadb.so, Apache-2.0 AOSP build).
 * The binary lives in the app's native library dir, the only location Android lets an app execute
 * from. HOME/TMPDIR point inside the app so pairing keys stay private, and the adb server uses
 * port 5038 to avoid clashing with other on-device adb servers.
 */
class AdbBinary(private val context: Context) {
    val file: File
        get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    fun isAvailable(): Boolean = file.exists() && file.canExecute()

    fun run(args: List<String>, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): AdbResult {
        val home = File(context.filesDir, "adbhome").apply { mkdirs() }
        val tmp = File(context.cacheDir, "adbtmp").apply { mkdirs() }
        val builder = ProcessBuilder(listOf(file.absolutePath) + args).redirectErrorStream(true)
        builder.environment()["HOME"] = home.absolutePath
        builder.environment()["TMPDIR"] = tmp.absolutePath
        builder.environment()["ANDROID_ADB_SERVER_PORT"] = SERVER_PORT.toString()
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return AdbResult(-1, "launch failed: ${e.message}")
        }
        val buffer = StringBuilder()
        val pump = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> synchronized(buffer) { buffer.append(line).append('\n') } }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            pump.join(1_000)
            return AdbResult(-1, "timeout\n" + synchronized(buffer) { buffer.toString() })
        }
        pump.join(2_000)
        return AdbResult(process.exitValue(), synchronized(buffer) { buffer.toString() })
    }

    fun pair(port: Int, code: String): AdbResult = run(listOf("pair", "127.0.0.1:$port", code))

    fun connect(port: Int): AdbResult = run(listOf("connect", "127.0.0.1:$port"))

    fun shell(vararg command: String): AdbResult = run(listOf("shell") + command)

    fun killServer(): AdbResult = run(listOf("kill-server"), timeoutSeconds = 10)

    companion object {
        const val BINARY_NAME = "libadb.so"
        const val SERVER_PORT = 5038
        const val DEFAULT_TIMEOUT_SECONDS = 30L
    }
}
