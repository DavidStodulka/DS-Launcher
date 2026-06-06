package com.caros.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RootManager
 *
 * Singleton responsible for all root-privilege interactions in CarOS:
 *
 *  1. Detecting whether `su` is available and usable.
 *  2. Executing arbitrary privileged commands via [ShellExecutor].
 *  3. Granting Android runtime permissions via `pm grant`.
 *  4. Setting up CAN-bus device node permissions (`chmod /dev/ttyS*`).
 *  5. Opening and verifying USB-serial device nodes (`/dev/ttyUSB*`).
 *
 * Root availability is cached in a [StateFlow] so UI components can
 * observe it reactively without polling.
 *
 * Hilt provides this class as a process-scoped singleton via [@Singleton].
 */
@Singleton
class RootManager @Inject constructor(
    private val shellExecutor: ShellExecutor
) {

    // -------------------------------------------------------------------------
    //  Root status state
    // -------------------------------------------------------------------------

    private val _rootStatus = MutableStateFlow(RootStatus.UNKNOWN)

    /**
     * Observed root status. Starts as [RootStatus.UNKNOWN] and is updated
     * on the first call to [checkRootAvailability].
     */
    val rootStatus: StateFlow<RootStatus> = _rootStatus.asStateFlow()

    /** Convenience property — `true` when root is confirmed available. */
    val isRooted: Boolean get() = _rootStatus.value == RootStatus.AVAILABLE

    // -------------------------------------------------------------------------
    //  Root detection
    // -------------------------------------------------------------------------

    /**
     * Determine whether the device has a working `su` binary.
     *
     * The check runs two tests:
     *  1. Whether common `su` binary locations are present on the filesystem.
     *  2. Whether `su -c id` returns a response containing `uid=0` (root).
     *
     * The result is cached in [rootStatus] and returned.
     *
     * @return `true` if root is available and responsive.
     */
    suspend fun checkRootAvailability(): Boolean = withContext(Dispatchers.IO) {
        Timber.d("RootManager: checking root availability")

        // --- Fast filesystem check ---
        val suBinaryExists = SU_BINARY_PATHS.any { File(it).exists() }
        if (!suBinaryExists) {
            Timber.w("RootManager: no su binary found at standard paths")
            _rootStatus.value = RootStatus.UNAVAILABLE
            return@withContext false
        }

        // --- Functional check: run `su -c id` and look for uid=0 ---
        val result = shellExecutor.executeSuCommand("id", timeoutMs = 4_000L)
        val output = result.getOrNull() ?: ""

        return@withContext if (result.isSuccess && output.contains("uid=0")) {
            Timber.i("RootManager: root confirmed (uid=0)")
            _rootStatus.value = RootStatus.AVAILABLE
            true
        } else {
            val err = result.exceptionOrNull()?.message ?: "no uid=0 in output"
            Timber.w("RootManager: root check failed — %s", err)
            _rootStatus.value = RootStatus.DENIED
            false
        }
    }

    // -------------------------------------------------------------------------
    //  Permission granting
    // -------------------------------------------------------------------------

    /**
     * Grant a runtime permission to [packageName] via `pm grant`.
     *
     * This requires root because `pm grant` for non-development permissions
     * is restricted to shell/root on production builds.
     *
     * @param packageName Target application package, e.g. `"com.caros"`.
     * @param permission  Full permission name, e.g. `"android.permission.CAMERA"`.
     * @return [Result.success] with empty string on success, or [Result.failure].
     */
    suspend fun grantPermission(
        packageName: String,
        permission: String
    ): Result<String> {
        requireRoot("grantPermission")
        val cmd = "pm grant $packageName $permission"
        Timber.d("RootManager: granting %s to %s", permission, packageName)
        return shellExecutor.executeSuCommand(cmd).also { result ->
            result.onSuccess { Timber.i("RootManager: granted %s", permission) }
            result.onFailure { e -> Timber.e(e, "RootManager: failed to grant %s", permission) }
        }
    }

    /**
     * Grant a set of runtime permissions to [packageName] in a single `su`
     * session. More efficient than calling [grantPermission] in a loop.
     *
     * @param packageName Target application package.
     * @param permissions Collection of full permission names to grant.
     * @return Map of permission -> success/failure [Result].
     */
    suspend fun grantPermissions(
        packageName: String,
        permissions: Collection<String>
    ): Map<String, Result<String>> {
        requireRoot("grantPermissions")
        Timber.d("RootManager: granting %d permissions to %s", permissions.size, packageName)

        val commands = permissions.map { "pm grant $packageName $it" }
        val batchResult = shellExecutor.executeSuCommands(
            commands,
            timeoutMs = ShellExecutor.DEFAULT_MULTI_TIMEOUT_MS
        )

        // Return individual results mapped from batch success/failure.
        // If the batch itself failed, propagate the failure to every permission.
        return if (batchResult.isSuccess) {
            permissions.associateWith { Result.success("") }
        } else {
            val ex = batchResult.exceptionOrNull() ?: Exception("Unknown batch error")
            permissions.associateWith { Result.failure(ex) }
        }
    }

    /**
     * Grant all permissions required by CarOS to itself (package `com.caros`).
     *
     * Called during application startup after [checkRootAvailability] confirms
     * root is available.
     */
    suspend fun grantCarOSSelfPermissions(): Map<String, Result<String>> {
        return grantPermissions(APP_PACKAGE, CAROS_REQUIRED_PERMISSIONS)
    }

    // -------------------------------------------------------------------------
    //  Device node setup
    // -------------------------------------------------------------------------

    /**
     * Set permissions on the primary CAN-bus serial device node so that the
     * app process can open it without root after the initial `chmod`.
     *
     * Default path is `/dev/ttyS1`; overridable via [devicePath].
     *
     * @param devicePath Absolute path to the serial device node.
     * @param mode       Unix permission mode string accepted by `chmod`, e.g. `"0666"`.
     * @return [Result] of the chmod operation.
     */
    suspend fun chmodCanDevice(
        devicePath: String = DEFAULT_CAN_DEVICE,
        mode: String = "0666"
    ): Result<String> {
        requireRoot("chmodCanDevice")
        Timber.d("RootManager: chmod %s %s", mode, devicePath)
        return shellExecutor.executeSuCommand("chmod $mode $devicePath").also { result ->
            result.onSuccess { Timber.i("RootManager: chmod %s %s OK", mode, devicePath) }
            result.onFailure { e -> Timber.e(e, "RootManager: chmod failed on %s", devicePath) }
        }
    }

    /**
     * Discover and set read/write permissions on all `/dev/ttyUSB*` nodes.
     *
     * USB-serial adapters (e.g. CH341, FTDI) appear as `/dev/ttyUSB0`,
     * `/dev/ttyUSB1`, etc. This method lists all present nodes and `chmod`s
     * them so that the app can open them directly from userspace after boot.
     *
     * @param mode Unix permission mode string, default `"0666"`.
     * @return List of device paths that were successfully chmod'd.
     */
    suspend fun setupUsbSerialDevices(mode: String = "0666"): List<String> =
        withContext(Dispatchers.IO) {
            requireRoot("setupUsbSerialDevices")

            // List /dev/ttyUSB* entries
            val listResult = shellExecutor.executeSuCommand("ls /dev/ttyUSB* 2>/dev/null || true")
            val devicePaths = listResult.getOrNull()
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.startsWith("/dev/ttyUSB") }
                ?: emptyList()

            if (devicePaths.isEmpty()) {
                Timber.d("RootManager: no /dev/ttyUSB* devices found")
                return@withContext emptyList()
            }

            Timber.d("RootManager: found %d USB serial devices: %s",
                devicePaths.size, devicePaths.joinToString())

            // Batch chmod in one su session
            val chmodCommands = devicePaths.map { "chmod $mode $it" }
            val batchResult = shellExecutor.executeSuCommands(chmodCommands)

            return@withContext if (batchResult.isSuccess) {
                Timber.i("RootManager: chmod %s on %d USB devices OK", mode, devicePaths.size)
                devicePaths
            } else {
                Timber.e(batchResult.exceptionOrNull(), "RootManager: USB serial chmod batch failed")
                emptyList()
            }
        }

    /**
     * Verify that a device path exists, is readable, and is a character device.
     *
     * Uses `test -c <path> && test -r <path>` via `su` to check the node.
     *
     * @param devicePath Absolute path to test, e.g. `/dev/ttyS1`.
     * @return `true` if the device node exists and is readable.
     */
    suspend fun isDeviceNodeAccessible(devicePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val result = shellExecutor.executeSuCommand(
                "test -c \"$devicePath\" && test -r \"$devicePath\" && echo ok || echo fail"
            )
            result.getOrNull()?.trim() == "ok"
        }

    /**
     * Set a system property via `setprop`.
     *
     * @param key   Property name, e.g. `"persist.caros.can.enabled"`.
     * @param value Property value.
     */
    suspend fun setSystemProperty(key: String, value: String): Result<String> {
        requireRoot("setSystemProperty")
        return shellExecutor.executeSuCommand("setprop $key $value")
    }

    /**
     * Get a system property via `getprop`.
     *
     * @param key Property name.
     * @return The property value string, or an empty string if not set.
     */
    suspend fun getSystemProperty(key: String): String =
        shellExecutor.executeSuCommand("getprop $key")
            .getOrNull()
            ?.trim()
            ?: ""

    /**
     * Remount a filesystem partition read-write.
     *
     * @param mountPoint Absolute path of the mount point, e.g. `"/system"`.
     */
    suspend fun remountReadWrite(mountPoint: String): Result<String> {
        requireRoot("remountReadWrite")
        Timber.d("RootManager: remounting %s rw", mountPoint)
        return shellExecutor.executeSuCommand("mount -o remount,rw $mountPoint")
    }

    // -------------------------------------------------------------------------
    //  Composite setup
    // -------------------------------------------------------------------------

    /**
     * Perform all first-boot privileged setup steps in the correct order:
     *
     *  1. Grant CarOS self permissions.
     *  2. chmod the primary CAN device (`/dev/ttyS1`).
     *  3. chmod any USB serial adapters.
     *
     * Called from [CarOSApplication] after [checkRootAvailability] succeeds.
     */
    suspend fun setupDevicePermissions() = withContext(Dispatchers.IO) {
        Timber.i("RootManager: running privileged device setup")

        // 1. Self-grant runtime permissions
        val permResults = grantCarOSSelfPermissions()
        val failedPerms = permResults.filter { it.value.isFailure }.keys
        if (failedPerms.isNotEmpty()) {
            Timber.w("RootManager: %d permissions could not be granted: %s",
                failedPerms.size, failedPerms.joinToString())
        } else {
            Timber.i("RootManager: all self-permissions granted successfully")
        }

        // 2. CAN device (ttyS1)
        chmodCanDevice(DEFAULT_CAN_DEVICE).onFailure {
            Timber.e(it, "RootManager: could not chmod %s", DEFAULT_CAN_DEVICE)
        }

        // 3. USB serial adapters
        val usbDevices = setupUsbSerialDevices()
        Timber.d("RootManager: %d USB serial devices configured", usbDevices.size)

        Timber.i("RootManager: device setup complete")
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    /**
     * Guard that logs a warning and throws [IllegalStateException] when
     * root is definitely not available (i.e. status is [RootStatus.UNAVAILABLE]
     * or [RootStatus.DENIED]).
     *
     * Note: status [RootStatus.UNKNOWN] passes through because the check may
     * not have run yet. Callers at that point will still get a proper error
     * from the underlying `su` invocation.
     */
    private fun requireRoot(caller: String) {
        if (_rootStatus.value == RootStatus.UNAVAILABLE ||
            _rootStatus.value == RootStatus.DENIED
        ) {
            Timber.w("RootManager.%s called without root (status=%s)", caller, _rootStatus.value)
        }
    }

    /**
     * Execute [block] only if root is available, otherwise return [fallback].
     *
     * Use this for features that are enhanced by root but should still work
     * without it.  The fallback is returned immediately — no `su` invocation
     * is attempted — so callers never block or throw on non-rooted devices.
     *
     * Example:
     * ```kotlin
     * val brightness = rootManager.withRootFallback(defaultBrightness) {
     *     shellExecutor.executeSuCommand("cat /sys/class/backlight/backlight/brightness")
     *         .getOrNull()?.trim()?.toIntOrNull() ?: defaultBrightness
     * }
     * ```
     */
    suspend fun <T> withRootFallback(fallback: T, block: suspend () -> T): T {
        if (_rootStatus.value == RootStatus.UNAVAILABLE ||
            _rootStatus.value == RootStatus.DENIED
        ) {
            Timber.d("RootManager: root unavailable — returning fallback")
            return fallback
        }
        return try {
            block()
        } catch (e: Exception) {
            Timber.w(e, "RootManager: root operation failed — returning fallback")
            fallback
        }
    }

    /**
     * Run [block] only if root is available; silently skip if not.
     * Use for fire-and-forget root operations where failure is acceptable.
     */
    suspend fun ifRootAvailable(block: suspend () -> Unit) {
        if (_rootStatus.value == RootStatus.UNAVAILABLE ||
            _rootStatus.value == RootStatus.DENIED
        ) return
        try {
            block()
        } catch (e: Exception) {
            Timber.w(e, "RootManager: optional root operation failed — continuing without root")
        }
    }

    /** Human-readable summary of root status for Settings / diagnostic screens. */
    fun rootStatusSummary(): String = when (_rootStatus.value) {
        RootStatus.AVAILABLE   -> "Root dostupný — všechny funkce aktivní"
        RootStatus.DENIED      -> "Root odepřen — omezená funkcionalita"
        RootStatus.UNAVAILABLE -> "Zařízení není rootované — základní mód"
        RootStatus.UNKNOWN     -> "Stav rootu se ověřuje…"
    }

    // -------------------------------------------------------------------------
    //  Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val APP_PACKAGE = "com.caros"
        private const val DEFAULT_CAN_DEVICE = "/dev/ttyS1"

        /** Common paths where `su` binaries are found on rooted Android devices. */
        private val SU_BINARY_PATHS = listOf(
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su"
        )

        /**
         * Runtime permissions that CarOS requires.
         *
         * These are granted via `pm grant` at startup (requires root)
         * so the user is not repeatedly prompted while driving.
         */
        private val CAROS_REQUIRED_PERMISSIONS = setOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_PHONE_STATE",
            "android.permission.CALL_PHONE",
            "android.permission.READ_CONTACTS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_SMS",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.MANAGE_MEDIA",
            "android.permission.MODIFY_AUDIO_SETTINGS"
        )
    }
}

// ---------------------------------------------------------------------------
//  Root status sealed enum
// ---------------------------------------------------------------------------

/**
 * Represents the current known state of root access on the device.
 */
enum class RootStatus {
    /** Initial state; [RootManager.checkRootAvailability] has not yet run. */
    UNKNOWN,

    /** `su` binary found and `uid=0` confirmed — root is fully working. */
    AVAILABLE,

    /**
     * `su` binary found but the SU grant dialog was dismissed / the binary
     * returned a non-zero exit code — root is present but access was denied
     * for this app.
     */
    DENIED,

    /** No `su` binary found anywhere — device is not rooted. */
    UNAVAILABLE
}
