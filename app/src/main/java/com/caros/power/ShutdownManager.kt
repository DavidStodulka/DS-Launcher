package com.caros.power

// ─────────────────────────────────────────────────────────────────────────────
//  ShutdownManager.kt — Graceful vehicle computer shutdown sequencer
//
//  Attempts multiple shutdown strategies in order of preference:
//    1. Broadcast save-state to all components
//    2. su reboot -p  (ACPI power-off)
//    3. su poweroff
//    4. Android ACTION_SHUTDOWN intent
//    5. Hidden PowerManager.shutdown() via reflection
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.caros.core.ShellExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Broadcast action sent to all components requesting immediate state save. */
const val ACTION_SAVE_STATE = "com.caros.power.ACTION_SAVE_STATE"

/** Broadcast action sent to UI to display the countdown shutdown overlay. */
const val ACTION_SHOW_SHUTDOWN_OVERLAY = "com.caros.power.ACTION_SHOW_SHUTDOWN_OVERLAY"

/** Extra: remaining seconds before shutdown. */
const val EXTRA_SHUTDOWN_SECONDS = "shutdown_seconds"

@Singleton
class ShutdownManager @Inject constructor(
    private val shellExecutor: ShellExecutor,
    @ApplicationContext private val context: Context
) {

    private val TAG = "ShutdownManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scheduledRunnable: Runnable? = null
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Performs a graceful shutdown sequence. Must be called from a coroutine.
     * This function does not return under normal circumstances.
     */
    suspend fun shutdown() = withContext(Dispatchers.IO) {
        Timber.i("$TAG: initiating graceful shutdown sequence")

        // Step 1: broadcast save-state to all bound components
        broadcastSaveState()

        // Step 2: allow 2 seconds for saves to complete
        delay(SAVE_STATE_WAIT_MS)

        // Step 3: try root reboot -p (cleanest ACPI shutdown)
        Timber.i("$TAG: attempting 'reboot -p' via su")
        val rebootResult = shellExecutor.execute("su -c 'reboot -p'")
        if (rebootResult.isSuccess) {
            Timber.i("$TAG: reboot -p command sent successfully")
            delay(SHUTDOWN_COMMAND_WAIT_MS)
        } else {
            Timber.w("$TAG: reboot -p failed: ${rebootResult.exceptionOrNull()?.message}")
        }

        // Step 4: fallback — poweroff
        Timber.i("$TAG: attempting 'poweroff' via su")
        val poweroffResult = shellExecutor.execute("su -c 'poweroff'")
        if (poweroffResult.isSuccess) {
            Timber.i("$TAG: poweroff command sent successfully")
            delay(SHUTDOWN_COMMAND_WAIT_MS)
        } else {
            Timber.w("$TAG: poweroff failed: ${poweroffResult.exceptionOrNull()?.message}")
        }

        // Step 5: fallback — Android ACTION_SHUTDOWN broadcast
        Timber.i("$TAG: sending ACTION_SHUTDOWN broadcast")
        try {
            @Suppress("DEPRECATION")
            val shutdownIntent = Intent("android.intent.action.ACTION_SHUTDOWN").apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            context.sendBroadcast(shutdownIntent)
            delay(SHUTDOWN_COMMAND_WAIT_MS)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: ACTION_SHUTDOWN broadcast failed")
        }

        // Step 6: last resort — PowerManager hidden API via reflection
        Timber.i("$TAG: attempting hidden PowerManager.shutdown() via reflection")
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val shutdownMethod = PowerManager::class.java.getMethod(
                "shutdown",
                Boolean::class.javaPrimitiveType,
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
            shutdownMethod.invoke(pm, false, "CarOS controlled shutdown", false)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: reflection shutdown failed: ${e.message}")
        }

        Timber.e("$TAG: all shutdown methods exhausted — device may not power off")
    }

    /**
     * Posts a delayed shutdown on the main thread handler.
     *
     * @param delayMs milliseconds to wait before calling [shutdown]
     */
    fun scheduleShutdown(delayMs: Long) {
        cancelScheduled()
        Timber.i("$TAG: scheduling shutdown in ${delayMs}ms")
        val runnable = Runnable {
            // Fire-and-forget on an IO-backed scope; never blocks the main thread
            shutdownScope.launch { shutdown() }
        }
        scheduledRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    /** Cancels any pending [scheduleShutdown] that has not yet fired. */
    fun cancelScheduled() {
        scheduledRunnable?.let {
            mainHandler.removeCallbacks(it)
            scheduledRunnable = null
            Timber.d("$TAG: scheduled shutdown cancelled")
        }
    }

    /**
     * Sends a broadcast to the UI layer to show the countdown overlay.
     *
     * @param remainingSeconds seconds shown in the overlay countdown.
     */
    fun triggerCountdownOverlay(remainingSeconds: Int) {
        val intent = Intent(ACTION_SHOW_SHUTDOWN_OVERLAY).apply {
            putExtra(EXTRA_SHUTDOWN_SECONDS, remainingSeconds)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun broadcastSaveState() {
        Timber.d("$TAG: broadcasting ACTION_SAVE_STATE")
        val intent = Intent(ACTION_SAVE_STATE).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val SAVE_STATE_WAIT_MS = 2_000L
        private const val SHUTDOWN_COMMAND_WAIT_MS = 3_000L
    }
}

