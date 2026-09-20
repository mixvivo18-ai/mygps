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
import android.os.PowerManager
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
import kotlin.random.Random

/**
 * Foreground service that keeps the mock location engine alive even when the app
 * is backgrounded.
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
        const val TICK_INTERVAL_MS: Long = 1_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        // Mutable shared state — accessed from MainActivity for diagnostics.
        // Marked @Volatile because they're written from the service coroutine and
        // read from the UI thread.
        @Volatile var curLat: Double = 0.0
            private set
        @Volatile var curLng: Double = 0.0
            private set
        @Volatile var curAccuracyM: Float = 5.0f
            private set
        @Volatile var curLastError: String? = null
            private set
        @Volatile var curProviderReady: Boolean = false
            private set

        private val _status = MutableStateFlow(
            ServiceStatus(false, 0.0, 0.0, false, null)
        )
        val status: StateFlow<ServiceStatus> = _status.asStateFlow()

        data class ServiceStatus(
            val running: Boolean,
            val lat: Double,
            val lng: Double,
            val providerReady: Boolean,
            val lastError: String?
        )

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

        @JvmStatic
        fun snapshotStatus(): ServiceStatus = ServiceStatus(
            running = _running.value,
            lat = curLat,
            lng = curLng,
            providerReady = curProviderReady,
            lastError = curLastError
        )

        /** Internal — called from the service instance to push state changes. */
        internal fun setState(
            running: Boolean? = null,
            lat: Double? = null,
            lng: Double? = null,
            accuracyM: Float? = null,
            providerReady: Boolean? = null,
            lastError: String? = "__CLEAR__"
        ) {
            if (running != null) _running.value = running
            if (lat != null) curLat = lat
            if (lng != null) curLng = lng
            if (accuracyM != null) curAccuracyM = accuracyM
            if (providerReady != null) curProviderReady = providerReady
            if (lastError != "__CLEAR__") curLastError = lastError

            _status.value = ServiceStatus(
                running = _running.value,
                lat = curLat,
                lng = curLng,
                providerReady = curProviderReady,
                lastError = curLastError
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
                val lat = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
                val accuracyM = intent.getFloatExtra(EXTRA_ACCURACY, 5.0f)
                setState(lat = lat, lng = lng, accuracyM = accuracyM)
                startMocking()
            }
            else -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMocking()
        scope.cancel()
        super.onDestroy()
    }

    private fun startMocking() {
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

        // Acquire a partial wake lock so doze doesn't kill our ticker.
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SP::MockLocationTicker"
                ).apply { setReferenceCounted(false) }
            }
            wakeLock?.acquire(60L * 60L * 1000L)  // 1 hour timeout
        } catch (e: Throwable) {
            Log.w(TAG, "wakeLock acquire failed", e)
        }

        if (tickerJob?.isActive == true) {
            updateNotification()
            return
        }
        tickerJob = scope.launch {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
                setState(lastError = "LocationManager service not available", running = false)
                stopSelf()
                return@launch
            }
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
                runCatching { lm.removeTestProvider(LocationManager.GPS_PROVIDER) }

                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false, true, false, false,
                    true, true, true,
                    powerHigh, accuracyFine
                )
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)

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

                val providers = lm.allProviders
                if (LocationManager.GPS_PROVIDER in providers) {
                    setState(providerReady = true, lastError = null)
                    Log.i(TAG, "Test provider registered. providers=$providers")
                } else {
                    setState(
                        providerReady = false,
                        lastError = "Provider not in allProviders list (${providers})"
                    )
                    Log.e(TAG, "Provider missing from allProviders: $providers")
                }
            } catch (e: SecurityException) {
                setState(
                    lastError = "App not selected as mock location app. Enable in Developer Options.",
                    providerReady = false
                )
                Log.e(TAG, "Add provider failed — app not selected as mock app", e)
                stopSelf()
                return@launch
            } catch (e: Throwable) {
                setState(lastError = "addTestProvider failed: ${e.message}", providerReady = false)
                Log.e(TAG, "Add provider failed", e)
                stopSelf()
                return@launch
            }

            setState(running = true, lastError = null, providerReady = true)
            var lastFixMs = 0L
            var currentBearing = Random.nextDouble(0.0, 360.0)
            var currentSpeed = 0f
            var consecutiveErrors = 0

            while (isActive) {
                try {
                    val jitterLat = curLat + Random.nextDouble(-0.00009, 0.00009)
                    val jitterLng = curLng + Random.nextDouble(-0.00009, 0.00009)
                    val jitterAcc = curAccuracyM + Random.nextFloat() * 8f - 4f
                    currentBearing = (currentBearing + Random.nextDouble(-5.0, 5.0) + 360.0) % 360.0
                    currentSpeed = (currentSpeed + Random.nextFloat() * 0.4f - 0.2f).coerceIn(0f, 1.5f)
                    val altitude = 5.0 + Random.nextDouble(-3.0, 3.0)

                    val now = System.currentTimeMillis()

                    val loc = Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = jitterLat
                        longitude = jitterLng
                        accuracy = jitterAcc.coerceAtLeast(2.5f)
                        this.altitude = altitude
                        bearing = currentBearing.toFloat()
                        speed = currentSpeed
                        time = now
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() +
                            Random.nextLong(0L, 50_000_000L)
                    }
                    lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                    lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
                    consecutiveErrors = 0
                    setState(lastError = null, providerReady = true)

                    lastFixMs = now
                } catch (e: SecurityException) {
                    setState(lastError = "Mock app access revoked (unselected?)", providerReady = false)
                    consecutiveErrors++
                    Log.w(TAG, "SecurityException on tick (mock app revoked?)", e)
                    if (consecutiveErrors > 3) {
                        stopSelf()
                        return@launch
                    }
                } catch (e: Throwable) {
                    setState(lastError = "tick failed: ${e.message}")
                    consecutiveErrors++
                    Log.w(TAG, "Tick failed ($consecutiveErrors consecutive)", e)
                    if (consecutiveErrors > 10) {
                        stopSelf()
                        return@launch
                    }
                }

                val jitterMs = TICK_INTERVAL_MS + Random.nextLong(-150L, 150L)
                delay(jitterMs)
            }
        }
    }

    private fun stopMocking() {
        tickerJob?.cancel()
        tickerJob = null
        setState(running = false, providerReady = false)
        runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                try { lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false) } catch (_: Throwable) {}
                try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Throwable) {}
            }
        }
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
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
                description = "Notification shown while SP is injecting a mock location"
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
            .setContentText("at %.5f, %.5f".format(curLat, curLng))
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