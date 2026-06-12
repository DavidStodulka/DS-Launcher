package com.caros.can

// ─────────────────────────────────────────────────────────────────────────────
//  CANReader.kt — Reads raw lines from /dev/ttyS1 and emits them as Flow<String>
//
//  The VW-RZ-08-0041 CAN adapter presents itself as a serial device at
//  /dev/ttyS1 (or /dev/ttyUSB0 on some kernels).  Root must have already
//  run: chmod 666 /dev/ttyS1  (handled by CarOSApplication via RootManager).
//
//  On IOException the reader reconnects with exponential back-off
//  (1 s → 2 s → 4 s … up to 30 s).
//  If the device file cannot be opened at all, it falls back to MockCANSource.
// ─────────────────────────────────────────────────────────────────────────────

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val DEVICE_PATH       = "/dev/ttyS1"
private const val DEVICE_PATH_ALT   = "/dev/ttyUSB0"   // Fallback on some kernels
private const val INITIAL_BACKOFF   = 1_000L            // ms
private const val MAX_BACKOFF       = 30_000L           // ms
private const val BACKOFF_FACTOR    = 2.0

/**
 * Opens the CAN serial device and emits each decoded ASCII line as a [Flow].
 *
 * Usage:
 * ```kotlin
 * canReader.lines().collect { line -> canParser.parseFrame(line) }
 * ```
 *
 * The flow never completes normally — it runs until the collector's scope is
 * cancelled.  On device read errors it automatically reconnects.
 */
@Singleton
class CANReader @Inject constructor(
    private val canLogger: CANLogger,
    private val mockSource: MockCANSource
) {

    /**
     * Dedicated single thread for the blocking serial read loop. Keeps CAN I/O
     * off the shared Dispatchers.IO pool so a stalled read can never starve
     * other I/O work (DB writes, file logging, OBD polling).
     */
    private val readerDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CANReaderThread").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a cold [Flow] of raw CAN lines.
     *
     * If the hardware device is not accessible, falls back to [MockCANSource]
     * raw-line emission (the mock generates lines in the same format so
     * [CANParser] can decode them identically).
     *
     * The flow runs on a dedicated single-thread dispatcher.
     */
    fun lines(): Flow<String> = flow {
        val deviceFile = resolveDevice()

        if (deviceFile == null) {
            Timber.w("CANReader: no CAN device found at %s or %s — using mock source",
                DEVICE_PATH, DEVICE_PATH_ALT)
            mockSource.rawLines().collect { emit(it) }
            return@flow
        }

        Timber.i("CANReader: using device %s", deviceFile.absolutePath)

        var backoffMs = INITIAL_BACKOFF

        while (coroutineContext.isActive) {
            try {
                Timber.d("CANReader: opening %s", deviceFile.absolutePath)
                FileInputStream(deviceFile).use { fis ->
                    BufferedReader(InputStreamReader(fis, Charsets.US_ASCII)).use { reader ->
                        backoffMs = INITIAL_BACKOFF   // reset on successful open
                        Timber.i("CANReader: connected to %s", deviceFile.absolutePath)

                        var line: String?
                        while (coroutineContext.isActive) {
                            line = reader.readLine()
                            if (line == null) {
                                // EOF — device was likely disconnected
                                Timber.w("CANReader: EOF on %s", deviceFile.absolutePath)
                                break
                            }
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) {
                                canLogger.log(trimmed)
                                emit(trimmed)
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Timber.e(e, "CANReader: permission denied on %s — check chmod 666",
                    deviceFile.absolutePath)
                // Permission denied is unlikely to self-heal; switch to mock
                Timber.w("CANReader: falling back to mock source due to permission error")
                mockSource.rawLines().collect { emit(it) }
                return@flow
            } catch (e: Exception) {
                if (!coroutineContext.isActive) return@flow
                Timber.w(e, "CANReader: I/O error — reconnect in %d ms", backoffMs)
            }

            // Back off before reconnecting
            delay(backoffMs)
            backoffMs = (backoffMs * BACKOFF_FACTOR).toLong().coerceAtMost(MAX_BACKOFF)
        }
    }.flowOn(readerDispatcher)

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves which device path to open.  Returns null if neither path exists
     * and the mock fallback should be used instead.
     */
    private fun resolveDevice(): File? {
        for (path in listOf(DEVICE_PATH, DEVICE_PATH_ALT)) {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                return f
            }
            // exists() but not readable → try anyway (root may have chmod'd it)
            if (f.exists()) {
                Timber.d("CANReader: %s exists but canRead()=false — attempting open", path)
                return f
            }
        }
        return null
    }
}
