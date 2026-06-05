package com.caros.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TermuxSetupManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class SetupStep { NOT_STARTED, TERMUX_INSTALL, PERMISSIONS, RUNNING_SCRIPT, DONE, ERROR }

    val isTermuxInstalled: Boolean get() = runCatching {
        context.packageManager.getPackageInfo("com.termux", 0); true
    }.getOrDefault(false)

    fun openTermuxFDroid() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.termux"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun runSetupScript(): Flow<String> = flow {
        emit("STATUS:Kopíruji setup skript...")
        val scriptAsset = "scripts/termux_setup.sh"
        val scriptFile = File(context.cacheDir, "termux_setup.sh")
        context.assets.open(scriptAsset).use { input ->
            scriptFile.outputStream().use { input.copyTo(it) }
        }
        scriptFile.setExecutable(true)

        emit("STATUS:Spouštím instalaci...")
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setPackage("com.termux")
            putExtra("com.termux.RUN_COMMAND_PATH", scriptFile.absolutePath)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", emptyArray<String>())
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.sendBroadcast(intent) }.onFailure { e ->
            emit("ERROR:${e.message}")
            return@flow
        }
        emit("STATUS:Skript spuštěn na pozadí...")
        emit("DONE:Setup byl odeslán do Termuxu")
    }.flowOn(Dispatchers.IO)

    fun grantTermuxPermission() {
        // com.termux.permission.RUN_COMMAND requires Termux:Tasker or manual ADB grant
        runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c",
                "pm grant com.termux com.termux.permission.RUN_COMMAND"))
        }
    }
}
