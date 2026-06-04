package com.caros.elevation

// ─────────────────────────────────────────────────────────────────────────────
//  GPXExporter.kt — Serialises a list of ElevationPoints to a standards-
//  compliant GPX 1.1 XML string or writes it to /sdcard/CarOS/routes/.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GPXExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * ISO-8601 UTC formatter for GPX `<time>` elements.
     * Thread-local because [SimpleDateFormat] is not thread-safe.
     */
    private val dateFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Convert a list of [ElevationPoint]s to a GPX 1.1 XML string.
     *
     * The output contains a single `<trk>` with one `<trkseg>`.  Each point
     * becomes a `<trkpt>` with `<ele>` and `<time>` children.
     *
     * @param points List of track points to encode
     * @param name   Track name written to the `<name>` element
     * @return       Well-formed GPX 1.1 XML as a String
     */
    fun toGPXString(points: List<ElevationPoint>, name: String): String {
        val sb = StringBuilder()
        val fmt = dateFormat.get()!!

        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine(
            """<gpx version="1.1" creator="CarOS DS-Launcher" """ +
                    """xmlns="http://www.topografix.com/GPX/1/1" """ +
                    """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                    """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 """ +
                    """http://www.topografix.com/GPX/1/1/gpx.xsd">"""
        )
        sb.appendLine("""  <metadata><name>${escapeXml(name)}</name></metadata>""")
        sb.appendLine("""  <trk>""")
        sb.appendLine("""    <name>${escapeXml(name)}</name>""")
        sb.appendLine("""    <trkseg>""")

        for (p in points) {
            val timeStr = fmt.format(Date(p.timestamp))
            sb.appendLine(
                """      <trkpt lat="${p.lat}" lon="${p.lon}">""" +
                        """<ele>${p.altM}</ele>""" +
                        """<time>$timeStr</time>""" +
                        """</trkpt>"""
            )
        }

        sb.appendLine("""    </trkseg>""")
        sb.appendLine("""  </trk>""")
        sb.append("""</gpx>""")

        return sb.toString()
    }

    /**
     * Write a GPX file to `/sdcard/CarOS/routes/` and return the resulting [File].
     *
     * The filename format is `<sanitised-name>_<epochMillis>.gpx`.
     *
     * @param points List of track points to encode
     * @param name   Human-readable route name (used in both filename and GPX content)
     * @return       The written [File] reference
     */
    fun exportToFile(points: List<ElevationPoint>, name: String): File {
        val dir = File("/sdcard/CarOS/routes/").also { it.mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        val file = File(dir, "${safeName}_${System.currentTimeMillis()}.gpx")
        file.writeText(toGPXString(points, name))
        return file
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Escape characters that are invalid in XML text content. */
    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
