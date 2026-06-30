package com.caros.vcds

// ─────────────────────────────────────────────────────────────────────────────
//  OBDSessionRecorder.kt — Records live OBD command/response pairs to a JSON
//  file so they can later be replayed via OBDSessionPlayer for testing the
//  VCDS UI without a physical ELM327 adapter.
//
//  Files are saved to /sdcard/CarOS/telemetry/obd_session_<timestamp>.json.
//  Recording wraps OBDConnection.sendCommand() — see VCDSManager.kt.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OBDSessionRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutex   = Mutex()
    private val entries = mutableListOf<Pair<String, String>>()

    @Volatile var isRecording = false
        private set

    /** Start a new recording. Clears any previous in-memory entries. */
    suspend fun startRecording() = mutex.withLock {
        entries.clear()
        isRecording = true
        Timber.i("OBDSessionRecorder: recording started")
    }

    /**
     * Record one command/response pair.  No-op when not recording.
     * Called from the OBD I/O path after each real [sendCommand] call.
     */
    suspend fun record(command: String, response: String) {
        if (!isRecording) return
        mutex.withLock {
            entries.add(command.trim() to (response ?: ""))
        }
    }

    /**
     * Stop recording and persist entries to JSON.
     *
     * @return The written file, or null if there was nothing to save or an I/O error occurred.
     */
    suspend fun stopAndSave(): File? = mutex.withLock {
        isRecording = false
        if (entries.isEmpty()) {
            Timber.w("OBDSessionRecorder: nothing recorded")
            return@withLock null
        }
        val dir  = File("/sdcard/CarOS/telemetry").also { it.mkdirs() }
        val file = File(dir, "obd_session_${System.currentTimeMillis()}.json")
        runCatching {
            val arr = JSONArray()
            entries.forEach { (cmd, resp) ->
                arr.put(JSONObject().put("cmd", cmd).put("resp", resp))
            }
            file.writeText(arr.toString(2))
            Timber.i("OBDSessionRecorder: saved %d entries → %s", entries.size, file.name)
            file
        }.getOrElse { e ->
            Timber.e(e, "OBDSessionRecorder: save failed")
            null
        }
    }

    /** List previously saved OBD session JSON files, newest first. */
    fun listSavedSessions(): List<File> =
        File("/sdcard/CarOS/telemetry")
            .listFiles { f -> f.name.startsWith("obd_session_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
}
