package com.caros.system

import com.caros.core.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class MagiskModule(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val isEnabled: Boolean
)

@Singleton
class MagiskModuleManager @Inject constructor(private val shellExecutor: ShellExecutor) {
    private val MODULES_DIR = "/data/adb/modules"

    suspend fun getModules(): List<MagiskModule> = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("ls $MODULES_DIR").getOrNull() ?: return@withContext emptyList()
        result.lines().filter { it.isNotBlank() }.mapNotNull { moduleId ->
            readModule(moduleId)
        }
    }

    private suspend fun readModule(moduleId: String): MagiskModule? = withContext(Dispatchers.IO) {
        val baseDir = "$MODULES_DIR/$moduleId"
        val propResult = shellExecutor.execute("cat $baseDir/module.prop 2>/dev/null").getOrNull()
            ?: return@withContext null
        val props = propResult.lines().associate { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else line to ""
        }
        val isDisabled = shellExecutor.execute("ls $baseDir/disable 2>/dev/null").isSuccess
        MagiskModule(
            id = moduleId,
            name = props["name"] ?: moduleId,
            version = props["version"] ?: "unknown",
            author = props["author"] ?: "unknown",
            description = props["description"] ?: "",
            isEnabled = !isDisabled
        )
    }

    suspend fun enableModule(id: String): Boolean = withContext(Dispatchers.IO) {
        shellExecutor.execute("rm -f $MODULES_DIR/$id/disable").isSuccess
    }

    suspend fun disableModule(id: String): Boolean = withContext(Dispatchers.IO) {
        shellExecutor.execute("touch $MODULES_DIR/$id/disable").isSuccess
    }

    suspend fun removeModule(id: String): Boolean = withContext(Dispatchers.IO) {
        shellExecutor.execute("touch $MODULES_DIR/$id/remove").isSuccess
    }

    suspend fun isMagiskInstalled(): Boolean = withContext(Dispatchers.IO) {
        shellExecutor.execute("magisk -V").isSuccess ||
        shellExecutor.execute("ls /sbin/.magisk 2>/dev/null").isSuccess
    }

    suspend fun getMagiskVersion(): String = withContext(Dispatchers.IO) {
        shellExecutor.execute("magisk -V").getOrNull()?.trim() ?: "Not detected"
    }
}
