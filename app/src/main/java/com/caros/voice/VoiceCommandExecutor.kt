package com.caros.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.caros.audio.AdaptiveEQEngine
import com.caros.audio.AudioEngineManager
import com.caros.audio.AudioProfile
import com.caros.can.CANFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceCommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioEngine: AudioEngineManager,
    private val adaptiveEQ: AdaptiveEQEngine
) {
    // Injected lazily to avoid circular deps
    var canFrameFlow: StateFlow<CANFrame>? = null

    fun execute(command: VoiceCommand): String {
        Timber.d("Execute voice command: $command")
        return when (command) {
            is VoiceCommand.Media -> executeMedia(command.cmd)
            is VoiceCommand.AudioProfile -> executeAudioProfile(command.profile)
            is VoiceCommand.EQAdjust -> executeEQAdjust(command.param, command.value)
            is VoiceCommand.AutoEQ -> { adaptiveEQ.setEnabled(command.enabled); if (command.enabled) "Auto EQ zapnuto" else "Auto EQ vypnuto" }
            is VoiceCommand.AppLaunch -> launchApp(command.app)
            is VoiceCommand.CarInfo -> getCarInfo(command.query)
            is VoiceCommand.Navigate -> { launchNavigation(command.destination); "Naviguju do ${command.destination}" }
            is VoiceCommand.System -> executeSystem(command.cmd)
            is VoiceCommand.Cancel -> "Zrušeno"
            is VoiceCommand.Phone -> "Funkce telefonu zatím není dostupná"
            is VoiceCommand.Unknown -> "Nerozuměl jsem příkazu: ${command.raw}"
        }
    }

    private fun executeMedia(cmd: String): String {
        val intent = Intent("android.intent.action.MEDIA_BUTTON").apply {
            when (cmd) {
                "play", "pause" -> putExtra("state", "play_pause")
                "next" -> putExtra("state", "next")
                "prev" -> putExtra("state", "previous")
                "volume_up" -> putExtra("state", "volume_up")
                "volume_down" -> putExtra("state", "volume_down")
            }
        }
        runCatching { context.sendBroadcast(intent) }
        return when (cmd) { "play" -> "Přehrávám"; "pause" -> "Pauza"; "next" -> "Další"; "prev" -> "Předchozí"; else -> "OK" }
    }

    private fun executeAudioProfile(profile: String): String {
        val p = when (profile) {
            "flat" -> AudioProfile.FLAT
            "bass" -> AudioProfile.BASS_PLUS
            "vocal" -> AudioProfile.VOCAL
            "stage" -> AudioProfile.STAGE
            "night" -> AudioProfile.NIGHT
            "sport" -> AudioProfile.SPORT
            else -> return "Profil '$profile' neznám"
        }
        audioEngine.setProfile(p)
        return "Profil ${p.name} aktivní"
    }

    private fun executeEQAdjust(param: String, value: Int): String {
        when (param) {
            "bass" -> audioEngine.setBass(value)
            "treble" -> { /* map to high bands */ }
            "surround" -> audioEngine.setSurroundStrength(value)
        }
        return "EQ: $param nastaven na $value"
    }

    private fun launchApp(app: String): String {
        val pkg = when (app) {
            "spotify" -> "com.spotify.music"
            "youtube" -> "com.google.android.youtube"
            "waze" -> "com.waze"
            else -> return "Neznám aplikaci $app"
        }
        return runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return "Aplikace není nainstalována"
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Spouštím $app"
        }.getOrElse { "Chyba spuštění $app" }
    }

    private fun launchNavigation(destination: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun getCarInfo(query: String): String {
        val frame = canFrameFlow?.value ?: return "Data nejsou dostupná"
        return when (query) {
            "speed" -> "Rychlost: ${frame.vehicleSpeed?.kmh?.toInt() ?: "--"} km/h"
            "rpm" -> "Otáčky: ${frame.engineRpm?.rpm ?: "--"} ot/min"
            "temp" -> "Teplota chladiva: ${frame.coolantTemp?.celsius?.toInt() ?: "--"}°C"
            "voltage" -> "Napětí: ${"%.1f".format(frame.batteryVoltage?.volts ?: 0f)} V"
            "dpf" -> "DPF: ${frame.dpfData?.loadPercent?.toInt() ?: "--"}%"
            else -> "Informace '$query' nejsou dostupná"
        }
    }

    private fun executeSystem(cmd: String): String {
        return when (cmd) {
            "brightness_up" -> { adjustBrightness(30); "Jas zvýšen" }
            "brightness_down" -> { adjustBrightness(-30); "Jas snížen" }
            "wifi_on" -> { setWifi(true); "WiFi zapnuto" }
            "wifi_off" -> { setWifi(false); "WiFi vypnuto" }
            else -> "Neznámý příkaz systému"
        }
    }

    private fun adjustBrightness(delta: Int) {
        runCatching {
            val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (current + delta).coerceIn(10, 255))
        }
    }

    private fun setWifi(enabled: Boolean) {
        // Direct wifi toggle requires CHANGE_WIFI_STATE permission
        runCatching {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION") wm.isWifiEnabled = enabled
        }
    }
}
