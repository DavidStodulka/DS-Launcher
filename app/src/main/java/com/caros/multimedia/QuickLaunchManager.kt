package com.caros.multimedia

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QuickLaunchManager
 *
 * Provides one-call launch helpers for the apps most commonly used from the
 * CarOS launcher (Waze, Spotify, YouTube) plus a generic [launchApp] for
 * any installed package.  All launches add [Intent.FLAG_ACTIVITY_NEW_TASK]
 * so they can be fired from a non-Activity context.
 */
@Singleton
class QuickLaunchManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // -------------------------------------------------------------------------
    //  Known package names
    // -------------------------------------------------------------------------

    companion object {
        const val PKG_WAZE    = "com.waze"
        const val PKG_SPOTIFY = "com.spotify.music"
        const val PKG_YOUTUBE = "com.google.android.youtube"
        const val PKG_MAPS    = "com.google.android.apps.maps"
        const val PKG_PHONE   = "com.android.dialer"
    }

    // -------------------------------------------------------------------------
    //  Named shortcuts
    // -------------------------------------------------------------------------

    /** Launch Waze navigation. Returns false if Waze is not installed. */
    fun launchWaze(): Boolean = launchApp(PKG_WAZE)

    /** Launch Spotify. Returns false if Spotify is not installed. */
    fun launchSpotify(): Boolean = launchApp(PKG_SPOTIFY)

    /** Launch YouTube. Returns false if YouTube is not installed. */
    fun launchYouTube(): Boolean = launchApp(PKG_YOUTUBE)

    /** Launch Google Maps. Returns false if Maps is not installed. */
    fun launchMaps(): Boolean = launchApp(PKG_MAPS)

    /** Launch the system dialer. Returns false if no dialer is installed. */
    fun launchPhone(): Boolean = launchApp(PKG_PHONE)

    // -------------------------------------------------------------------------
    //  Generic launcher
    // -------------------------------------------------------------------------

    /**
     * Launch the main activity of [packageName].
     *
     * @param packageName Android package name of the target application.
     * @return `true` if the activity was started successfully; `false` if the
     *         package is not installed or the intent could not be resolved.
     */
    fun launchApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Timber.w("QuickLaunch: no launch intent for %s", packageName)
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Timber.d("QuickLaunch: started %s", packageName)
            true
        } catch (e: Exception) {
            Timber.e(e, "QuickLaunch: failed to start %s", packageName)
            false
        }
    }

    /**
     * Launch [packageName] with an explicit URI payload (e.g. a deep-link).
     *
     * @param packageName Android package name of the target application.
     * @param uri         URI string to pass as the intent's data.
     * @return `true` if the activity was started successfully.
     */
    fun launchAppWithUri(packageName: String, uri: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Timber.e(e, "QuickLaunch: failed to start %s with uri %s", packageName, uri)
            false
        }
    }

    // -------------------------------------------------------------------------
    //  Installation query
    // -------------------------------------------------------------------------

    /**
     * Check whether [packageName] is installed on the device.
     *
     * @return `true` if the package is present and at least partially installed.
     */
    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Return a map of package name → isInstalled for all known shortcut packages.
     */
    fun getInstallStatus(): Map<String, Boolean> = listOf(
        PKG_WAZE, PKG_SPOTIFY, PKG_YOUTUBE, PKG_MAPS, PKG_PHONE
    ).associateWith { isInstalled(it) }
}
