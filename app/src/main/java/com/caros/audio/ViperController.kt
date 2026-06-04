package com.caros.audio

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ViperController
 *
 * Communicates with an installed ViPER4Android FX app via broadcast intents.
 * All methods are no-ops when ViPER is not installed; callers should check
 * [isViperInstalled] (or call [checkInstalled]) before presenting ViPER options
 * to the user.
 *
 * The broadcast-based API mirrors the documented ViPER4Android intent contract.
 * If the installed build uses a different intent action scheme, override
 * [ACTION_ENABLE] / [ACTION_SET_PARAM] in a subclass or via constructor injection.
 */
@Singleton
class ViperController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // -------------------------------------------------------------------------
    //  Constants
    // -------------------------------------------------------------------------

    private val VIPER_PACKAGE = "com.pittvandewitt.viperfx"
    private val ACTION_ENABLE = "com.pittvandewitt.viperfx.action.ENABLE"
    private val ACTION_SET_PARAM = "com.pittvandewitt.viperfx.action.SET_PARAM"

    // -------------------------------------------------------------------------
    //  State
    // -------------------------------------------------------------------------

    /** True once [checkInstalled] has confirmed the ViPER package is present. */
    var isViperInstalled: Boolean = false
        private set

    // -------------------------------------------------------------------------
    //  Installation check
    // -------------------------------------------------------------------------

    /**
     * Query [PackageManager] for the ViPER package.
     *
     * @return `true` if ViPER is installed; `false` otherwise.
     *         [isViperInstalled] is updated accordingly.
     */
    fun checkInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(VIPER_PACKAGE, 0)
            Timber.d("ViPER4Android detected")
            isViperInstalled = true
            true
        } catch (e: Exception) {
            Timber.d("ViPER4Android not installed")
            isViperInstalled = false
            false
        }
    }

    // -------------------------------------------------------------------------
    //  Control API
    // -------------------------------------------------------------------------

    /**
     * Enable or disable the ViPER audio processing engine.
     *
     * @param enabled `true` to enable, `false` to disable.
     */
    fun setEnabled(enabled: Boolean) {
        if (!isViperInstalled) return
        val intent = Intent(ACTION_ENABLE).apply {
            `package` = VIPER_PACKAGE
            putExtra("enabled", enabled)
        }
        sendSafely(intent, "setEnabled($enabled)")
    }

    /**
     * Set a single ViPER parameter by key.
     *
     * Supported value types: [Boolean], [Int], [Float], [String].
     * Other types are silently ignored.
     *
     * @param key   Parameter key as expected by ViPER's intent receiver.
     * @param value Parameter value. The correct extra type is inferred automatically.
     */
    fun setParameter(key: String, value: Any) {
        if (!isViperInstalled) return
        val intent = Intent(ACTION_SET_PARAM).apply {
            `package` = VIPER_PACKAGE
            putExtra("key", key)
            when (value) {
                is Boolean -> putExtra("value", value)
                is Int     -> putExtra("value", value)
                is Float   -> putExtra("value", value)
                is String  -> putExtra("value", value)
                else       -> {
                    Timber.w("ViPER setParameter: unsupported value type %s", value::class.simpleName)
                    return
                }
            }
        }
        sendSafely(intent, "setParameter($key)")
    }

    /**
     * Apply a full [AudioProfile]'s ViPER settings in one call.
     * Requires [profile.viperEnabled] to be true; otherwise this is a no-op.
     *
     * The [AudioProfile.viperSettings] JSON blob is passed to ViPER as-is via
     * the "viper_settings_json" key.  The ViPER app is responsible for parsing it.
     *
     * @param profile The profile whose ViPER settings should be applied.
     */
    fun applyProfile(profile: AudioProfile) {
        if (!isViperInstalled) return
        setEnabled(profile.viperEnabled)
        if (profile.viperEnabled && profile.viperSettings.isNotBlank()) {
            setParameter("viper_settings_json", profile.viperSettings)
        }
    }

    // -------------------------------------------------------------------------
    //  User-facing helpers
    // -------------------------------------------------------------------------

    /**
     * Return a localised instruction string that guides the user through
     * installing ViPER4Android FX manually.
     */
    fun getInstallInstructions(): String =
        "Nainstalujte ViPER4Android FX:\n" +
        "1. Stáhněte APK z: https://forum.xda-developers.com/t/app-2-5-0-7-viper4android-fx.2191223/\n" +
        "2. Povolte instalaci z neznámých zdrojů (Nastavení → Zabezpečení)\n" +
        "3. Nainstalujte APK\n" +
        "4. Restartujte aplikaci CarOS"

    // -------------------------------------------------------------------------
    //  Internal helpers
    // -------------------------------------------------------------------------

    private fun sendSafely(intent: Intent, description: String) {
        try {
            context.sendBroadcast(intent)
            Timber.d("ViPER broadcast sent: %s", description)
        } catch (e: Exception) {
            Timber.w(e, "ViPER broadcast failed: %s", description)
        }
    }
}
