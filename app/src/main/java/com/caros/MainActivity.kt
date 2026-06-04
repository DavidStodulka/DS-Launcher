package com.caros

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.caros.can.CANService
import com.caros.databinding.ActivityMainBinding
import com.caros.profiles.DrivingMode
import com.caros.profiles.ProfileManager
import com.caros.ui.main.LeftPanelFragment
import com.caros.ui.main.MainViewModel
import com.caros.ui.main.RightPanelFragment
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
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.centerPanel) as NavHostFragment
        navController = navHostFragment.navController
    }

    private fun setupBottomNav() {
        binding.btnHome.setOnClickListener {
            navController.navigate(R.id.mainFragment)
            updateNavHighlight(R.id.btnHome)
        }
        binding.btnMedia.setOnClickListener {
            navController.navigate(R.id.mediaFragment)
            updateNavHighlight(R.id.btnMedia)
        }
        binding.btnRace.setOnClickListener {
            navController.navigate(R.id.raceFragment)
            updateNavHighlight(R.id.btnRace)
        }
        binding.btnVCDS.setOnClickListener {
            navController.navigate(R.id.vcdsFragment)
            updateNavHighlight(R.id.btnVCDS)
        }
        binding.btnAndroid.setOnClickListener {
            navController.navigate(R.id.settingsFragment)
            updateNavHighlight(R.id.btnAndroid)
        }
        binding.btnDiag.setOnClickListener {
            navController.navigate(R.id.diagnosticsFragment)
            updateNavHighlight(R.id.btnDiag)
        }
    }

    private fun updateNavHighlight(selectedId: Int) {
        val allButtons = listOf(
            binding.btnHome,
            binding.btnMedia,
            binding.btnRace,
            binding.btnVCDS,
            binding.btnAndroid,
            binding.btnDiag
        )
        allButtons.forEach { btn ->
            btn.alpha = if (btn.id == selectedId) 1.0f else 0.55f
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

    override fun onDestroy() {
        super.onDestroy()
        if (canServiceBound) {
            unbindService(serviceConnection)
            canServiceBound = false
        }
    }
}
