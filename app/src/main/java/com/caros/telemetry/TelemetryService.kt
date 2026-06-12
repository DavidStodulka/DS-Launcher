package com.caros.telemetry

// ─────────────────────────────────────────────────────────────────────────────
//  TelemetryService.kt — Foreground service for real-time telemetry recording
//
//  Data sources:
//    • CAN data  — bound to CANService via LocalBroadcastManager (intent broadcasts)
//    • GPS       — LocationManager with GPS + NETWORK providers, 500 ms update rate
//
//  Session lifecycle:
//    • Session STARTS when speed > 0 for 3 consecutive CAN readings
//    • Session ENDS when speed == 0 for 60 s OR an ACC_OFF broadcast is received
//    • After end: DrivingStyleAnalyzer.analyzeSession() is launched in the background
//
//  G-force calculation from GPS speed deltas:
//    • longitudinalG = Δv / Δt / 9.81  (m/s² / 9.81)
//    • lateralG      = speed × Δbearing / Δt / 9.81  (centripetal approximation)
//
//  The service exposes a companion StateFlow<ServiceState> for UI binding.
//  Notification channel: CarOSApplication.CHANNEL_TELEMETRY ("caros_telemetry")
// ─────────────────────────────────────────────────────────────────────────────

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.caros.can.ACTION_ACC_STATE_CHANGED
import com.caros.can.ACTION_CAN_FRAME
import com.caros.can.CANParser
import com.caros.can.EXTRA_ACC_ON
import com.caros.can.EXTRA_COOLANT_CELSIUS
import com.caros.can.EXTRA_IS_ACC_ON
import com.caros.can.EXTRA_RPM
import com.caros.can.EXTRA_SPEED_KMH
import com.caros.core.CarOSApplication
import com.caros.core.HealthModules
import com.caros.core.ServiceHealthMonitor
import com.caros.db.CarOSDatabase
import com.caros.race.AggressiveDrivingDetector
import com.caros.race.GForce
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class TelemetryService : Service() {

    // ── Injected dependencies ─────────────────────────────────────────────────

    @Inject lateinit var db: CarOSDatabase
    @Inject lateinit var drivingStyleAnalyzer: DrivingStyleAnalyzer
    @Inject lateinit var routePredictorEngine: RoutePredictorEngine
    @Inject lateinit var aggressiveDrivingDetector: AggressiveDrivingDetector
    @Inject lateinit var canParser: CANParser
    @Inject lateinit var healthMonitor: ServiceHealthMonitor

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Timber.e(t, "TelemetryService: uncaught coroutine exception")
        }
    )

    // ── Location manager ──────────────────────────────────────────────────────

    private lateinit var locationManager: LocationManager

    /** Most recent GPS fix — updated from LocationListener. */
    private val latestLocation = AtomicReference<Location?>(null)

    // ── Recorded telemetry state ──────────────────────────────────────────────

    /** ID of the currently active telemetry session in Room, or 0 if none. */
    @Volatile private var currentSessionId: Long = 0L

    /** Distance accumulated in the current session in km. */
    @Volatile private var sessionDistanceKm: Double = 0.0

    /** Last GPS location used for distance/g-force calculation. */
    @Volatile private var lastLocation: Location? = null

    /** Timestamp of the last GPS fix. */
    @Volatile private var lastLocationTimeMs: Long = 0L

    /** Last GPS bearing (degrees). */
    @Volatile private var lastBearing: Float = 0f

    /** Last GPS speed (m/s). */
    @Volatile private var lastSpeedMs: Float = 0f

    // ── Session trigger state (moving / stopped detection) ───────────────────

    /** Ring buffer of the last N speed readings for start trigger. */
    private val speedBuffer = ArrayDeque<Float>(5)
    private val CONSECUTIVE_MOVING_COUNT = 3

    /** How many consecutive zero-speed readings have we had. */
    @Volatile private var zeroSpeedCount = 0
    private val STOP_FRAME_THRESHOLD = 120  // 120 × 500 ms = 60 s

    // ── Current CAN snapshot (merged from broadcasts) ─────────────────────────

    @Volatile private var lastSpeed: Float     = 0f
    @Volatile private var lastRpm: Int         = 0
    @Volatile private var lastCoolant: Float   = 0f
    @Volatile private var lastThrottle: Float  = 0f
    @Volatile private var lastBoost: Float     = 0f
    @Volatile private var lastOilTemp: Float?  = null
    @Volatile private var lastMaf: Float?      = null
    @Volatile private var lastGear: String     = "N"
    @Volatile private var lastFuelTrimS: Float = 0f
    @Volatile private var lastFuelTrimL: Float = 0f
    @Volatile private var lastDpfLoad: Float   = 0f
    @Volatile private var lastVoltage: Float   = 12f

    // ── Computed g-forces ─────────────────────────────────────────────────────

    @Volatile private var longitudinalG: Float = 0f
    @Volatile private var lateralG: Float      = 0f

    // ── Recording loop job ────────────────────────────────────────────────────

    private var recordingJob: Job? = null
    private var stopTimerJob: Job? = null

    /**
     * Frame batch buffer — frames are accumulated here and written to Room in
     * batches of [FRAME_BATCH_SIZE] (≈ every 5 s) instead of one insert per
     * 500 ms, reducing eMMC write frequency 10×. Flushed on session end; a
     * hard process kill loses at most the last ~5 s of frames.
     */
    private val frameBuffer = ArrayList<com.caros.db.TelemetryFrameEntity>(FRAME_BATCH_SIZE)

    // ── Broadcast receivers ───────────────────────────────────────────────────

    private val canFrameReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CAN_FRAME) return
            lastSpeed   = intent.getFloatExtra(EXTRA_SPEED_KMH, lastSpeed)
            lastRpm     = intent.getIntExtra(EXTRA_RPM, lastRpm)
            lastCoolant = intent.getFloatExtra(EXTRA_COOLANT_CELSIUS, lastCoolant)
            // throttle / boost / gear etc. are not in the lightweight broadcast;
            // they would need a richer intent or a bound CANService reference.
            // The recording loop picks up what it can; other fields stay at last known.
            onSpeedUpdate(lastSpeed)
        }
    }

    private val accStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_ACC_STATE_CHANGED) return
            val accOn = intent.getBooleanExtra(EXTRA_IS_ACC_ON, true)
            if (!accOn) {
                Timber.i("TelemetryService: ACC off — ending session")
                serviceScope.launch { endCurrentSession(reason = "ACC_OFF") }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Timber.i("TelemetryService: onCreate")

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        startForeground(NOTIFICATION_ID, buildNotification("Waiting for motion…"))

        registerReceivers()
        startLocationUpdates()
        startRecordingLoop()
        startHeartbeat()

        _serviceState.value = ServiceState.RUNNING
    }

    private fun startHeartbeat() {
        val appContext = applicationContext
        healthMonitor.registerRestartAction(HealthModules.TELEMETRY) { start(appContext) }
        serviceScope.launch {
            while (true) {
                healthMonitor.heartbeat(HealthModules.TELEMETRY)
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RECORDING) {
            Timber.i("TelemetryService: stop requested via notification action")
            serviceScope.launch { endCurrentSession(reason = "USER_STOP") }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("TelemetryService: onDestroy")
        _serviceState.value = ServiceState.STOPPED
        // Intentional stop — stop watchdog monitoring so it doesn't resurrect us
        healthMonitor.unregister(HealthModules.TELEMETRY)

        recordingJob?.cancel()
        stopTimerJob?.cancel()

        unregisterReceivers()
        stopLocationUpdates()

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    //  Receivers
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerReceivers() {
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.registerReceiver(canFrameReceiver, IntentFilter(ACTION_CAN_FRAME))
        lbm.registerReceiver(accStateReceiver, IntentFilter(ACTION_ACC_STATE_CHANGED))
    }

    private fun unregisterReceivers() {
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.unregisterReceiver(canFrameReceiver)
        lbm.unregisterReceiver(accStateReceiver)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GPS
    // ─────────────────────────────────────────────────────────────────────────

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = System.currentTimeMillis()
            updateGForces(location, now)
            updateDistance(location)

            lastLocation       = location
            lastLocationTimeMs = now
            lastBearing        = location.bearing
            lastSpeedMs        = location.speed
            latestLocation.set(location)
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun startLocationUpdates() {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.forEach { provider ->
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        /* minTimeMs  = */ 500L,
                        /* minDistM   = */ 0f,
                        locationListener
                    )
                    Timber.d("TelemetryService: registered $provider location updates")
                }
            } catch (e: SecurityException) {
                Timber.w(e, "TelemetryService: location permission missing for $provider")
            } catch (e: Exception) {
                Timber.w(e, "TelemetryService: could not register $provider")
            }
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
            Timber.w(e, "TelemetryService: failed to remove location updates")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  G-force calculation
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateGForces(newLocation: Location, nowMs: Long) {
        val prev     = lastLocation ?: return
        val dtMs     = nowMs - lastLocationTimeMs
        if (dtMs <= 0) return

        val dtSec    = dtMs / 1000f
        val newSpeedMs = newLocation.speed   // GPS speed in m/s
        val speedDelta = newSpeedMs - lastSpeedMs

        // Longitudinal g: acceleration / deceleration along direction of travel
        longitudinalG = (speedDelta / dtSec) / 9.81f

        // Lateral g: centripetal acceleration from bearing change
        // a_lateral = v × dθ/dt  (v in m/s, dθ in rad/s)
        val bearingDelta = bearingDelta(lastBearing, newLocation.bearing)
        val bearingRateRad = Math.toRadians(bearingDelta.toDouble()).toFloat() / dtSec
        lateralG = (newSpeedMs * bearingRateRad) / 9.81f

        // Feed live G-force to aggression detector (only while a session is active).
        // Prefers ESP hardware G-force from CANParser when available.
        if (currentSessionId != 0L) {
            aggressiveDrivingDetector.updateFromCANFrame(
                frame    = canParser.currentFrame(),
                fallback = GForce(lateralG, longitudinalG, nowMs)
            )
        }
    }

    /** Returns the shortest signed bearing difference in degrees [-180, 180]. */
    private fun bearingDelta(from: Float, to: Float): Float {
        var delta = to - from
        while (delta > 180f)  delta -= 360f
        while (delta < -180f) delta += 360f
        return delta
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Distance accumulation
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateDistance(newLocation: Location) {
        val prev = lastLocation ?: return
        val distanceM = prev.distanceTo(newLocation)
        if (distanceM < 500f) {  // Sanity check — ignore GPS jumps > 500 m
            sessionDistanceKm += distanceM / 1000.0
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Speed update → session start/stop trigger
    // ─────────────────────────────────────────────────────────────────────────

    private fun onSpeedUpdate(speed: Float) {
        speedBuffer.addLast(speed)
        if (speedBuffer.size > 5) speedBuffer.removeFirst()

        if (speed > 0f) {
            zeroSpeedCount = 0
            stopTimerJob?.cancel()
            stopTimerJob = null

            // Start session if we have enough consecutive moving readings
            val movingCount = speedBuffer.count { it > 0f }
            if (movingCount >= CONSECUTIVE_MOVING_COUNT && currentSessionId == 0L) {
                serviceScope.launch { startNewSession() }
            }
        } else {
            zeroSpeedCount++
            if (zeroSpeedCount >= STOP_FRAME_THRESHOLD && currentSessionId != 0L && stopTimerJob == null) {
                serviceScope.launch { endCurrentSession(reason = "SPEED_ZERO_60S") }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Session management
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun startNewSession() = withContext(Dispatchers.IO) {
        if (currentSessionId != 0L) return@withContext

        val gps = latestLocation.get()
        val entity = com.caros.db.TelemetrySessionEntity(
            startTime  = System.currentTimeMillis(),
            startLat   = gps?.latitude,
            startLon   = gps?.longitude
        )
        currentSessionId   = db.telemetrySessionDao().insert(entity)
        sessionDistanceKm  = 0.0
        lastLocation       = null
        aggressiveDrivingDetector.resetSession()

        Timber.i("TelemetryService: session started, id=$currentSessionId")
        updateNotification("Recording session #$currentSessionId")
    }

    private suspend fun endCurrentSession(reason: String) = withContext(Dispatchers.IO) {
        val sid = currentSessionId
        if (sid == 0L) return@withContext

        Timber.i("TelemetryService: ending session $sid — reason=$reason")
        val endTime = System.currentTimeMillis()

        flushFrameBuffer()
        db.telemetrySessionDao().closeSession(sid, endTime, sessionDistanceKm)
        currentSessionId  = 0L
        sessionDistanceKm = 0.0

        updateNotification("Waiting for motion…")

        // Launch post-session analysis and route learning without blocking the service
        serviceScope.launch(Dispatchers.IO) {
            try {
                Timber.d("TelemetryService: analysing session $sid")
                drivingStyleAnalyzer.analyzeSession(sid)
                Timber.d("TelemetryService: analysis complete for session $sid")
            } catch (e: Exception) {
                Timber.e(e, "TelemetryService: analysis failed for session $sid")
            }
        }

        // Record destination for predictive navigation
        val finalGps = latestLocation.get()
        if (finalGps != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    routePredictorEngine.recordTrip(finalGps.latitude, finalGps.longitude)
                } catch (e: Exception) {
                    Timber.w(e, "TelemetryService: route prediction record failed")
                }
            }
        }
    }

    /** Write any buffered frames to Room (called before closing a session). */
    private suspend fun flushFrameBuffer() {
        val batch = synchronized(frameBuffer) {
            if (frameBuffer.isEmpty()) null
            else frameBuffer.toList().also { frameBuffer.clear() }
        } ?: return
        try {
            db.telemetryFrameDao().insertAll(batch)
        } catch (e: Exception) {
            Timber.w(e, "TelemetryService: failed to flush frame buffer")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Recording loop — buffers frames, batch-writes to DB every ~5 s
    // ─────────────────────────────────────────────────────────────────────────

    private fun startRecordingLoop() {
        recordingJob = serviceScope.launch(Dispatchers.IO) {
            while (true) {
                delay(RECORDING_INTERVAL_MS)
                val sid = currentSessionId
                if (sid == 0L) continue

                val gps = latestLocation.get()
                val frame = com.caros.db.TelemetryFrameEntity(
                    timestamp      = System.currentTimeMillis(),
                    speedKmh       = lastSpeed,
                    rpm            = lastRpm,
                    throttlePct    = lastThrottle,
                    coolantTemp    = lastCoolant,
                    oilTemp        = lastOilTemp,
                    boostKpa       = lastBoost,
                    mafGs          = lastMaf,
                    gear           = lastGear,
                    fuelTrimShort  = lastFuelTrimS,
                    fuelTrimLong   = lastFuelTrimL,
                    dpfLoadPct     = lastDpfLoad,
                    voltage        = lastVoltage,
                    gpsLat         = gps?.latitude,
                    gpsLon         = gps?.longitude,
                    gpsAlt         = gps?.altitude,
                    gpsSpeed       = gps?.speed,
                    lateralG       = lateralG,
                    longitudinalG  = longitudinalG,
                    sessionId      = sid
                )

                val batch: List<com.caros.db.TelemetryFrameEntity>? = synchronized(frameBuffer) {
                    frameBuffer.add(frame)
                    if (frameBuffer.size >= FRAME_BATCH_SIZE) {
                        val copy = frameBuffer.toList()
                        frameBuffer.clear()
                        copy
                    } else null
                }
                if (batch != null) {
                    try {
                        db.telemetryFrameDao().insertAll(batch)
                    } catch (e: Exception) {
                        Timber.w(e, "TelemetryService: failed to insert frame batch for session $sid")
                    }
                }

                // Update notification with current distance periodically
                if (System.currentTimeMillis() % NOTIFICATION_UPDATE_INTERVAL < RECORDING_INTERVAL_MS) {
                    updateNotification("Recording — %.1f km".format(sessionDistanceKm))
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Foreground notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, TelemetryService::class.java).apply {
                action = ACTION_STOP_RECORDING
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CarOSApplication.CHANNEL_TELEMETRY)
            .setContentTitle("CarOS Recording")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Companion
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val NOTIFICATION_ID             = 2001
        private const val RECORDING_INTERVAL_MS       = 500L
        private const val NOTIFICATION_UPDATE_INTERVAL = 5_000L
        private const val HEARTBEAT_INTERVAL_MS       = 5_000L
        private const val FRAME_BATCH_SIZE            = 10

        const val ACTION_STOP_RECORDING = "com.caros.telemetry.ACTION_STOP_RECORDING"

        /** Possible operational states of the service. */
        enum class ServiceState { IDLE, RUNNING, STOPPED }

        private val _serviceState = MutableStateFlow(ServiceState.IDLE)

        /**
         * Static [StateFlow] that any ViewModel can observe to know whether the
         * telemetry service is currently running.
         */
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        /** Convenience: start the service from any Context. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TelemetryService::class.java))
        }

        /** Convenience: stop the service from any Context. */
        fun stop(context: Context) {
            context.stopService(Intent(context, TelemetryService::class.java))
        }
    }
}
