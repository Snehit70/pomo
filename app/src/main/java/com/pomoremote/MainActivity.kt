package com.pomoremote

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.pomoremote.service.PomodoroService
import com.pomoremote.ui.TimerFragment
import com.pomoremote.util.UtilPreferenceManager

class MainActivity : AppCompatActivity() {
    var service: PomodoroService? = null
        private set
    var isBound = false
        private set

    lateinit var prefs: UtilPreferenceManager
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as PomodoroService.LocalBinder
            service = localBinder.service
            isBound = true
            updateCurrentFragment()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            service = null
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateCurrentFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = UtilPreferenceManager(this)

        // Setup Navigation
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navView: BottomNavigationView = findViewById(R.id.nav_view)
        navView.setupWithNavController(navController)

        startService()
        requestNotificationPermission()
    }

    private fun updateCurrentFragment() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val currentFragment = navHostFragment?.childFragmentManager?.primaryNavigationFragment

        if (currentFragment is TimerFragment) {
            service?.currentState?.let { currentFragment.updateUI(it) }
        }
    }

    fun toggleTimer() {
        if (isBound) service?.toggleTimer()
    }

    fun skipTimer() {
        if (isBound) service?.skipTimer()
    }

    fun resetTimer() {
        if (isBound) service?.resetTimer()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Handle permissions if needed
    }

    private fun startService() {
        val intent = Intent(this, PomodoroService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, IntentFilter("com.pomoremote.STATE_UPDATE"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, IntentFilter("com.pomoremote.STATE_UPDATE"))
        }

        if (isBound) {
            updateCurrentFragment()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1
    }
}
