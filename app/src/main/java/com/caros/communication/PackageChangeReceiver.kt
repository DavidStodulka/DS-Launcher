package com.caros.communication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> Timber.d("Package installed: $packageName")
            Intent.ACTION_PACKAGE_REMOVED -> Timber.d("Package removed: $packageName")
            Intent.ACTION_PACKAGE_REPLACED -> Timber.d("Package replaced: $packageName")
        }
    }
}
