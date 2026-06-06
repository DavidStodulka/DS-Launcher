package com.caros.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTAManager — downloads a CarOS APK from a given URL and launches the
 * system package installer.  Requires the INSTALL_PACKAGES permission or
 * REQUEST_INSTALL_PACKAGES (API 26+) in AndroidManifest.
 *
 * Progress is exposed via [downloadProgress] (0f–1f) so the UI can show
 * a progress bar.  [state] reflects the current OTA lifecycle step.
 */
@Singleton
class OTAManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) {
    sealed class OTAState {
        object Idle : OTAState()
        object Checking : OTAState()
        data class UpdateAvailable(val remoteVersion: String, val downloadUrl: String) : OTAState()
        object Downloading : OTAState()
        data class ReadyToInstall(val apkFile: File) : OTAState()
        data class Error(val message: String) : OTAState()
        object UpToDate : OTAState()
    }

    private val _state = MutableStateFlow<OTAState>(OTAState.Idle)
    val state: Flow<OTAState> = _state.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: Flow<Float> = _downloadProgress.asStateFlow()

    private val apkDir: File = File(context.cacheDir, "ota").also { it.mkdirs() }

    /**
     * Check [versionUrl] (expected: plain text `{"version":"1.2.3","url":"https://...apk"}`).
     * Sets state to [OTAState.UpdateAvailable] if the remote version differs from the
     * installed one, or [OTAState.UpToDate] if already current.
     */
    suspend fun checkForUpdate(versionUrl: String) = withContext(Dispatchers.IO) {
        _state.value = OTAState.Checking
        runCatching {
            val request = Request.Builder().url(versionUrl).get().build()
            val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
            val json = org.json.JSONObject(body)
            val remoteVersion = json.getString("version")
            val downloadUrl = json.getString("url")
            val localVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
            if (remoteVersion != localVersion) {
                Timber.i("OTA: update available $localVersion → $remoteVersion")
                _state.value = OTAState.UpdateAvailable(remoteVersion, downloadUrl)
            } else {
                Timber.i("OTA: up-to-date ($localVersion)")
                _state.value = OTAState.UpToDate
            }
        }.onFailure { e ->
            Timber.e(e, "OTA: version check failed")
            _state.value = OTAState.Error("Version check failed: ${e.message}")
        }
    }

    /**
     * Download the APK from [url] into the app's cache directory.
     * Updates [downloadProgress] as bytes arrive.
     * On success, sets state to [OTAState.ReadyToInstall].
     */
    suspend fun downloadUpdate(url: String) = withContext(Dispatchers.IO) {
        _state.value = OTAState.Downloading
        _downloadProgress.value = 0f
        val outFile = File(apkDir, "caros-update.apk")
        runCatching {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body ?: error("Empty response body")
                val contentLength = body.contentLength()
                var downloaded = 0L
                FileOutputStream(outFile).use { out ->
                    body.byteStream().use { src ->
                        val buf = ByteArray(8192)
                        var read: Int
                        while (src.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            downloaded += read
                            if (contentLength > 0) {
                                _downloadProgress.value = downloaded.toFloat() / contentLength
                            }
                        }
                    }
                }
            }
            Timber.i("OTA: download complete → ${outFile.absolutePath}")
            _downloadProgress.value = 1f
            _state.value = OTAState.ReadyToInstall(outFile)
        }.onFailure { e ->
            Timber.e(e, "OTA: download failed")
            _state.value = OTAState.Error("Download failed: ${e.message}")
        }
    }

    /**
     * Launch the system package installer for the downloaded APK.
     * Must be called from a UI context (Activity or Fragment's context).
     *
     * @param activity  Activity used to start the installer intent
     * @param apkFile   The downloaded APK file (from [OTAState.ReadyToInstall])
     */
    fun installUpdate(activity: Activity, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        activity.startActivity(intent)
    }

    fun reset() {
        _state.value = OTAState.Idle
        _downloadProgress.value = 0f
    }
}
