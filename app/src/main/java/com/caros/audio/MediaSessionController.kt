package com.caros.audio

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of currently playing media from the active [MediaController].
 *
 * @param title        Track title (empty if unknown).
 * @param artist       Artist name (empty if unknown).
 * @param album        Album name (empty if unknown).
 * @param durationMs   Total track duration in milliseconds; 0 if not reported.
 * @param positionMs   Current playback position in milliseconds.
 * @param isPlaying    Whether the transport is in the PLAYING state.
 * @param albumArtUri  Content URI string for album art, or null if not available.
 */
data class MediaInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val albumArtUri: String? = null
)

/**
 * MediaSessionController
 *
 * Monitors [MediaSessionManager] for active media sessions and exposes the
 * currently playing track via [mediaInfo] as a [StateFlow].
 *
 * Transport controls (play / pause / next / previous / seek) are forwarded
 * to the first active [MediaController] via [android.media.session.MediaController.TransportControls].
 *
 * **Requirements:**
 *  - The app must hold a running [android.service.notification.NotificationListenerService]
 *    so that [MediaSessionManager.getActiveSessions] is granted.
 *  - [initialize] must be called once (e.g. from a service or Application.onCreate).
 */
@Singleton
class MediaSessionController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // -------------------------------------------------------------------------
    //  State
    // -------------------------------------------------------------------------

    private val _mediaInfo = MutableStateFlow(MediaInfo())

    /** Live [MediaInfo] updated whenever the active session's metadata or playback state changes. */
    val mediaInfo: StateFlow<MediaInfo> = _mediaInfo.asStateFlow()

    private var activeController: MediaController? = null

    // -------------------------------------------------------------------------
    //  Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Attach to the first active [MediaController] found by [MediaSessionManager].
     *
     * Safe to call multiple times — re-registering the callback on a new controller
     * automatically unregisters the previous one.
     *
     * Silently swallows [SecurityException] if the notification listener
     * component has not yet been granted access by the user.
     */
    fun initialize() {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listenerComponent = ComponentName(
                context,
                "com.caros.communication.CarOSNotificationListenerService"
            )
            val controllers = msm.getActiveSessions(listenerComponent)
            Timber.d("MediaSessionController: %d active sessions found", controllers.size)
            attachController(controllers.firstOrNull())
        } catch (e: SecurityException) {
            Timber.w(e, "MediaSession: NotificationListener access not granted yet")
        } catch (e: Exception) {
            Timber.w(e, "MediaSession initialisation failed")
        }
    }

    /** Release the callback from the current controller. */
    fun release() {
        activeController?.unregisterCallback(mediaCallback)
        activeController = null
    }

    // -------------------------------------------------------------------------
    //  Transport controls
    // -------------------------------------------------------------------------

    /** Resume / start playback on the active session. */
    fun play() = activeController?.transportControls?.play()

    /** Pause playback on the active session. */
    fun pause() = activeController?.transportControls?.pause()

    /** Skip to the next track on the active session. */
    fun next() = activeController?.transportControls?.skipToNext()

    /** Skip to the previous track on the active session. */
    fun previous() = activeController?.transportControls?.skipToPrevious()

    /**
     * Seek the active session to [positionMs].
     *
     * @param positionMs Target playback position in milliseconds.
     */
    fun seekTo(positionMs: Long) = activeController?.transportControls?.seekTo(positionMs)

    // -------------------------------------------------------------------------
    //  Internal helpers
    // -------------------------------------------------------------------------

    private fun attachController(controller: MediaController?) {
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller
        controller?.registerCallback(mediaCallback)
        // Seed state from whatever is already active
        controller?.let {
            updateFromMetadata(it.metadata, it.playbackState)
        }
    }

    private fun updateFromMetadata(metadata: MediaMetadata?, state: PlaybackState?) {
        _mediaInfo.value = MediaInfo(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = state?.position ?: 0L,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            albumArtUri = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        )
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateFromMetadata(metadata, activeController?.playbackState)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            _mediaInfo.value = _mediaInfo.value.copy(
                isPlaying = state?.state == PlaybackState.STATE_PLAYING,
                positionMs = state?.position ?: 0L
            )
        }
    }
}
