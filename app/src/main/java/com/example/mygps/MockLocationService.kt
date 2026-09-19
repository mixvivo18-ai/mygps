package com.example.mygps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.location.LocationProvider
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the mock location engine alive even when the app
 * is backgrounded.
 *
 * Why we need this:
 *   When MainActivity goes to the background, its `lifecycleScope` is cancelled,
 *   which stops the engine's 1.5-second location ticker. Real GPS then takes over
 *   and apps see the user's actual location.
 *
 *   A foreground service runs independently of activity lifecycle, so the ticker
 *   keeps firing until the user explicitly stops it.
 *
 * Lifecycle:
 *   ACTION_START — start injection at (lat, lng)
 *   ACTION_STOP  — stop injection and remove test provider
 */
class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "com.example.mygps.action.START"
        const val ACTION_STOP = "com.example.mygps.action.STOP"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ACCURACY = "accuracy"

        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "mock_location_channel"
        private const val NOTIFICATION_ID = 1001
        const val TICK_INTERVAL_MS: Long = 1_000L  // 1 second — fast enough to stay authoritative

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context, lat: Double, lng: Double, accuracyM: Float = 5.0f) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LATITUDE, lat)
                putExtra(EXTRA_LONGITUDE, lng)
                putExtra(EXTRA_ACCURACY, accuracyM)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickerJob: Job? = null

    private var lat: Double = 0.0
    private var lng: Double = 0.0
    private var accuracyM: Float = 5.0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMocking()
                stopForegroundCompat()
                stopSelf()
            }
            ACTION_START -> {
                lat = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
                lng = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
                accuracyM = intent.getFloatExtra(EXTRA_ACCURACY, 5.0f)
                startMocking()
            }
            else -> {
                stopSelf()
            }
        }
        // START_STICKY: Android will recreate this service if killed
        return START_STICKY
    }

    override fun onDestroy() {
        stopMocking()
        scope.cancel()
        super.onDestroy()
    }

    private fun startMocking() {
        // Must call startForeground() within 5 seconds of startForegroundService()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // If the ticker is already running, the new lat/lng/accuracy values will be
        // picked up on the next tick (≤1 second). No need to restart.
        if (tickerJob?.isActive == true) {
            updateNotification()
            return
        }
        tickerJob = scope.launch {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@launch
            val powerHigh =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    ProviderProperties.POWER_USAGE_HIGH
                else
                    3
            val accuracyFine =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    ProviderProperties.ACCURACY_FINE
                else
                    1

            try {
                // Clean up any stale test provider
                runCatching { lm.removeTestProvider(LocationManager.GPS_PROVIDER) }

                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false, true, false, false,
                    true, true, true,
                    powerHigh, accuracyFine
                )
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)

                // Mark status AVAILABLE so clients accept fixes
                try {
                    @Suppress("DEPRECATION")
                    lm.setTestProviderStatus(
                        LocationManager.GPS_PROVIDER,
                        LocationProvider.AVAILABLE,
                        null,
                        SystemClock.elapsedRealtimeNanos()
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "setTestProviderStatus failed", e)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Add provider failed — app not selected as mock app", e)
                stopSelf()
                return@launch
            } catch (e: Throwable) {
                Log.e(TAG, "Add provider failed", e)
                stopSelf()
                return@launch
            }

            _running.value = true
            while (isActive) {
                try {
                    val loc = Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = lat
                        longitude = lng
                        accuracy = accuracyM
                        altitude = 0.0
                        bearing = 0f
                        speed = 0f
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
                    // Re-arm the provider to keep it authoritative
                    lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                } catch (e: Throwable) {
                    Log.w(TAG, "Tick failed", e)
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun stopMocking() {
        tickerJob?.cancel()
        tickerJob = null
        _running.value = false
        runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                try { lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false) } catch (_: Throwable) {}
                try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Throwable) {}
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mock location",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification shown while mygps is injecting a mock location"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_my_location)
            .setContentTitle("SP — mocking location")
            .setContentText("at %.5f, %.5f".format(lat, lng))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }
}