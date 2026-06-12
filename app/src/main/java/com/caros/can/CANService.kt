package com.caros.can

// ─────────────────────────────────────────────────────────────────────────────
//  CANService.kt — Android foreground Service for CAN bus data acquisition
//
//  Runs from boot (started by a BroadcastReceiver listening to BOOT_COMPLETED).
//  Reads frames via CANReader (real hardware) or MockCANSource (dev builds),
//  parses them with CANParser, and distributes the data through:
//    1. StateFlow<CANFrame>  — for Compose / ViewModel observers
//    2. LocalBroadcastManager — for legacy Activity / Fragment receivers
//    3. IBinder / ServiceConnection — for bound clients needing a direct ref
// ─────────────────────────────────────────────────────────────────────────────

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.caros.BuildConfig
import com.caros.R
import com.caros.core.CarOSApplication
import com.caros.core.HealthModules
import com.caros.core.ServiceHealthMonitor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

// ── Intent extra / action constants ──────────────────────────────────────────

const val ACTION_CAN_FRAME         = "com.caros.can.ACTION_CAN_FRAME"
const val EXTRA_SPEED_KMH          = "speed_kmh"
const val EXTRA_RPM                = "rpm"
const val EXTRA_COOLANT_CELSIUS    = "coolant_celsius"
const val EXTRA_ACC_ON             = "acc_on"

const val ACTION_ACC_STATE_CHANGED = "com.caros.can.ACTION_ACC_STATE_CHANGED"
const val EXTRA_IS_ACC_ON          = "is_acc_on"

private const val NOTIFICATION_ID  = 1001
private const val FOREGROUND_STOP_ACTION = "com.caros.can.STOP"
private const val HEARTBEAT_INTERVAL_MS  = 5_000L

@AndroidEntryPoint
class CANService : Service() {

    // ── Injected dependencies ─────────────────────────────────────────────────

    @Inject lateinit var canReader:   CANReader
    @Inject lateinit var canParser:   CANParser
    @Inject lateinit var canLogger:   CANLogger
    @Inject lateinit var mockSource:  MockCANSource
    @Inject lateinit var healthMonitor: ServiceHealthMonitor

    // ── Coroutine scope tied to service lifetime ──────────────────────────────

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Timber.e(t, "CANService: uncaught exception in service scope")
        }
    )

    // ── State ─────────────────────────────────────────────────────────────────

    private val _canFrame = MutableStateFlow(CANFrame.EMPTY)

    /** Latest decoded CAN snapshot — observe from any ViewModel or Composable. */
    val canFrame: StateFlow<CANFrame> = _canFrame.asStateFlow()

    private var readerJob:     Job? = null
    private var lastAccState:  Boolean? = null

    // ── Binder ────────────────────────────────────────────────────────────────

    inner class CANBinder : Binder() {
        fun getService(): CANService = this@CANService
    }
    private val binder = CANBinder()

    override fun onBind(intent: Intent): IBinder = binder

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Timber.i("CANService: onCreate")
        canParser.reset()
        canLogger.start()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
        startHeartbeat()
    }

    private fun startHeartbeat() {
        val appContext = applicationContext
        healthMonitor.registerRestartAction(HealthModules.CAN) { start(appContext) }
        serviceScope.launch {
            while (true) {
                healthMonitor.heartbeat(HealthModules.CAN)
                kotlinx.coroutines.delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FOREGROUND_STOP_ACTION) {
            Timber.i("CANService: stop requested via notification")
            stopSelf()
            return START_NOT_STICKY
        }
        startReaderPipeline()
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("CANService: onDestroy")
        // Intentional stop — stop watchdog monitoring so it doesn't resurrect us
        healthMonitor.unregister(HealthModules.CAN)
        readerJob?.cancel()
        canLogger.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Reader pipeline ───────────────────────────────────────────────────────

    private fun startReaderPipeline() {
        readerJob?.cancel()

        val useMock = shouldUseMock()
        Timber.i("CANService: starting reader pipeline (mock=%b)", useMock)
        updateNotification(if (useMock) "Mock mode" else "Hardware connected")

        val source = if (useMock) {
            mockSource.rawLines()
        } else {
            canReader.lines()
        }

        readerJob = serviceScope.launch {
            source
                .onEach { line ->
                    val frame = canParser.parseFrame(line) ?: return@onEach
                    _canFrame.value = frame
                    broadcastFrame(frame)
                    handleAccStateChange(frame)
                    updateNotificationFromFrame(frame)
                }
                .catch { e ->
                    Timber.e(e, "CANService: source flow error")
                    updateNotification("Error — reconnecting…")
                    // The CANReader handles reconnect internally; mock never throws
                }
                .collect {}
        }
    }

    // ── ACC state tracking ────────────────────────────────────────────────────

    private fun handleAccStateChange(frame: CANFrame) {
        val current = frame.accState?.isOn ?: return
        if (current == lastAccState) return
        lastAccState = current

        Timber.i("CANService: ACC state changed → %b", current)

        val intent = Intent(ACTION_ACC_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_ACC_ON, current)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // ── LocalBroadcastManager ─────────────────────────────────────────────────

    private fun broadcastFrame(frame: CANFrame) {
        val intent = Intent(ACTION_CAN_FRAME).apply {
            putExtra(EXTRA_SPEED_KMH,       frame.vehicleSpeed?.kmh ?: 0f)
            putExtra(EXTRA_RPM,             frame.engineRpm?.rpm ?: 0)
            putExtra(EXTRA_COOLANT_CELSIUS, frame.coolantTemp?.celsius ?: 0f)
            putExtra(EXTRA_ACC_ON,          frame.accState?.isOn ?: false)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun buildNotification(statusText: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CANService::class.java).apply { action = FOREGROUND_STOP_ACTION },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Launch intent — opens launcher main activity
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CarOSApplication.CHANNEL_CAN_SERVICE)
            .setContentTitle("CarOS CAN Bus")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent
            )
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun updateNotificationFromFrame(frame: CANFrame) {
        // Throttle notification updates — only update every ~2 s of real data
        val speed  = frame.vehicleSpeed?.kmh?.toInt() ?: 0
        val rpm    = frame.engineRpm?.rpm ?: 0
        if (frame.timestamp % 2000L < 150L) {
            updateNotification("%.0f km/h  |  %d RPM".format(speed.toFloat(), rpm))
        }
    }

    // ── Mock / hardware selection ─────────────────────────────────────────────

    private fun shouldUseMock(): Boolean {
        // 1. Build-time flag (set in build.gradle as buildConfigField)
        if (BuildConfig.DEBUG && !File("/dev/ttyS1").exists() && !File("/dev/ttyUSB0").exists()) {
            return true
        }
        // 2. Explicit override via system property (useful for testing on emulator)
        val prop = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, "caros.use_mock_can", "false") as String
        }.getOrDefault("false")
        if (prop == "true") return true

        // 3. Neither device path accessible → fall back to mock
        return !File("/dev/ttyS1").exists() && !File("/dev/ttyUSB0").exists()
    }

    // ── Companion / static helpers ────────────────────────────────────────────

    companion object {
        /** Convenience: start the service from any Context. */
        fun start(context: Context) {
            val intent = Intent(context, CANService::class.java)
            context.startForegroundService(intent)
        }

        /** Convenience: stop the service from any Context. */
        fun stop(context: Context) {
            context.stopService(Intent(context, CANService::class.java))
        }
    }
}
