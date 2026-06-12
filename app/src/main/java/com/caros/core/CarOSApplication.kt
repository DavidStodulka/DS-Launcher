package com.caros.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class CarOSApplication : Application(), Configuration.Provider {

    /** Application-level coroutine scope tied to the process lifetime. */
    val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + globalExceptionHandler
    )

    @Inject
    lateinit var rootManager: RootManager

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** On-demand WorkManager init (the default initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this

        initTimber()
        initCrashHandler()
        initNotificationChannels()
        initCarOSDirectories()
        initRootAccess()
        scheduleDbMaintenance()
    }

    // -------------------------------------------------------------------------
    //  Fatal crash reporting — writes the stacktrace to /sdcard before dying
    // -------------------------------------------------------------------------

    private fun initCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File("/sdcard/CarOS/crashreports")
                if (!dir.exists()) dir.mkdirs()
                File(dir, "fatal_${System.currentTimeMillis()}.txt").writeText(
                    buildString {
                        appendLine("CarOS fatal crash")
                        appendLine("Version: ${com.caros.BuildConfig.VERSION_NAME}")
                        appendLine("Thread:  ${thread.name}")
                        appendLine("Time:    ${java.util.Date()}")
                        appendLine()
                        appendLine(throwable.stackTraceToString())
                    }
                )
            } catch (_: Exception) {
                // Never let the crash reporter itself crash
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // -------------------------------------------------------------------------
    //  Periodic database maintenance (telemetry pruning)
    // -------------------------------------------------------------------------

    private fun scheduleDbMaintenance() {
        try {
            com.caros.db.DbMaintenanceWorker.schedule(this)
        } catch (e: Exception) {
            Timber.w(e, "Failed to schedule DB maintenance worker")
        }
    }

    // -------------------------------------------------------------------------
    //  Timber logging
    // -------------------------------------------------------------------------

    private fun initTimber() {
        if (com.caros.BuildConfig.ENABLE_LOGGING) {
            Timber.plant(CarOSDebugTree())
        } else {
            Timber.plant(CrashReportingTree())
        }
        Timber.i("CarOS application starting — version %s", com.caros.BuildConfig.VERSION_NAME)
    }

    // -------------------------------------------------------------------------
    //  Notification channels (required for foreground services on API 26+)
    // -------------------------------------------------------------------------

    private fun initNotificationChannels() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channels = listOf(
            NotificationChannel(
                CHANNEL_CAN_SERVICE,
                "CAN Bus Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background CAN bus data acquisition service"
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_TELEMETRY,
                "Telemetry Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Vehicle telemetry and GPS tracking service"
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_MEDIA,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio and media playback controls"
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_CALLS,
                "Phone Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming and active phone call notifications"
                setShowBadge(true)
                enableVibration(true)
            },
            NotificationChannel(
                CHANNEL_ALERTS,
                "System Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical vehicle alerts and warnings"
                setShowBadge(true)
                enableVibration(true)
            }
        )

        channels.forEach { channel ->
            notificationManager.createNotificationChannel(channel)
            Timber.d("Created notification channel: %s", channel.id)
        }
    }

    // -------------------------------------------------------------------------
    //  Filesystem initialisation
    // -------------------------------------------------------------------------

    private fun initCarOSDirectories() {
        applicationScope.launch(Dispatchers.IO) {
            val directories = listOf(
                "/sdcard/CarOS",
                "/sdcard/CarOS/logs",
                "/sdcard/CarOS/telemetry",
                "/sdcard/CarOS/media",
                "/sdcard/CarOS/maps",
                "/sdcard/CarOS/config",
                "/sdcard/CarOS/recordings",
                "/sdcard/CarOS/crashreports"
            )
            directories.forEach { path ->
                val dir = File(path)
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    if (created) {
                        Timber.d("Created directory: %s", path)
                    } else {
                        Timber.w("Failed to create directory: %s", path)
                    }
                }
            }

            // Also create app-private directories
            val privateDirectories = listOf(
                File(filesDir, "room"),
                File(filesDir, "prefs"),
                File(cacheDir, "tiles")
            )
            privateDirectories.forEach { dir ->
                if (!dir.exists()) dir.mkdirs()
            }
        }
    }

    // -------------------------------------------------------------------------
    //  Root access initialisation
    // -------------------------------------------------------------------------

    private fun initRootAccess() {
        applicationScope.launch(Dispatchers.IO) {
            val hasRoot = rootManager.checkRootAvailability()
            if (hasRoot) {
                Timber.i("Root access confirmed — initialising privileged subsystems")
                rootManager.setupDevicePermissions()
            } else {
                Timber.w("Root access not available — some features will be limited")
            }
        }
    }

    // -------------------------------------------------------------------------
    //  Timber tree implementations
    // -------------------------------------------------------------------------

    private class CarOSDebugTree : Timber.DebugTree() {
        override fun createStackElementTag(element: StackTraceElement): String {
            return "CarOS/${element.fileName?.removeSuffix(".kt") ?: "Unknown"}:${element.lineNumber}"
        }

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            super.log(priority, tag, message, t)
            // Optionally write to /sdcard/CarOS/logs/ for debug builds
            if (priority >= Log.WARN) {
                appendToLogFile(priority, tag, message, t)
            }
        }

        private fun appendToLogFile(priority: Int, tag: String?, message: String, t: Throwable?) {
            try {
                val logDir = File("/sdcard/CarOS/logs")
                if (logDir.exists()) {
                    val level = when (priority) {
                        Log.WARN  -> "W"
                        Log.ERROR -> "E"
                        Log.ASSERT -> "A"
                        else       -> "I"
                    }
                    val logFile = File(logDir, "caros_${currentDateStamp()}.log")
                    logFile.appendText("${System.currentTimeMillis()} $level/$tag: $message\n")
                    t?.let { logFile.appendText("  Exception: ${it.stackTraceToString()}\n") }
                }
            } catch (_: Exception) {
                // Swallow file I/O errors in the logger
            }
        }

        private fun currentDateStamp(): String {
            val cal = java.util.Calendar.getInstance()
            return "%04d%02d%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }

    private class CrashReportingTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < Log.WARN) return
            // In production, write errors to crash reports directory
            if (priority >= Log.ERROR) {
                try {
                    val crashDir = File("/sdcard/CarOS/crashreports")
                    if (crashDir.exists()) {
                        val file = File(crashDir, "crash_${System.currentTimeMillis()}.txt")
                        file.writeText(
                            "Time: ${System.currentTimeMillis()}\n" +
                                "Tag: $tag\n" +
                                "Message: $message\n" +
                                (t?.let { "Exception:\n${it.stackTraceToString()}" } ?: "")
                        )
                    }
                } catch (_: Exception) {
                    // Last-resort swallow
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    //  Companion
    // -------------------------------------------------------------------------

    companion object {
        // Notification channel IDs
        const val CHANNEL_CAN_SERVICE = "caros_can_service"
        const val CHANNEL_TELEMETRY   = "caros_telemetry"
        const val CHANNEL_MEDIA       = "caros_media"
        const val CHANNEL_CALLS       = "caros_calls"
        const val CHANNEL_ALERTS      = "caros_alerts"

        @Volatile
        private var instance: CarOSApplication? = null

        fun get(): CarOSApplication = instance
            ?: error("CarOSApplication not yet initialised")

        private val globalExceptionHandler = CoroutineExceptionHandler { context, throwable ->
            Timber.e(throwable, "Uncaught exception in application coroutine scope: %s", context)
        }
    }
}
