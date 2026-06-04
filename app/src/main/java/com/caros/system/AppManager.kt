package com.caros.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.caros.core.ShellExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Summary metadata for an installed application.
 *
 * @param packageName Android package name.
 * @param label       Human-readable application label.
 * @param versionName Version string from the package manifest.
 * @param isSystem    True if the app carries [ApplicationInfo.FLAG_SYSTEM].
 * @param sizeBytes   Approximate APK size in bytes (base APK only).
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val isSystem: Boolean,
    val sizeBytes: Long
)

/**
 * AppManager
 *
 * Provides read access to the installed app list and privileged management
 * operations (force-stop, cache clear, disable/enable, uninstall).
 *
 * All operations that invoke shell commands are suspend functions that run on
 * [Dispatchers.IO] via [ShellExecutor].
 *
 * Destructive operations (`pm uninstall`, `pm disable`) require the app to
 * hold Device Owner privileges or root access via [ShellExecutor.executeSuCommand].
 */
@Singleton
class AppManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellExecutor: ShellExecutor
) {
    // -------------------------------------------------------------------------
    //  App list
    // -------------------------------------------------------------------------

    /**
     * Return metadata for every installed application, sorted alphabetically
     * by label.  Runs on [Dispatchers.IO].
     */
    suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA).map { info ->
            AppInfo(
                packageName = info.packageName,
                label = pm.getApplicationLabel(info).toString(),
                versionName = try {
                    pm.getPackageInfo(info.packageName, 0).versionName ?: ""
                } catch (e: Exception) { "" },
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                sizeBytes = try {
                    File(pm.getApplicationInfo(info.packageName, 0).sourceDir).length()
                } catch (e: Exception) { 0L }
            )
        }.sortedBy { it.label }
    }

    /**
     * Return only user-installed (non-system) apps, sorted alphabetically.
     */
    suspend fun getUserApps(): List<AppInfo> =
        getInstalledApps().filter { !it.isSystem }

    /**
     * Return only system apps, sorted alphabetically.
     */
    suspend fun getSystemApps(): List<AppInfo> =
        getInstalledApps().filter { it.isSystem }

    // -------------------------------------------------------------------------
    //  Process control
    // -------------------------------------------------------------------------

    /**
     * Force-stop [packageName] via `am force-stop`.
     * Does not require root; works for user apps.
     */
    suspend fun forceStop(packageName: String): Result<String> {
        Timber.d("AppManager: force-stop %s", packageName)
        return shellExecutor.execute("am force-stop $packageName")
    }

    // -------------------------------------------------------------------------
    //  Storage management
    // -------------------------------------------------------------------------

    /**
     * Clear data and cache for [packageName] via `pm clear`.
     * Effective for user apps; system apps may require root.
     */
    suspend fun clearCache(packageName: String): Result<String> {
        Timber.d("AppManager: clear cache %s", packageName)
        return shellExecutor.execute("pm clear $packageName")
    }

    /**
     * Attempt to delete the data caches of every installed app using a
     * best-effort shell find invocation (requires root on most ROMs).
     */
    suspend fun clearAllCaches(): Result<String> {
        Timber.d("AppManager: clearing all app caches")
        return shellExecutor.executeSuCommand(
            "find /data/data -maxdepth 2 -name 'cache' -type d -exec rm -rf {} + 2>/dev/null; sync"
        )
    }

    // -------------------------------------------------------------------------
    //  Install / uninstall
    // -------------------------------------------------------------------------

    /**
     * Uninstall [packageName] for the current user via `pm uninstall`.
     * For system apps a root-level `pm uninstall -k --user 0` is attempted.
     *
     * @param packageName Package to uninstall.
     * @param forAllUsers If `true`, removes for all users (requires root).
     */
    suspend fun uninstallApp(
        packageName: String,
        forAllUsers: Boolean = false
    ): Result<String> {
        Timber.d("AppManager: uninstall %s (forAllUsers=%b)", packageName, forAllUsers)
        return if (forAllUsers) {
            shellExecutor.executeSuCommand("pm uninstall $packageName")
        } else {
            shellExecutor.execute("pm uninstall -k --user 0 $packageName")
        }
    }

    // -------------------------------------------------------------------------
    //  Enable / disable
    // -------------------------------------------------------------------------

    /**
     * Disable [packageName] so it no longer appears in the launcher or
     * runs in the background. Requires Device Owner or root.
     */
    suspend fun disableApp(packageName: String): Result<String> {
        Timber.d("AppManager: disable %s", packageName)
        return shellExecutor.executeSuCommand("pm disable $packageName")
    }

    /**
     * Re-enable a previously disabled [packageName]. Requires Device Owner or root.
     */
    suspend fun enableApp(packageName: String): Result<String> {
        Timber.d("AppManager: enable %s", packageName)
        return shellExecutor.executeSuCommand("pm enable $packageName")
    }

    /**
     * Suspend [packageName] (API 28+). The app remains installed but cannot
     * be launched by the user until [unsuspendApp] is called.
     * Requires Device Owner privilege.
     */
    suspend fun suspendApp(packageName: String): Result<String> {
        Timber.d("AppManager: suspend %s", packageName)
        return shellExecutor.executeSuCommand("pm suspend $packageName")
    }

    /**
     * Lift the suspension applied by [suspendApp].
     */
    suspend fun unsuspendApp(packageName: String): Result<String> {
        Timber.d("AppManager: unsuspend %s", packageName)
        return shellExecutor.executeSuCommand("pm unsuspend $packageName")
    }

    // -------------------------------------------------------------------------
    //  Queries
    // -------------------------------------------------------------------------

    /**
     * Check whether [packageName] is currently installed (any install state).
     */
    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Check whether [packageName] is currently enabled.
     *
     * @return `true` if the app is installed and not in a disabled state.
     */
    fun isEnabled(packageName: String): Boolean = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        info.enabled
    } catch (e: Exception) {
        false
    }
}
