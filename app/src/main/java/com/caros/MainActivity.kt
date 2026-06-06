package com.caros

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.caros.can.CANService
import com.caros.databinding.ActivityMainBinding
import com.caros.profiles.DrivingMode
import com.caros.profiles.ProfileManager
import com.caros.ui.main.LeftPanelFragment
import com.caros.ui.main.MainViewModel
import com.caros.ui.main.RightPanelFragment
import com.caros.voice.SteeringWheelButtonDetector
import com.caros.voice.VoiceCommandExecutor
import com.caros.voice.VoiceInputManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    @Inject lateinit var profileManager: ProfileManager
    @Inject lateinit var voiceCommandExecutor: VoiceCommandExecutor
    @Inject lateinit var steeringWheelButtonDetector: SteeringWheelButtonDetector
    @Inject lateinit var voiceInputManager: VoiceInputManager

    private val mainViewModel: MainViewModel by viewModels()

    private var canServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            canServiceBound = true
            Timber.d("MainActivity: CANService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            canServiceBound = false
            Timber.d("MainActivity: CANService disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()

        setupPanels(savedInstanceState)
        setupNavigation()
        setupBottomNav()
        bindCAN()
        startNightDimCoroutine()
        observeDrivingMode()
    }

    private fun hideSystemUI() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
    }

    private fun setupPanels(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.leftPanel, LeftPanelFragment())
                .replace(R.id.rightPanel, RightPanelFragment())
                .commitNow()
        }
    }

    private fun setupNavigation() {
        // Dynamically add NavHostFragment into the centerPanel FrameLayout
        val existing = supportFragmentManager.findFragmentById(R.id.centerPanel)
        if (existing is NavHostFragment) {
            navController = existing.navController
        } else {
            val navHost = NavHostFragment.create(R.navigation.nav_graph)
            supportFragmentManager.beginTransaction()
                .replace(R.id.centerPanel, navHost, "navHost")
                .setPrimaryNavigationFragment(navHost)
                .commitNow()
            navController = navHost.navController
        }
    }

    private fun setupBottomNav() {
        val navOpts = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setPopUpTo(R.id.mainFragment, inclusive = false)
            .build()

        binding.navHome.setOnClickListener {
            navController.navigate(R.id.mainFragment, null, navOpts)
            updateNavHighlight(R.id.navHome)
        }
        binding.navMedia.setOnClickListener {
            navController.navigate(R.id.mediaFragment, null, navOpts)
            updateNavHighlight(R.id.navMedia)
        }
        binding.navRace.setOnClickListener {
            navController.navigate(R.id.raceFragment, null, navOpts)
            updateNavHighlight(R.id.navRace)
        }
        binding.navVcds.setOnClickListener {
            navController.navigate(R.id.vcdsFragment, null, navOpts)
            updateNavHighlight(R.id.navVcds)
        }
        binding.navAndroid.setOnClickListener {
            navController.navigate(R.id.settingsFragment, null, navOpts)
            updateNavHighlight(R.id.navAndroid)
        }
        binding.navDiag.setOnClickListener {
            navController.navigate(R.id.diagnosticsFragment, null, navOpts)
            updateNavHighlight(R.id.navDiag)
        }
    }

    private fun updateNavHighlight(selectedId: Int) {
        val allNavItems = listOf(
            binding.navHome,
            binding.navMedia,
            binding.navRace,
            binding.navVcds,
            binding.navAndroid,
            binding.navDiag
        )
        allNavItems.forEach { item ->
            item.alpha = if (item.id == selectedId) 1.0f else 0.55f
        }
    }

    private fun bindCAN() {
        val intent = Intent(this, CANService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startNightDimCoroutine() {
        lifecycleScope.launch {
            while (isActive) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (hour >= 20 || hour <= 6) {
                    setBrightness(60)
                }
                delay(60_000L)
            }
        }
    }

    private fun setBrightness(value: Int) {
        try {
            android.provider.Settings.System.putInt(
                contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                value.coerceIn(0, 255)
            )
        } catch (e: Exception) {
            Timber.w(e, "MainActivity: failed to set brightness")
        }
    }

    private fun observeDrivingMode() {
        lifecycleScope.launch {
            profileManager.drivingMode.collect { mode ->
                when (mode) {
                    DrivingMode.DRIVING -> {
                        binding.bottomNavBar.alpha = 0.6f
                    }
                    DrivingMode.PARKED -> {
                        binding.bottomNavBar.alpha = 1.0f
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Route to calibration first if active
        if (steeringWheelButtonDetector.isCalibrating && event != null) {
            if (steeringWheelButtonDetector.onKeyEvent(event)) return true
        }
        val voiceKeyCode = mainViewModel.voiceKeyCode.value
        if (keyCode == KeyEvent.KEYCODE_SEARCH || (voiceKeyCode != null && keyCode == voiceKeyCode)) {
            mainViewModel.toggleVoiceListening()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceInputManager.destroy()
        if (canServiceBound) {
            unbindService(serviceConnection)
            canServiceBound = false
        }
    }
}
