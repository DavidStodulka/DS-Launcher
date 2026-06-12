package com.caros.ui.map

// ─────────────────────────────────────────────────────────────────────────────
//  MapActivity.kt — Offline map with telemetry route overlay using OSMDroid.
//
//  Features:
//   • Displays saved telemetry routes as speed-coloured polylines
//   • Speed colour gradient: green (<60) → yellow (<100) → red (≥120 km/h)
//   • G-force mode: green (<0.2 g) → yellow (<0.4 g) → red (≥0.5 g)
//   • Session selector spinner — loads the last 10 sessions
//   • Offline-first: tiles cached to /sdcard/CarOS/maps/cache
// ─────────────────────────────────────────────────────────────────────────────

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.caros.R
import com.caros.db.CarOSDatabase
import com.caros.db.TelemetrySessionEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class MapActivity : AppCompatActivity() {

    @Inject lateinit var db: CarOSDatabase

    private lateinit var mapView: MapView
    private lateinit var sessionSpinner: Spinner
    private lateinit var colorModeBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var infoText: TextView

    private var sessions: List<TelemetrySessionEntity> = emptyList()
    private var colorByGForce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure OSMDroid
        Configuration.getInstance().apply {
            osmdroidBasePath  = File("/sdcard/CarOS/maps")
            osmdroidTileCache = File("/sdcard/CarOS/maps/cache")
            userAgentValue    = packageName
        }

        setContentView(createLayout())

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(13.0)
        mapView.controller.setCenter(GeoPoint(50.0755, 14.4378)) // Prague default

        loadSessions()
    }

    private fun createLayout(): View {
        // Build layout programmatically to avoid XML dependency
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }

        // Toolbar row
        val toolbar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.parseColor("#16213E"))
        }

        sessionSpinner = Spinner(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        colorModeBtn = Button(this).apply {
            text = "Rychlost"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0F3460"))
            setPadding(16, 8, 16, 8)
            setOnClickListener { toggleColorMode() }
        }

        infoText = TextView(this).apply {
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 10f
            setPadding(8, 0, 8, 0)
        }

        toolbar.addView(sessionSpinner)
        toolbar.addView(colorModeBtn)
        root.addView(toolbar)
        root.addView(infoText)

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = android.view.Gravity.CENTER }
        }
        root.addView(progressBar)

        mapView = MapView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(mapView)

        return root
    }

    private fun loadSessions() {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) {
                db.telemetrySessionDao().getRecentSessions(10)
            }
            sessions = all
            val labels = all.map { session ->
                val date = java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(session.startTime))
                "$date  (${"%.1f km".format(session.distanceKm)})"
            }
            val adapter = ArrayAdapter(this@MapActivity, android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            sessionSpinner.adapter = adapter
            sessionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    loadRouteForSession(sessions[pos])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            if (all.isNotEmpty()) loadRouteForSession(all[0])
        }
    }

    private fun loadRouteForSession(session: TelemetrySessionEntity) {
        progressBar.visibility = View.VISIBLE
        mapView.overlayManager.clear()

        lifecycleScope.launch {
            val frames = withContext(Dispatchers.IO) {
                db.telemetryFrameDao().getFramesForSessionOnce(session.id)
            }

            val validPoints = frames.filter { it.gpsLat != null && it.gpsLon != null }
            if (validPoints.isEmpty()) {
                infoText.text = "Žádná GPS data pro tuto jízdu"
                progressBar.visibility = View.GONE
                return@launch
            }

            // Subsample to max 500 points for performance
            val step = maxOf(1, validPoints.size / 500)
            val sampled = validPoints.filterIndexed { i, _ -> i % step == 0 }

            // Build coloured segments — each consecutive pair is one Polyline
            for (i in 0 until sampled.size - 1) {
                val a = sampled[i]
                val b = sampled[i + 1]
                val color = if (colorByGForce) {
                    gForceColor(maxOf(abs(a.lateralG ?: 0f), abs(a.longitudinalG ?: 0f)))
                } else {
                    speedColor(a.speedKmh ?: 0f)
                }
                val line = Polyline(mapView).apply {
                    setPoints(listOf(
                        GeoPoint(a.gpsLat!!, a.gpsLon!!),
                        GeoPoint(b.gpsLat!!, b.gpsLon!!)
                    ))
                    outlinePaint.color = color
                    outlinePaint.strokeWidth = 8f
                    isEnabled = false
                }
                mapView.overlayManager.add(line)
            }

            // Auto-zoom to route bounding box
            val lats = sampled.map { it.gpsLat!! }
            val lons = sampled.map { it.gpsLon!! }
            val bbox = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
            mapView.post {
                mapView.zoomToBoundingBox(bbox.increaseByScale(1.1f), true)
            }

            infoText.text = "${validPoints.size} GPS bodů, %.1f km".format(
                session.distanceKm ?: 0.0
            )
            progressBar.visibility = View.GONE
            mapView.invalidate()

            Timber.d("MapActivity: loaded %d points for session %d", sampled.size, session.id)
        }
    }

    private fun toggleColorMode() {
        colorByGForce = !colorByGForce
        colorModeBtn.text = if (colorByGForce) "G-force" else "Rychlost"
        val current = sessions.getOrNull(sessionSpinner.selectedItemPosition)
        if (current != null) loadRouteForSession(current)
    }

    private fun speedColor(kmh: Float): Int = when {
        kmh < 60f  -> Color.parseColor("#4CAF50")
        kmh < 100f -> Color.parseColor("#FFC107")
        kmh < 120f -> Color.parseColor("#FF5722")
        else       -> Color.parseColor("#F44336")
    }

    private fun gForceColor(g: Float): Int = when {
        g < 0.20f -> Color.parseColor("#4CAF50")
        g < 0.40f -> Color.parseColor("#FFC107")
        g < 0.55f -> Color.parseColor("#FF5722")
        else      -> Color.parseColor("#F44336")
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mapView.onDetach()
        super.onDestroy()
    }

    companion object {
        fun launch(context: android.content.Context) {
            context.startActivity(
                android.content.Intent(context, MapActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
