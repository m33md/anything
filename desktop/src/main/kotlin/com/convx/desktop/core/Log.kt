/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A small rolling log file next to the caches. Playback failures on a user's machine
 * can only be diagnosed from here, so stream resolution and audio errors land in it.
 */
object Log {
    private val file: File by lazy { File(AppDirs.logs, "convx.log") }
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lock = Any()

    fun i(tag: String, message: String) = write("I", tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write("W", tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    /** The latest crash, as the phone's Crash report page shows it; null until one happens. */
    val crashFile: File get() = File(AppDirs.logs, "crash-report.txt")

    /** The last [lines] lines of the log, newest last, for pasting into a bug report. */
    fun recent(lines: Int = 300): String = synchronized(lock) {
        runCatching { file.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
    }

    /**
     * The phone's crash report: what was running, the error, and the log leading up to it, kept
     * until the next crash so it can be copied from Settings after a restart.
     */
    fun crash(thread: Thread, error: Throwable) {
        e("Crash", "Uncaught on ${thread.name}", error)
        runCatching {
            val trace = java.io.StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            crashFile.writeText(
                buildString {
                    appendLine("Convx ${AppInfo.VERSION} crash report")
                    appendLine("Time: ${LocalDateTime.now()}")
                    appendLine("System: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
                    appendLine("Java: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    appendLine(trace)
                    appendLine("Log before the crash:")
                    append(recent(150))
                },
            )
        }
    }

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val line = "${LocalDateTime.now().format(time)} $level/$tag: $message"
        System.err.println(line)
        synchronized(lock) {
            runCatching {
                if (file.length() > 2_000_000) file.renameTo(File(file.parentFile, "convx.old.log"))
                PrintWriter(java.io.FileOutputStream(file, true).bufferedWriter()).use { out ->
                    out.println(line)
                    error?.printStackTrace(out)
                }
            }
        }
    }
}
