package com.caros.multimedia

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  Domain types
// ─────────────────────────────────────────────────────────────────────────────

/** Broad classification of an audio file's container / codec. */
enum class MediaType { MP3, FLAC, AAC, WAV, OGG, OTHER }

/**
 * Metadata for a single audio file found during a browse operation.
 *
 * @param path       Absolute filesystem path.
 * @param name       Filename including extension.
 * @param sizeBytes  File size in bytes.
 * @param type       Container / codec type inferred from extension.
 */
data class MediaFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val type: MediaType
)

// ─────────────────────────────────────────────────────────────────────────────
//  USBMediaBrowser
// ─────────────────────────────────────────────────────────────────────────────

/**
 * USBMediaBrowser
 *
 * Scans the local filesystem (USB mass storage and SD-card mount points) for
 * supported audio files.  All I/O is performed on [Dispatchers.IO].
 *
 * Typical usage:
 * ```kotlin
 * val files = browser.browseFiles("/storage/usbdrive0")
 * val dirs  = browser.getDirectories("/storage/usbdrive0")
 * ```
 */
@Singleton
class USBMediaBrowser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // -------------------------------------------------------------------------
    //  Constants
    // -------------------------------------------------------------------------

    companion object {
        /** File extensions considered playable audio. */
        val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "aac", "wav", "ogg", "m4a", "wma", "opus")

        /** Common USB / SD-card mount points on Android head-unit ROMs. */
        val DEFAULT_USB_PATHS = listOf(
            "/storage/",
            "/mnt/usb_storage/",
            "/mnt/usb/",
            "/mnt/sdcard/",
            "/sdcard/"
        )
    }

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /**
     * Return all supported audio files directly inside [path] (non-recursive).
     *
     * @param path Absolute directory path to scan. Defaults to the first entry
     *             in [DEFAULT_USB_PATHS].
     * @return Sorted list of [MediaFile] objects, or an empty list if [path] does
     *         not exist or is not a directory.
     */
    suspend fun browseFiles(path: String = DEFAULT_USB_PATHS.first()): List<MediaFile> =
        withContext(Dispatchers.IO) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                Timber.w("USBMediaBrowser: path does not exist or is not a directory: %s", path)
                return@withContext emptyList()
            }
            val files = dir.listFiles() ?: return@withContext emptyList()
            files.filter { f ->
                f.isFile && f.extension.lowercase() in SUPPORTED_EXTENSIONS
            }.map { f ->
                MediaFile(
                    path = f.absolutePath,
                    name = f.name,
                    sizeBytes = f.length(),
                    type = extensionToType(f.extension)
                )
            }.sortedBy { it.name }
        }

    /**
     * Recursively scan [rootPath] and return every supported audio file found
     * in any subdirectory.
     *
     * @param rootPath Root directory for the recursive scan.
     * @return Flat list of all audio files found beneath [rootPath].
     */
    suspend fun browseFilesRecursive(rootPath: String): List<MediaFile> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<MediaFile>()
            fun scan(dir: File) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    when {
                        child.isDirectory -> scan(child)
                        child.isFile && child.extension.lowercase() in SUPPORTED_EXTENSIONS ->
                            results += MediaFile(
                                path = child.absolutePath,
                                name = child.name,
                                sizeBytes = child.length(),
                                type = extensionToType(child.extension)
                            )
                    }
                }
            }
            val root = File(rootPath)
            if (root.exists() && root.isDirectory) scan(root)
            results.sortedBy { it.name }
        }

    /**
     * Return all subdirectories directly inside [path].
     *
     * @param path Parent directory to list. Defaults to the first USB path.
     * @return List of [File] objects for each sub-directory, sorted by name.
     */
    suspend fun getDirectories(path: String = DEFAULT_USB_PATHS.first()): List<File> =
        withContext(Dispatchers.IO) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
            dir.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                ?: emptyList()
        }

    /**
     * Check which of [DEFAULT_USB_PATHS] are actually mounted and accessible.
     *
     * @return List of accessible path strings.
     */
    suspend fun getAvailableMounts(): List<String> = withContext(Dispatchers.IO) {
        DEFAULT_USB_PATHS.filter { p ->
            val f = File(p)
            f.exists() && f.isDirectory && (f.listFiles()?.isNotEmpty() == true)
        }
    }

    // -------------------------------------------------------------------------
    //  Internal helpers
    // -------------------------------------------------------------------------

    private fun extensionToType(ext: String): MediaType = when (ext.lowercase()) {
        "mp3"        -> MediaType.MP3
        "flac"       -> MediaType.FLAC
        "aac", "m4a" -> MediaType.AAC
        "wav"        -> MediaType.WAV
        "ogg", "opus"-> MediaType.OGG
        else         -> MediaType.OTHER
    }
}
