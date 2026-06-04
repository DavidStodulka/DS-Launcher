package com.caros.can

// ─────────────────────────────────────────────────────────────────────────────
//  CANLogger.kt — Rotating file logger for raw CAN frames
//
//  Writes to /sdcard/CarOS/can_logs/
//  Format per line: <unix_millis>,<raw_line>
//  Max file size: 10 MB  |  Max files retained: 5
// ─────────────────────────────────────────────────────────────────────────────

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_DIR            = "/sdcard/CarOS/can_logs"
private const val MAX_FILE_BYTES     = 10L * 1024L * 1024L   // 10 MB
private const val MAX_FILES_RETAINED = 5
private const val FILE_PREFIX        = "can_"
private const val FILE_EXTENSION     = ".csv"
private const val CHANNEL_CAPACITY   = 2048

/**
 * Coroutine-based, non-blocking logger for raw CAN frames.
 *
 * Call [log] from any coroutine / thread — writes are dispatched through
 * a [Channel] and handled serially on [Dispatchers.IO] so the CAN reader
 * loop is never blocked by I/O.
 *
 * Call [start] once (typically from [CANService]) and [stop] when the
 * service is destroyed.
 */
@Singleton
class CANLogger @Inject constructor() {

    private val logScope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logChannel = Channel<String>(capacity = CHANNEL_CAPACITY, onBufferOverflow =
        kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    @Volatile private var isStarted = false

    private var currentWriter: BufferedWriter? = null
    private var currentFile:   File?           = null
    private var currentBytes:  Long            = 0L

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the background logging coroutine.
     * Safe to call multiple times — only starts once.
     */
    fun start() {
        if (isStarted) return
        isStarted = true

        ensureLogDir()

        logScope.launch {
            for (entry in logChannel) {
                try {
                    writeEntry(entry)
                } catch (e: Exception) {
                    Timber.e(e, "CANLogger write error — reopening file")
                    closeWriter()
                }
            }
        }
        Timber.d("CANLogger started, writing to %s", LOG_DIR)
    }

    /**
     * Stops the logger and flushes any pending entries.
     */
    fun stop() {
        logChannel.close()
        closeWriter()
        isStarted = false
        Timber.d("CANLogger stopped")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enqueue a raw CAN line for logging.
     * Non-blocking — drops entries if the internal buffer is full (ring-buffer).
     *
     * @param rawLine  The raw string as received from the CAN adapter.
     */
    fun log(rawLine: String) {
        if (!isStarted) return
        val entry = "${System.currentTimeMillis()},$rawLine"
        logChannel.trySend(entry)  // non-blocking, drops if full
    }

    /**
     * Flush and close the current log file without stopping the logger.
     * Useful when a new logging session begins.
     */
    fun rotate() {
        logScope.launch { closeWriter() }
    }

    // ── Internal write logic ──────────────────────────────────────────────────

    private fun writeEntry(entry: String) {
        val writer = getOrOpenWriter()
        writer.write(entry)
        writer.newLine()
        currentBytes += entry.length + 1

        if (currentBytes >= MAX_FILE_BYTES) {
            Timber.d("CANLogger: file size limit reached, rotating")
            closeWriter()
        }
    }

    private fun getOrOpenWriter(): BufferedWriter {
        val existing = currentWriter
        if (existing != null) return existing

        val dir  = File(LOG_DIR)
        if (!dir.exists()) dir.mkdirs()

        pruneOldFiles(dir)

        val fileName = "$FILE_PREFIX${dateFormat.format(Date())}$FILE_EXTENSION"
        val file     = File(dir, fileName)

        Timber.d("CANLogger: opening new log file %s", file.absolutePath)

        val bw = BufferedWriter(FileWriter(file, true /* append */))
        bw.write("# CarOS CAN log — opened ${System.currentTimeMillis()}")
        bw.newLine()
        bw.write("# timestamp_ms,raw_line")
        bw.newLine()

        currentWriter = bw
        currentFile   = file
        currentBytes  = file.length()

        return bw
    }

    private fun closeWriter() {
        try {
            currentWriter?.flush()
            currentWriter?.close()
        } catch (e: Exception) {
            Timber.w(e, "CANLogger: error closing writer")
        } finally {
            currentWriter = null
            currentFile   = null
            currentBytes  = 0L
        }
    }

    private fun pruneOldFiles(dir: File) {
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_EXTENSION)
        } ?: return

        if (files.size >= MAX_FILES_RETAINED) {
            files.sortedBy { it.lastModified() }
                 .take(files.size - MAX_FILES_RETAINED + 1)
                 .forEach { old ->
                     val deleted = old.delete()
                     Timber.d("CANLogger: pruned %s (deleted=%b)", old.name, deleted)
                 }
        }
    }

    private fun ensureLogDir() {
        val dir = File(LOG_DIR)
        if (!dir.exists()) {
            val ok = dir.mkdirs()
            Timber.d("CANLogger: created log dir %s (ok=%b)", LOG_DIR, ok)
        }
    }
}
