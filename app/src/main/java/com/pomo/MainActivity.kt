package com.pomo

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.pomo.service.PomodoroService
import com.pomo.service.PomodoroServiceStarter
import com.pomo.ui.TimerFragment
import com.pomo.util.UtilPreferenceManager
import kotlinx.coroutines.launch

public class MainActivity : AppCompatActivity() {
    public var service: PomodoroService? = null
        private set
    public var isBound: Boolean = false
        private set

    public lateinit var prefs: UtilPreferenceManager
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.i(TAG, "PomodoroService connected: ${name.flattenToShortString()}")
            val localBinder = binder as PomodoroService.LocalBinder
            service = localBinder.service
            isBound = true
            updateCurrentFragment()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "PomodoroService disconnected: ${name.flattenToShortString()}")
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
        Log.i(
            TAG,
            "MainActivity created. sdk=${Build.VERSION.SDK_INT}, release=${Build.VERSION.RELEASE}, " +
                "manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}, version=${BuildConfig.VERSION_NAME}",
        )
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
        val service = service ?: return
        lifecycleScope.launch {
            updateCurrentFragment(service.stateSnapshot())
        }
    }

    private fun updateCurrentFragment(state: com.pomo.timer.TimerState) {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val currentFragment = navHostFragment?.childFragmentManager?.primaryNavigationFragment

        if (currentFragment is TimerFragment) {
            currentFragment.updateUI(state)
        }
    }

    public fun toggleTimer() {
        dispatchTimerCommand("toggle") { toggleTimerBlocking() }
    }

    public fun skipTimer() {
        dispatchTimerCommand("skip") { skipTimerBlocking() }
    }

    public fun resetTimer() {
        dispatchTimerCommand("reset") { resetTimerBlocking() }
    }

    public fun addTime(secondsDelta: Int) {
        dispatchTimerCommand("add_time") { addTimeBlocking(secondsDelta) }
    }

    private fun dispatchTimerCommand(name: String, command: suspend PomodoroService.() -> com.pomo.timer.TimerState) {
        val boundService = service
        Log.i(TAG, "Timer command requested: $name. isBound=$isBound serviceReady=${boundService != null}")
        if (!isBound || boundService == null) {
            Log.w(TAG, "Ignoring timer command '$name' because PomodoroService is not bound")
            return
        }

        lifecycleScope.launch {
            runCatching { boundService.command() }
                .onSuccess { state ->
                    Log.i(TAG, "Timer command completed in activity: $name status=${state.status}")
                    updateCurrentFragment(state)
                }
                .onFailure { Log.e(TAG, "Timer command failed in activity: $name", it) }
        }
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
        val started = PomodoroServiceStarter.start(this, intent)
        Log.i(TAG, "PomodoroService start requested. started=$started")

        val bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "PomodoroService bind requested. bound=$bound")
        if (!bound) {
            Log.e(TAG, "PomodoroService bind failed; timer controls will not be available")
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            Log.d(TAG, "Touch up at x=${ev.x.toInt()} y=${ev.y.toInt()}")
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, IntentFilter("com.pomo.STATE_UPDATE"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, IntentFilter("com.pomo.STATE_UPDATE"))
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

    public companion object {
        private const val TAG: String = "PomoMainActivity"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE: Int = 1
    }
}
