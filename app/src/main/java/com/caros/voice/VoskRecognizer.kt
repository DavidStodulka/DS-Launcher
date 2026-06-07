package com.caros.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VoskRecognizer — offline speech-to-text using Vosk.
 *
 * Model is expected at /sdcard/CarOS/vosk-model-small-cs/ or
 * context.filesDir/vosk-model-small-cs/.
 * Download from: https://alphacephei.com/vosk/models (vosk-model-small-cs-0.4-rhasspy)
 *
 * Falls back gracefully to null if model is not present.
 */
@Singleton
class VoskRecognizer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var model: Any? = null        // org.vosk.Model (loaded via reflection to avoid crash if absent)
    private var recognizer: Any? = null   // org.vosk.Recognizer

    val isModelAvailable: Boolean get() = findModelPath() != null

    /**
     * Initialize Vosk model. Call once from a background coroutine.
     * Returns true if model was loaded successfully.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        val modelPath = findModelPath() ?: run {
            Timber.w("VoskRecognizer: model not found — offline Vosk STT unavailable")
            return@withContext false
        }
        return@withContext try {
            val modelClass = Class.forName("org.vosk.Model")
            model = modelClass.getConstructor(String::class.java).newInstance(modelPath)
            val recClass = Class.forName("org.vosk.Recognizer")
            recognizer = recClass.getConstructor(modelClass, Float::class.java)
                .newInstance(model, 16000.0f)
            Timber.i("VoskRecognizer: model loaded from $modelPath")
            true
        } catch (e: Exception) {
            Timber.e(e, "VoskRecognizer: failed to load model")
            false
        }
    }

    /**
     * Recognize speech from raw PCM audio bytes (16kHz, 16-bit, mono).
     * Returns recognized text or null on failure.
     */
    suspend fun recognize(audioBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val rec = recognizer ?: return@withContext null
        return@withContext try {
            val acceptMethod = rec.javaClass.getMethod("acceptWaveForm", ByteArray::class.java, Int::class.java)
            acceptMethod.invoke(rec, audioBytes, audioBytes.size)
            val resultMethod = rec.javaClass.getMethod("getFinalResult")
            val resultJson = resultMethod.invoke(rec) as? String ?: return@withContext null
            // Parse {"text":"..."} from Vosk JSON result
            val match = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"").find(resultJson)
            match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Timber.e(e, "VoskRecognizer: recognize failed")
            null
        }
    }

    fun release() {
        runCatching { recognizer?.javaClass?.getMethod("close")?.invoke(recognizer) }
        runCatching { model?.javaClass?.getMethod("close")?.invoke(model) }
        recognizer = null
        model = null
    }

    private fun findModelPath(): String? {
        val candidates = listOf(
            "/sdcard/CarOS/vosk-model-small-cs",
            File(context.filesDir, "vosk-model-small-cs").absolutePath,
            File(context.getExternalFilesDir(null), "vosk-model-small-cs").absolutePath
        )
        return candidates.firstOrNull { File(it).exists() && File(it).isDirectory }
    }
}
