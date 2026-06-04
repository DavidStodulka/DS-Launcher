package com.caros.telemetry

// ─────────────────────────────────────────────────────────────────────────────
//  TelemetryExporter.kt — CSV and JSON export of recorded telemetry sessions
//
//  Output location: /sdcard/CarOS/telemetry/
//  CSV format: RFC 4180 with header row
//  JSON format: JSON array of frame objects, one element per frame
//
//  The caller is responsible for creating a content URI via FileProvider before
//  sharing the returned File with external apps.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import com.caros.db.CarOSDatabase
import com.caros.db.TelemetryFrameEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelemetryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CarOSDatabase
) {

    companion object {
        private const val TAG         = "TelemetryExporter"
        private const val EXPORT_DIR  = "/sdcard/CarOS/telemetry"

        private val FILE_DATE_FORMAT  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        private val CSV_HEADER =
            "timestamp,speed,rpm,throttle,coolant,oil,boost,maf,gear," +
            "fuel_trim_s,fuel_trim_l,dpf,voltage,lat,lon,alt,gps_speed,lat_g,lon_g"
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Exports all telemetry frames for [sessionId] as a CSV file.
     *
     * @param sessionId The session ID to export.
     * @return The [File] written to disk. Use FileProvider to create a shareable URI.
     * @throws IllegalStateException if the session has no frames.
     */
    suspend fun exportToCSV(sessionId: Long): File = withContext(Dispatchers.IO) {
        Timber.d("$TAG: exporting session $sessionId to CSV")
        val frames = fetchFrames(sessionId)

        val dir  = ensureExportDir()
        val date = FILE_DATE_FORMAT.format(Date())
        val file = File(dir, "session_${sessionId}_$date.csv")

        BufferedWriter(FileWriter(file)).use { writer ->
            writer.write(CSV_HEADER)
            writer.newLine()

            frames.forEach { f ->
                writer.write(f.toCsvRow())
                writer.newLine()
            }
        }

        Timber.i("$TAG: CSV export complete — ${frames.size} frames → ${file.absolutePath}")
        file
    }

    /**
     * Exports all telemetry frames for [sessionId] as a JSON array file.
     *
     * @param sessionId The session ID to export.
     * @return The [File] written to disk. Use FileProvider to create a shareable URI.
     */
    suspend fun exportToJSON(sessionId: Long): File = withContext(Dispatchers.IO) {
        Timber.d("$TAG: exporting session $sessionId to JSON")
        val frames = fetchFrames(sessionId)

        val dir  = ensureExportDir()
        val date = FILE_DATE_FORMAT.format(Date())
        val file = File(dir, "session_${sessionId}_$date.json")

        val jsonArray = JSONArray()
        frames.forEach { f ->
            jsonArray.put(f.toJsonObject())
        }

        file.bufferedWriter().use { it.write(jsonArray.toString(2)) }

        Timber.i("$TAG: JSON export complete — ${frames.size} frames → ${file.absolutePath}")
        file
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun fetchFrames(sessionId: Long): List<TelemetryFrameEntity> {
        val frames = db.telemetryFrameDao().getFramesForSessionOnce(sessionId)
        if (frames.isEmpty()) {
            Timber.w("$TAG: session $sessionId has no frames")
        }
        return frames
    }

    private fun ensureExportDir(): File {
        val dir = File(EXPORT_DIR)
        if (!dir.exists()) {
            val created = dir.mkdirs()
            if (!created) {
                // Fallback to app-external files dir
                val fallback = File(context.getExternalFilesDir(null), "telemetry")
                fallback.mkdirs()
                Timber.w("$TAG: could not create $EXPORT_DIR, using ${fallback.absolutePath}")
                return fallback
            }
        }
        return dir
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Formatters
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders this frame as a single CSV row matching [CSV_HEADER].
     * Null values are written as empty fields to maintain column alignment.
     */
    private fun TelemetryFrameEntity.toCsvRow(): String {
        fun Float?.fmt()  = this?.let { "%.3f".format(it) } ?: ""
        fun Double?.fmt() = this?.let { "%.7f".format(it) } ?: ""
        fun Int?.fmt()    = this?.toString() ?: ""

        return buildString {
            append(timestamp);           append(',')
            append(speedKmh.fmt());      append(',')
            append(rpm.fmt());           append(',')
            append(throttlePct.fmt());   append(',')
            append(coolantTemp.fmt());   append(',')
            append(oilTemp.fmt());       append(',')
            append(boostKpa.fmt());      append(',')
            append(mafGs.fmt());         append(',')
            append(gear ?: "");          append(',')
            append(fuelTrimShort.fmt()); append(',')
            append(fuelTrimLong.fmt());  append(',')
            append(dpfLoadPct.fmt());    append(',')
            append(voltage.fmt());       append(',')
            append(gpsLat.fmt());        append(',')
            append(gpsLon.fmt());        append(',')
            append(gpsAlt.fmt());        append(',')
            append(gpsSpeed.fmt());      append(',')
            append(lateralG.fmt());      append(',')
            append(longitudinalG.fmt())
        }
    }

    /** Renders this frame as a JSON object with named keys. */
    private fun TelemetryFrameEntity.toJsonObject(): JSONObject = JSONObject().apply {
        put("timestamp",      timestamp)
        put("speed",          speedKmh ?: JSONObject.NULL)
        put("rpm",            rpm ?: JSONObject.NULL)
        put("throttle",       throttlePct ?: JSONObject.NULL)
        put("coolant",        coolantTemp ?: JSONObject.NULL)
        put("oil",            oilTemp ?: JSONObject.NULL)
        put("boost",          boostKpa ?: JSONObject.NULL)
        put("maf",            mafGs ?: JSONObject.NULL)
        put("gear",           gear ?: JSONObject.NULL)
        put("fuel_trim_s",    fuelTrimShort ?: JSONObject.NULL)
        put("fuel_trim_l",    fuelTrimLong ?: JSONObject.NULL)
        put("dpf",            dpfLoadPct ?: JSONObject.NULL)
        put("voltage",        voltage ?: JSONObject.NULL)
        put("lat",            gpsLat ?: JSONObject.NULL)
        put("lon",            gpsLon ?: JSONObject.NULL)
        put("alt",            gpsAlt ?: JSONObject.NULL)
        put("gps_speed",      gpsSpeed ?: JSONObject.NULL)
        put("lat_g",          lateralG ?: JSONObject.NULL)
        put("lon_g",          longitudinalG ?: JSONObject.NULL)
        put("session_id",     sessionId)
    }
}
