package com.caros.ui.call

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.caros.communication.CallManager
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class CallOverlayActivity : AppCompatActivity() {
    @Inject lateinit var callManager: CallManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("CallOverlayActivity created")
        // TODO: inflate call overlay layout, bind callManager.callInfo
        // Shows incoming call UI with answer/decline buttons
        finish() // placeholder — will be implemented with proper call layout
    }
}
