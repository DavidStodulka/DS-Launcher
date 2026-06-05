package com.caros.can

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CANWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val port = "/dev/ttyS1"
    private val TAG = "CANWriter"

    suspend fun sendClimateCommand(cmd: ClimateCommand) = withContext(Dispatchers.IO) {
        val frame = cmd.toCANFrame()
        try {
            val proc = ProcessBuilder("su", "-c", "echo -n '${frame}' > $port")
                .redirectErrorStream(true)
                .start()
            proc.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Climate write failed: ${e.message}")
        }
    }
}

data class ClimateCommand(
    val targetTemp: Float? = null,
    val fanSpeed: Int? = null,
    val acOn: Boolean? = null,
    val recircOn: Boolean? = null,
    val defrostOn: Boolean? = null
) {
    fun toCANFrame(): String {
        // VAG climate CAN frame encoding — address 0x3E0
        val temp = targetTemp?.let { ((it + 40) * 2).toInt() } ?: 0xFF
        val fan = fanSpeed?.coerceIn(0, 7) ?: 0xFF
        val flags = ((if (acOn == true) 1 else 0) or
                    (if (recircOn == true) 2 else 0) or
                    (if (defrostOn == true) 4 else 0))
        return "3E0#%02X%02X%02X000000".format(temp, fan, flags)
    }
}
