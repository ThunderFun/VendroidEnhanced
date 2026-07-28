package com.nin0dev.vendroid.utils

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object VDELog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    private data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String
    )

    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_BYTES = 256 * 1024L   // 256 KB
    private const val TRUNCATE_TARGET = 128 * 1024L   // keep newest 128 KB

    private val buffer = ArrayDeque<LogEntry>(64)
    // DateTimeFormatter is immutable and thread-safe (unlike SimpleDateFormat)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val zone = ZoneId.systemDefault()

    private var handler: Handler? = null
    private var logFile: File? = null

    private val initialized = AtomicBoolean(false)

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        logFile = File(context.filesDir, "vde_logs.txt")
        val thread = HandlerThread("VDELog-writer", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        handler = Handler(thread.looper)
        // Truncate the previous session's file so logs don't persist across launches.
        try {
            logFile?.writeText("")
        } catch (_: Exception) {}
        try {
            val header = "--- Session: ${dateFmt.format(Instant.now().atZone(zone))} (PID=${android.os.Process.myPid()}) ---\n"
            logFile?.appendText(header)
        } catch (_: Exception) {}
    }

    fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        synchronized(buffer) {
            buffer.addLast(entry)
            if (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        handler?.post { writeToFile(entry, throwable) }
    }

    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)
    fun e(tag: String, message: String, throwable: Throwable) = log(Level.ERROR, tag, message, throwable)
    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)

    fun getRecentLogs(count: Int = 500): String {
        val entries: List<LogEntry>
        synchronized(buffer) {
            entries = buffer.toList().takeLast(count.coerceIn(1, MAX_ENTRIES))
        }
        if (entries.isEmpty()) return "No logs available."
        val sb = StringBuilder(entries.size * 120)
        for (entry in entries) {
            sb.append('[')
            sb.append(timeFmt.format(Instant.ofEpochMilli(entry.timestamp).atZone(zone)))
            sb.append("] [")
            sb.append(entry.level.name)
            sb.append("] [")
            sb.append(entry.tag)
            sb.append("] ")
            // Truncate individual entries to prevent bloating the JS bridge return value
            val msg = if (entry.message.length > 2000) entry.message.substring(0, 2000) + "…" else entry.message
            // Escape newlines so each entry is one line for parsing
            sb.append(msg.replace("\n", "\\n"))
            sb.append('\n')
        }
        return sb.toString()
    }

    fun clearLogs() {
        synchronized(buffer) { buffer.clear() }
        handler?.post {
            try { logFile?.writeText("") } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        handler?.looper?.quitSafely()
        handler = null
    }

    fun getLogFileContents(): String {
        return try {
            val f = logFile ?: return "No log file."
            if (f.exists()) f.readText() else "No log file."
        } catch (_: Exception) {
            "Failed to read log file."
        }
    }

    private fun writeToFile(entry: LogEntry, throwable: Throwable?) {
        try {
            val f = logFile ?: return
            val line = buildString(160) {
                append('[')
                append(dateFmt.format(Instant.ofEpochMilli(entry.timestamp).atZone(zone)))
                append("] [")
                append(entry.level.name)
                append("] [")
                append(entry.tag)
                append("] ")
                append(entry.message.replace("\n", "\\n"))
                if (throwable != null) {
                    append(" | ")
                    append(throwable.stackTraceToString())
                }
                append('\n')
            }
            f.appendText(line)
            rotateIfNeeded(f)
        } catch (_: Exception) {}
    }

    private fun rotateIfNeeded(f: File) {
        if (f.length() <= MAX_FILE_BYTES) return
        try {
            val bytes = f.readBytes()
            val keep = bytes.copyOfRange((bytes.size - TRUNCATE_TARGET.toInt()).coerceAtLeast(0), bytes.size)
            f.writeBytes(keep)
        } catch (_: Exception) {}
    }
}
