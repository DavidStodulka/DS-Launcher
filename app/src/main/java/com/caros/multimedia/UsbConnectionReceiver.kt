package com.caros.multimedia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class UsbConnectionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var androidAutoManager: AndroidAutoManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            androidAutoManager.onUsbDeviceAttached()
        } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
            androidAutoManager.disconnect()
        }
    }
}
