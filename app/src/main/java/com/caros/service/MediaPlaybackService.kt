package com.caros.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import timber.log.Timber

class MediaPlaybackService : Service() {
    override fun onCreate() { super.onCreate(); Timber.d("MediaPlaybackService created") }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); Timber.d("MediaPlaybackService destroyed") }
}
