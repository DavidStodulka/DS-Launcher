package com.caros.vcds

// ─────────────────────────────────────────────────────────────────────────────
//  OBDSessionPlayer.kt — Replays a previously recorded OBD session JSON file,
//  returning canned responses to OBD commands so the full VCDS/diagnostic UI
//  can be tested without a physical adapter or a running vehicle.
//
//  Response lookup order:
//    1. Exact command match (case-insensitive, trimmed)
//    2. Substring match — the recorded key contains or is contained by the query
//    3. null → OBDConnection falls back to built-in mockResponse()
// ─────────────────────────────────────────────────────────────────────────────

import org.json.JSONArray
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OBDSessionPlayer @Inject constructor() {

    /** Map of uppercase trimmed command → recorded response. */
    private val responses = mutableMapOf<String, String>()

    @Volatile var isPlaying = false
        private set

    /** Loaded file name for display in settings UI. */
    var loadedFileName: String = ""
        private set

    /**
     * Load a JSON file produced by [OBDSessionRecorder].
     *
     * @return true on success, false if the file could not be parsed.
     */
    fun load(file: File): Boolean {
        return runCatching {
            val arr = JSONArray(file.readText())
            val map = mutableMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val obj  = arr.getJSONObject(i)
                val cmd  = obj.getString("cmd").trim().uppercase()
                val resp = obj.getString("resp")
                map[cmd] = resp
            }
            responses.clear()
            responses.putAll(map)
            loadedFileName = file.name
            Timber.i("OBDSessionPlayer: loaded %d entries from %s", responses.size, file.name)
            true
        }.getOrElse { e ->
            Timber.e(e, "OBDSessionPlayer: load failed for %s", file.name)
            false
        }
    }

    /** Begin serving responses for subsequent [respond] calls. */
    fun start() {
        if (responses.isEmpty()) {
            Timber.w("OBDSessionPlayer: start called but no session loaded")
            return
        }
        isPlaying = true
        Timber.i("OBDSessionPlayer: playback started (%d entries)", responses.size)
    }

    /** Stop playback and clear loaded data. */
    fun stop() {
        isPlaying = false
        responses.clear()
        loadedFileName = ""
        Timber.i("OBDSessionPlayer: stopped")
    }

    /**
     * Look up the response for [command].
     *
     * @return The recorded response string, or null if no match found (caller should
     *         fall back to the built-in mock response).
     */
    fun respond(command: String): String? {
        if (!isPlaying) return null
        val key = command.trim().uppercase()

        // Exact match
        responses[key]?.let { return it }

        // Substring match — handles partial commands like "0105" matching "AT Z\r0105\r"
        val subMatch = responses.entries.firstOrNull { (k, _) ->
            key.contains(k) || k.contains(key)
        }
        return subMatch?.value
    }
}
