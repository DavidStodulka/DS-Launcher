package com.caros.voice

import kotlinx.serialization.Serializable

@Serializable
sealed class VoiceCommand {
    @Serializable data class Navigate(val destination: String) : VoiceCommand()
    @Serializable data class Media(val cmd: String) : VoiceCommand()  // play|pause|next|prev|volume_up|volume_down
    @Serializable data class AudioProfile(val profile: String) : VoiceCommand()  // flat|bass|vocal|stage|night|sport
    @Serializable data class EQAdjust(val param: String, val value: Int) : VoiceCommand()  // bass|treble|surround 0-100
    @Serializable data class AutoEQ(val enabled: Boolean) : VoiceCommand()
    @Serializable data class AppLaunch(val app: String) : VoiceCommand()  // spotify|youtube|waze
    @Serializable data class CarInfo(val query: String) : VoiceCommand()   // speed|rpm|temp|dpf|oil_life|voltage
    @Serializable data class Phone(val cmd: String, val contact: String = "") : VoiceCommand()
    @Serializable data class System(val cmd: String) : VoiceCommand()  // brightness_up|brightness_down|wifi_on|wifi_off
    @Serializable object Cancel : VoiceCommand()
    @Serializable data class Unknown(val raw: String) : VoiceCommand()
}
