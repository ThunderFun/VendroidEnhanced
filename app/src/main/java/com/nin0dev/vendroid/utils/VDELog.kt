package com.nin0dev.vendroid.utils

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
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
    // Kept open on the handler thread so each log line is a buffered write
    // instead of an open/write/close syscall. Handler-thread confined only.
    private var writer: BufferedWriter? = null
    // Mirrors logFile.length() without a stat() per log line.
    private var currentFileBytes = 0L

    private val initialized = AtomicBoolean(false)

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        logFile = File(context.filesDir, "vde_logs.txt")
        val thread = HandlerThread("VDELog-writer", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        handler = Handler(thread.looper)
        // Truncate the previous session's file and write the session header.
        handler?.post {
            try {
                openWriterLocked(append = false)
                val header = "--- Session: ${dateFmt.format(Instant.now().atZone(zone))} (PID=${android.os.Process.myPid()}) ---\n"
                writeLineLocked(header)
            } catch (_: Exception) {}
        }
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
            try {
                openWriterLocked(append = false)
            } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        val h = handler
        if (h != null) {
            // Flush and close before quitting the looper so buffered bytes survive.
            h.post {
                try {
                    writer?.flush()
                    writer?.close()
                } catch (_: Exception) {}
                writer = null
            }
            h.looper.quitSafely()
        }
        handler = null
    }

    fun getLogFileContents(): String {
        // Flush on the handler thread so the on-disk file is current.
        val h = handler
        if (h != null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            h.post {
                try { writer?.flush() } catch (_: Exception) {}
                latch.countDown()
            }
            try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }
        return try {
            val f = logFile ?: return "No log file."
            if (f.exists()) f.readText() else "No log file."
        } catch (_: Exception) {
            "Failed to read log file."
        }
    }

    private fun writeToFile(entry: LogEntry, throwable: Throwable?) {
        try {
            if (writer == null) openWriterLocked(append = true)
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
            writeLineLocked(line)
            rotateIfNeededLocked()
        } catch (_: Exception) {
            // Writer may be in a bad state — force a reopen on the next line.
            try { writer?.close() } catch (_: Exception) {}
            writer = null
        }
    }

    /** Writes [line] to the writer and advances [currentFileBytes].
     *  Handler-thread confined. */
    private fun writeLineLocked(line: String) {
        val w = writer ?: return
        val bytes = line.toByteArray(Charsets.UTF_8)
        w.write(line, 0, line.length)
        currentFileBytes += bytes.size
    }

    /** Opens or reopens the file writer. When [append] is false the file is
     *  truncated and [currentFileBytes] reset; otherwise appended to and
     *  seeded from the existing file length. Handler-thread confined. */
    private fun openWriterLocked(append: Boolean) {
        try {
            writer?.close()
        } catch (_: Exception) {}
        writer = null
        val f = logFile ?: return
        if (!append) {
            try { f.writeText("") } catch (_: Exception) {}
            currentFileBytes = 0
        } else {
            currentFileBytes = if (f.exists()) f.length() else 0
        }
        writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(f, append), Charsets.UTF_8),
            8192
        )
    }

    /** Truncates the file to [TRUNCATE_TARGET] newest bytes when it exceeds
     *  [MAX_FILE_BYTES], using [currentFileBytes] instead of stat(). */
    private fun rotateIfNeededLocked() {
        if (currentFileBytes <= MAX_FILE_BYTES) return
        try {
            writer?.flush()
            writer?.close()
            writer = null
            val f = logFile ?: return
            val bytes = f.readBytes()
            val keep = bytes.copyOfRange(
                (bytes.size - TRUNCATE_TARGET.toInt()).coerceAtLeast(0),
                bytes.size
            )
            f.writeBytes(keep)
            currentFileBytes = keep.size.toLong()
            writer = BufferedWriter(
                OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8),
                8192
            )
        } catch (_: Exception) {
            // If rotation failed, reopen in append mode so logging continues.
            try {
                openWriterLocked(append = true)
            } catch (_: Exception) {}
        }
    }
}
