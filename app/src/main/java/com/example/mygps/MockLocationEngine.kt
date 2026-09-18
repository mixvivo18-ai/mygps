package com.example.mygps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
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
 * Mock Location Provider engine.
 *
 * Wraps Android's [LocationManager] test-provider APIs:
 *   addTestProvider -> setTestProviderEnabled -> setTestProviderStatus -> setTestProviderLocation
 *   ... -> removeTestProvider (on shutdown)
 *
 * On API 28+ some properties (POWER_USAGE_HIGH, ACCURACY_FINE) were promoted to
 * [ProviderProperties] constants; older APIs used integer literals. Both paths
 * are supported.
 */
class MockLocationEngine(private val context: Context) {

    companion object {
        private const val TAG = "MockLocationEngine"
        const val PROVIDER_GPS = LocationManager.GPS_PROVIDER
        const val DEFAULT_INTERVAL_MS = 1_500L
        const val DEFAULT_ACCURACY_M = 5.0f

        // Pre-API 28 numeric constants (kept for compatibility)
        private const val PRE_28_POWER_HIGH = 3
        private const val PRE_28_ACCURACY_FINE = 1
    }

    enum class StartResult {
        OK,
        NO_LOCATION_PERMISSION,
        SECURITY_EXCEPTION,
        PROVIDER_ALREADY_PRESENT,
        UNKNOWN_ERROR
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickerJob: Job? = null

    data class State(
        val isRunning: Boolean = false,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val accuracy: Float = DEFAULT_ACCURACY_M,
        val message: String? = null
    )

    /** Returns true if the device has granted the app the ACCESS_FINE_LOCATION permission. */
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Start injecting a mock location at (lat, lng).
     *
     * @return [StartResult] describing what happened.
     */
    @SuppressLint("MissingPermission")
    fun start(lat: Double, lng: Double, accuracyM: Float = DEFAULT_ACCURACY_M): StartResult {
        if (!isValidLat(lat) || !isValidLng(lng)) {
            _state.value = _state.value.copy(message = "Invalid coordinates")
            return StartResult.UNKNOWN_ERROR
        }
        if (!hasLocationPermission()) {
            _state.value = _state.value.copy(message = "Location permission not granted")
            return StartResult.NO_LOCATION_PERMISSION
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return StartResult.UNKNOWN_ERROR

        try {
            // If a stale provider exists from a previous crash, remove it first.
            if (lm.allProviders.contains(PROVIDER_GPS)) {
                // Best-effort cleanup of a test-only provider registered by us previously
                try {
                    lm.removeTestProvider(PROVIDER_GPS)
                } catch (_: IllegalArgumentException) {
                    // not ours, ignore
                } catch (_: SecurityException) {
                    // another app owns it
                    return StartResult.PROVIDER_ALREADY_PRESENT
                }
            }

            val powerHigh =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    ProviderProperties.POWER_USAGE_HIGH
                else
                    PRE_28_POWER_HIGH

            val accuracyFine =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    ProviderProperties.ACCURACY_FINE
                else
                    PRE_28_ACCURACY_FINE

            // Re-add with the gps provider id; Android will treat this as a test provider
            // when called from an app that has been selected in Developer Options.
            lm.addTestProvider(
                PROVIDER_GPS,
                /* requiresNetwork = */ false,
                /* requiresSatellite = */ true,
                /* requiresCell = */ false,
                /* hasMonetaryCost = */ false,
                /* supportsAltitude = */ true,
                /* supportsSpeed = */ true,
                /* supportsBearing = */ true,
                /* powerRequirement = */ powerHigh,
                /* accuracy = */ accuracyFine
            )

            lm.setTestProviderEnabled(PROVIDER_GPS, true)

            // API 24+ requires the status to be AVAILABLE before location fixes are accepted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                lm.setTestProviderStatus(
                    PROVIDER_GPS,
                    LocationManager.AVAILABLE,
                    /* extras = */ null,
                    /* callbackIntent = */ null
                )
            } else {
                @Suppress("DEPRECATION")
                lm.setTestProviderStatus(PROVIDER_GPS, LocationManager.AVAILABLE, null)
            }

            // Push an initial fix
            pushLocation(lm, lat, lng, accuracyM)

            _state.value = State(
                isRunning = true,
                latitude = lat,
                longitude = lng,
                accuracy = accuracyM,
                message = "Mocking started"
            )

            // Continuous ticker
            tickerJob?.cancel()
            tickerJob = scope.launch {
                while (isActive) {
                    delay(DEFAULT_INTERVAL_MS)
                    runCatching { pushLocation(lm, lat, lng, accuracyM) }
                        .onFailure { Log.w(TAG, "tick failed", it) }
                }
            }

            return StartResult.OK
        } catch (sec: SecurityException) {
            Log.e(TAG, "SecurityException - app not selected as mock location app", sec)
            _state.value = _state.value.copy(message = "App is not set as mock location app")
            return StartResult.SECURITY_EXCEPTION
        } catch (e: Throwable) {
            Log.e(TAG, "start() failed", e)
            _state.value = _state.value.copy(message = "Start failed: ${e.message}")
            return StartResult.UNKNOWN_ERROR
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                lm.setTestProviderEnabled(PROVIDER_GPS, false)
            }
            try {
                lm.removeTestProvider(PROVIDER_GPS)
            } catch (_: IllegalArgumentException) {
                // provider was already removed
            } catch (_: SecurityException) {
                Log.w(TAG, "Could not remove test provider - permission revoked")
            }
            _state.value = State(message = "Mocking stopped")
        } catch (e: Throwable) {
            Log.e(TAG, "stop() failed", e)
        }
    }

    /** Replace the currently-injected coordinates while the engine is running. */
    @SuppressLint("MissingPermission")
    fun updateLocation(lat: Double, lng: Double, accuracyM: Float = DEFAULT_ACCURACY_M): Boolean {
        val s = _state.value
        if (!s.isRunning) return false
        if (!isValidLat(lat) || !isValidLng(lng)) return false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching { pushLocation(lm, lat, lng, accuracyM) }
            .onSuccess {
                _state.value = s.copy(latitude = lat, longitude = lng, accuracy = accuracyM)
            }
            .isSuccess
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    // --- internals ---

    @SuppressLint("MissingPermission")
    private fun pushLocation(lm: LocationManager, lat: Double, lng: Double, accuracyM: Float) {
        val loc = Location(PROVIDER_GPS).apply {
            latitude = lat
            longitude = lng
            accuracy = accuracyM
            altitude = 0.0
            bearing = 0f
            speed = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        lm.setTestProviderLocation(PROVIDER_GPS, loc)
    }

    companion object {
        @JvmStatic
        fun isValidLat(lat: Double) = !lat.isNaN() && lat in -90.0..90.0

        @JvmStatic
        fun isValidLng(lng: Double) = !lng.isNaN() && lng in -180.0..180.0
    }
}